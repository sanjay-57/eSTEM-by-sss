package com.sss.estem.player

import com.sss.estem.audio.BeatGrid
import com.sss.estem.audio.Effect
import com.sss.estem.audio.StemPlayerEngine
import com.sss.estem.data.db.Recording
import com.sss.estem.data.db.TrackWithStems
import com.sss.estem.data.model.SeparationState
import com.sss.estem.data.model.Stem
import com.sss.estem.data.repo.LibraryRepository
import com.sss.estem.data.storage.StemStorage
import com.sss.estem.design.puck.PuckButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

enum class PuckMode { MAIN, EFFECTS, LOOPS }

/**
 * Where 1x sits on the speed arm. Centre, so the arm runs half-speed to double either side of it
 * and unity is a place you can find without looking.
 */
private const val SPEED_UNITY_AXIS = 0.5f

/** One song on one deck. Everything here belongs to the track, not to the machine. */
data class DeckState(
    val trackId: Long = -1,
    val title: String = "",
    val artist: String? = null,
    val loaded: Boolean = false,
    val stemLevels: List<Float> = List(Stem.COUNT) { 1f },
    /** Live signal magnitude per stem — what makes the lights move with the music. */
    val stemEnergies: List<Float> = List(Stem.COUNT) { 0f },
    val isolated: Set<Int> = emptySet(),
    val muted: Set<Int> = emptySet(),
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Detected tempo, or 0 when the track has no usable grid. */
    val bpm: Float = 0f,
    val speed: Float = 1f,
    /** 0f..1f along the speed arm, with 1x at the centre. */
    val speedAxis: Float = SPEED_UNITY_AXIS,
    /** Loop length in beats; 0 is off. */
    val loopBeats: Int = 0,
    val looping: Boolean = false,
    val loopAxis: Float = 0f,
) {
    /** Tempo as it is actually playing, which is what has to match for two decks to sit together. */
    val effectiveBpm: Float get() = bpm * speed
}

data class PlayerState(
    val decks: List<DeckState> = listOf(DeckState(), DeckState()),
    /** Which deck the puck is driving. The face has four arms, not eight. */
    val focus: Int = 0,
    /** 0 is all of deck A, 1 is all of deck B. */
    val crossfade: Float = 0f,
    /** True while the two decks are locked to one tempo. Turns the panel blue. */
    val synced: Boolean = false,
    val autoMixing: Boolean = false,
    val master: Float = 0.8f,
    val locked: Boolean = false,
    val mode: PuckMode = PuckMode.MAIN,
    val effect: Effect? = null,
    /**
     * Continuous position along the effect-selection strip, 0f..1f.
     *
     * The strip is what the finger actually moves; which of the eight effects that lands on is
     * read out of it. Stepping the ordinal per touch event instead meant one flick crossed all
     * eight, because a drag sends events at frame rate, not once per intended step.
     */
    val effectAxis: Float = 0f,
    val effectIntensity: Float = 0f,
    /** True while speed changes tempo alone. False makes it a turntable. */
    val keepPitch: Boolean = true,
    val recording: Boolean = false,
    val recordProgress: Float = 0f,
    val scrubbing: Boolean = false,
    val streamSampleRate: Int = 0,
    val xRuns: Int = 0,
    /** Output buffer in frames. Climbs on its own when the device cannot hold the deadline. */
    val bufferFrames: Int = 0,
    val message: String? = null,
) {
    /** The deck the puck is driving. */
    val focused: DeckState get() = decks[focus]

    /** The other one — cued, mixing in, or empty. */
    val other: DeckState get() = decks[1 - focus]

    val bothLoaded: Boolean get() = decks.all { it.loaded }
}

/**
 * The whole player, owned by the application rather than by a screen.
 *
 * The three pages are views onto one device — swiping from the front to the back must not stop
 * playback or reset the sliders, so none of this state can live in a per-screen ViewModel.
 *
 * Chords (two buttons at once) and long presses resolve here rather than in the UI, so the
 * touchscreen today and a gamepad mapping later can both feed the same six button-down/button-up
 * events without duplicating behaviour.
 *
 * Two decks, one puck. The face drives whichever deck has focus; the crossfader, the effects rack
 * and the recorder always belong to the mix.
 */
class PlayerController(
    private val library: LibraryRepository,
    private val storage: StemStorage,
    private val engine: StemPlayerEngine,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    val queue: Flow<List<TrackWithStems>> = library.observeLibrary()
    val recordings: Flow<List<Recording>> = library.observeRecordings()

    private val pressed = mutableSetOf<PuckButton>()
    private var chordConsumed = false
    private val longPressJobs = mutableMapOf<PuckButton, Job>()
    private var scrubJob: Job? = null
    private var barScrubJob: Job? = null
    private var recordTimerJob: Job? = null
    private var autoMixJob: Job? = null
    private var syncJob: Job? = null
    private val loadJobs = arrayOfNulls<Job>(DECKS)

    private val sampleRates = IntArray(DECKS) { 44100 }
    /** Each deck's beat grid, or null where the detector found no steady pulse. */
    private val grids = arrayOfNulls<BeatGrid>(DECKS)
    private var effectStackDepth = 0

    /** Where the finger is during a bar scrub, in frames. Read by the servo. */
    private var scrubTargetFrames = 0L

    /** Reused so the 60 Hz LED poll does not allocate a new array every frame. */
    private val energyScratch = Array(DECKS) { FloatArray(Stem.COUNT) }

    init {
        // The lights follow the music, so this has to run at frame rate, not at the once-per-100ms
        // cadence the clock needs.
        scope.launch {
            var tick = 0
            while (isActive) {
                val snapshot = _state.value
                if (snapshot.decks.any { it.loaded }) {
                    val transport = tick % TRANSPORT_EVERY == 0
                    val updated = snapshot.decks.mapIndexed { index, deck ->
                        if (!deck.loaded) return@mapIndexed deck
                        val handle = engine.decks[index]
                        handle.readStemEnergies(energyScratch[index])
                        val energies = energyScratch[index].toList()

                        // Transport values change slowly; refreshing them every LED frame would
                        // churn state for nothing.
                        if (transport) {
                            val rate = sampleRates[index].coerceAtLeast(1)
                            deck.copy(
                                stemEnergies = energies,
                                positionMs = handle.positionFrames * 1000 / rate,
                                playing = handle.isPlaying,
                            )
                        } else {
                            deck.copy(stemEnergies = energies)
                        }
                    }

                    if (transport) {
                        _state.update {
                            it.copy(
                                decks = updated,
                                recording = engine.isRecording,
                                xRuns = engine.xRunCount,
                                bufferFrames = engine.bufferFrames,
                                streamSampleRate = engine.streamSampleRate,
                            )
                        }
                    } else {
                        _state.update { it.copy(decks = updated) }
                    }
                }
                tick++
                delay(LED_POLL_MS)
            }
        }
    }

    // ------------------------------------------------------------ loading

    /** Loads into the focused deck and plays it. */
    fun load(entry: TrackWithStems) = load(entry, _state.value.focus, play = true)

    /**
     * Loads into the deck that is *not* focused, without starting it.
     *
     * Cueing is the whole point of the second deck: the track is mapped, its grid is read and its
     * tempo can be matched while the audience hears none of it, because the crossfader has not
     * moved. Loading it does not clear the effect running on the deck you can hear either.
     */
    fun cue(entry: TrackWithStems) = load(entry, 1 - _state.value.focus, play = false)

    private fun load(entry: TrackWithStems, deckIndex: Int, play: Boolean) {
        if (entry.track.separationState != SeparationState.READY) {
            message("${entry.track.title} is still being separated")
            return
        }
        loadJobs[deckIndex]?.cancel()
        loadJobs[deckIndex] = scope.launch {
            val stems = entry.stems ?: library.stems(entry.track.id) ?: return@launch
            val files = listOf(
                stems.vocalsPath, stems.drumsPath, stems.bassPath, stems.melodyPath,
            ).map(::File)
            if (files.any { !it.isFile }) {
                message("Stem files are missing on disk")
                return@launch
            }

            engine.start()
            val handle = engine.decks[deckIndex]
            if (!handle.loadTrack(files, stems.sampleRate, stems.channelCount)) {
                message("Engine could not load these stems")
                return@launch
            }

            sampleRates[deckIndex] = stems.sampleRate
            grids[deckIndex] = if (stems.bpm > 0f) {
                BeatGrid(stems.bpm, stems.firstBeatFrame, stems.sampleRate)
            } else {
                null
            }

            val levels = List(Stem.COUNT) { 1f }
            Stem.ORDER.forEachIndexed { index, stem -> handle.setStemGain(stem, levels[index]) }
            handle.setIsolated(emptySet())
            handle.setMuted(emptySet())
            // Speed, loop and pitch mode all belong to the track that was on this deck, not to the
            // one arriving — a 4-bar loop measured against the old grid means nothing against this.
            handle.rate = 1f
            handle.setLooping(false)
            handle.setLoopRegion(-1, -1)
            handle.keepPitch = _state.value.keepPitch
            handle.isPlaying = play

            if (deckIndex == _state.value.focus) {
                engine.clearEffects()
                effectStackDepth = 0
            }

            // Loading either deck breaks whatever match was in force: one of the two tempi it was
            // computed from has just been replaced.
            stopSync()

            updateDeck(deckIndex) {
                DeckState(
                    trackId = entry.track.id,
                    title = entry.track.title,
                    artist = entry.track.artist,
                    loaded = true,
                    playing = play,
                    stemLevels = levels,
                    bpm = grids[deckIndex]?.bpm ?: 0f,
                    durationMs = stems.frameCount * 1000 /
                        stems.sampleRate.coerceAtLeast(1),
                )
            }
            if (deckIndex == _state.value.focus) {
                _state.update {
                    it.copy(effect = null, effectAxis = 0f, effectIntensity = 0f,
                        mode = PuckMode.MAIN, message = null)
                }
            } else {
                message("Cued ${entry.track.title} to deck ${deckName(deckIndex)}")
            }
        }
    }

    // ---------------------------------------------------------------- bars

    /** @param delta already resolved so that "away from centre" is positive. */
    fun onBarDrag(barIndex: Int, delta: Float) {
        if (_state.value.locked) return
        when (_state.value.mode) {
            PuckMode.MAIN -> adjustStem(barIndex, delta)
            PuckMode.EFFECTS -> adjustEffectAxis(barIndex, delta)
            PuckMode.LOOPS -> adjustLoopAxis(barIndex, delta)
        }
    }

    private fun adjustStem(barIndex: Int, delta: Float) {
        val stem = Stem.ORDER.getOrNull(barIndex) ?: return
        val levels = focusedDeck().stemLevels.toMutableList()
        levels[barIndex] = (levels[barIndex] + delta).coerceIn(0f, 1f)
        focusedHandle().setStemGain(stem, levels[barIndex])
        updateFocused { it.copy(stemLevels = levels) }
    }

    /**
     * Matches the renderer: arms 0-1 are the selection strip, 2-3 are the intensity.
     *
     * The strip is a continuous position that the choice is read out of, rather than an ordinal
     * nudged per event. Dragging outward moves the lit LED outward and raises the selection —
     * both of which used to be the wrong way round, and neither was usable in any case, because a
     * drag delivers events at frame rate and each one stepped the selection by a whole effect.
     */
    private fun adjustEffectAxis(barIndex: Int, delta: Float) {
        if (barIndex < SELECTION_ARMS) {
            val axis = (_state.value.effectAxis + delta).coerceIn(0f, 1f)
            val index = indexOnStrip(axis, Effect.COUNT)
            _state.update { it.copy(effectAxis = axis) }
            if (index != _state.value.effect?.ordinal) selectEffect(Effect.entries[index])
        } else {
            setEffectIntensity((_state.value.effectIntensity + delta).coerceIn(0f, 1f))
        }
    }

    /**
     * Arms 0-1 pick the loop length, 2-3 are the speed.
     *
     * Same two-axis shape as the effects menu, because it is the same four arms and the same
     * fingers — the hardware's menus differ in what they set, not in how they are driven.
     */
    private fun adjustLoopAxis(barIndex: Int, delta: Float) {
        if (barIndex < SELECTION_ARMS) {
            val axis = (focusedDeck().loopAxis + delta).coerceIn(0f, 1f)
            updateFocused { it.copy(loopAxis = axis) }
            setLoopBeats(LOOP_BEATS[indexOnStrip(axis, LOOP_BEATS.size)])
        } else {
            setSpeedAxis(focusedDeck().speedAxis + delta)
        }
    }

    /** Which of `count` choices a 0f..1f position along the two-arm strip lands on. */
    private fun indexOnStrip(axis: Float, count: Int): Int =
        (axis * count).toInt().coerceIn(0, count - 1)

    fun selectEffect(effect: Effect) {
        engine.setEffect(effect)
        engine.setEffectIntensity(_state.value.effectIntensity)
        effectStackDepth = 1
        _state.update { it.copy(effect = effect) }
    }

    fun setEffectIntensity(intensity: Float) {
        engine.setEffectIntensity(intensity)
        _state.update { it.copy(effectIntensity = intensity.coerceIn(0f, 1f)) }
    }

    // ------------------------------------------------------- loops and speed

    /**
     * Arms a loop of `beats` on the focused deck, starting at the grid line at or before the
     * playhead. 0 disarms it.
     *
     * Needs a grid. Looping between two arbitrary points is possible and useless — it lands mid-bar
     * and every repeat stumbles, which is worse than not looping at all, so a track the detector
     * could not read says so instead of pretending.
     */
    fun setLoopBeats(beats: Int) {
        val index = _state.value.focus
        val grid = grids[index]
        val handle = engine.decks[index]

        if (beats > 0 && (grid == null || !grid.valid)) {
            handle.setLooping(false)
            updateFocused { it.copy(loopBeats = 0, looping = false) }
            message("No beat grid for this track")
            return
        }
        if (beats <= 0 || grid == null) {
            handle.setLooping(false)
            handle.setLoopRegion(-1, -1)
            updateFocused { it.copy(loopBeats = 0, looping = false) }
            return
        }

        // Whole bars snap to a bar line, shorter loops to a beat. A two-beat loop forced onto a bar
        // line can only ever be the front half of the bar; snapped to a beat it can be either half.
        val position = handle.positionFrames
        val start = if (beats >= BeatGrid.DEFAULT_BEATS_PER_BAR) {
            grid.floorToBar(position)
        } else {
            grid.floorToBeat(position)
        }

        handle.setLoopRegion(start, start + grid.framesForBeats(beats))
        handle.setLooping(true)
        updateFocused { it.copy(loopBeats = beats, looping = true) }
    }

    /**
     * Speed from the arm position: half at the inner end, double at the outer, unity in the middle.
     *
     * Exponential rather than linear so a given distance is the same musical interval wherever the
     * finger is — halving and doubling sit symmetrically either side of the centre, which a linear
     * map cannot do. The centre has a detent, because unity has to be exactly reachable and 1.02x
     * against another deck is audibly wrong.
     */
    fun setSpeedAxis(axis: Float) {
        val clamped = axis.coerceIn(0f, 1f)
        val offset = clamped - SPEED_UNITY_AXIS
        val speed = if (abs(offset) < SPEED_DETENT) 1f else 2f.pow(offset * 2f)
        focusedHandle().rate = speed
        updateFocused { it.copy(speedAxis = clamped, speed = speed) }
        // Moving one deck's speed by hand is a decision to stop matching the other.
        if (_state.value.synced) stopSync()
    }

    /** True: speed moves tempo alone. False: it moves pitch with it, like a turntable. */
    fun setKeepPitch(keep: Boolean) {
        engine.decks.forEach { it.keepPitch = keep }
        _state.update { it.copy(keepPitch = keep) }
    }

    // ------------------------------------------------------------ two decks

    /** Swaps which deck the four arms are driving. */
    fun toggleFocus() = setFocus(1 - _state.value.focus)

    fun setFocus(index: Int) {
        val target = index.coerceIn(0, DECKS - 1)
        if (target == _state.value.focus) return
        _state.update { it.copy(focus = target, mode = PuckMode.MAIN) }
    }

    /** 0 is all of deck A, 1 is all of deck B. */
    fun setCrossfade(position: Float) {
        val clamped = position.coerceIn(0f, 1f)
        engine.crossfade = clamped
        _state.update { it.copy(crossfade = clamped) }
    }

    /**
     * Pulls the other deck to the focused deck's tempo and lines their bars up.
     *
     * Two things, and the second is the one people forget. Matching tempo alone leaves two tracks
     * running at the same speed with their downbeats in different places, which sounds worse than
     * not matching at all — so the other deck is also seeked so that its position within the bar
     * equals the focused deck's.
     *
     * Held, not set and forgotten. The two tempi come from an estimator accurate to a fraction of
     * a per cent, and a fraction of a per cent is a beat of drift every few minutes, so a slow
     * servo keeps trimming the rate to hold the phase where it was put.
     */
    fun sync() {
        val here = _state.value.focus
        val there = 1 - here
        val ours = grids[here]
        val theirs = grids[there]

        if (!_state.value.bothLoaded) {
            message("Cue a track to the other deck first")
            return
        }
        if (ours == null || !ours.valid || theirs == null || !theirs.valid) {
            message("Both tracks need a beat grid to sync")
            return
        }

        val target = _state.value.decks[here].effectiveBpm
        if (target <= 0f) return

        val ratio = (target / theirs.bpm).coerceIn(MIN_SYNC_RATIO, MAX_SYNC_RATIO)
        val other = engine.decks[there]
        other.keepPitch = true
        other.rate = ratio

        alignBars(here, there, ours, theirs)

        updateDeck(there) { it.copy(speed = ratio, speedAxis = axisForSpeed(ratio)) }
        _state.update { it.copy(synced = true) }

        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                delay(SYNC_TRIM_MS)
                holdPhase(here, there, ours, theirs, ratio)
            }
        }
    }

    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        if (_state.value.synced) _state.update { it.copy(synced = false) }
    }

    /** Seeks `there` so its position within the bar equals `here`'s. */
    private fun alignBars(here: Int, there: Int, ours: BeatGrid, theirs: BeatGrid) {
        val ourBars = barsAt(engine.decks[here].positionFrames, ours)
        val theirBars = barsAt(engine.decks[there].positionFrames, theirs)
        val phase = ourBars - floor(ourBars)

        // The bar they are already in, replayed from the point in it that we are at. Landing in the
        // *current* bar rather than the next keeps the seek short enough to be inaudible under the
        // deck's own seek fade.
        val wanted = floor(theirBars) + phase
        val frames = theirs.firstBeatFrame +
            (wanted * BeatGrid.DEFAULT_BEATS_PER_BAR * theirs.framesPerBeat).toLong()
        engine.decks[there].seekToFrame(frames.coerceAtLeast(0))
    }

    /**
     * Trims the followed deck's rate by a fraction of a per cent to cancel accumulated drift.
     *
     * A rate trim rather than a seek: a seek is a fade and a fade every two seconds is audible,
     * whereas a 0.2% rate change for two seconds moves the phase by milliseconds and is not.
     */
    private fun holdPhase(here: Int, there: Int, ours: BeatGrid, theirs: BeatGrid, base: Float) {
        val ourBars = barsAt(engine.decks[here].positionFrames, ours)
        val theirBars = barsAt(engine.decks[there].positionFrames, theirs)

        // Signed distance to the nearest bar line, in bars: -0.5..0.5.
        var error = (theirBars - ourBars) % 1.0
        if (error > 0.5) error -= 1.0
        if (error < -0.5) error += 1.0

        val trim = (-error * PHASE_TRIM_GAIN).coerceIn(-PHASE_TRIM_MAX, PHASE_TRIM_MAX)
        engine.decks[there].rate = (base * (1.0 + trim)).toFloat()
    }

    private fun barsAt(frames: Long, grid: BeatGrid): Double =
        (frames - grid.firstBeatFrame) /
            (grid.framesPerBeat * BeatGrid.DEFAULT_BEATS_PER_BAR)

    /** Inverse of [setSpeedAxis], so a synced deck's arm shows where its speed actually is. */
    private fun axisForSpeed(speed: Float): Float {
        if (speed <= 0f) return SPEED_UNITY_AXIS
        val octaves = kotlin.math.ln(speed.toDouble()) / kotlin.math.ln(2.0)
        return (SPEED_UNITY_AXIS + octaves / 2.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Syncs, starts the other deck, and walks the crossfader over `bars` of the current tempo.
     *
     * The automatic half of two-song mixing. The semi-manual half is the same thing with the last
     * step done by a finger, which is why [sync] and [setCrossfade] are public on their own.
     */
    fun autoMix(bars: Int = AUTO_MIX_BARS) {
        if (_state.value.autoMixing) {
            cancelAutoMix()
            return
        }
        if (!_state.value.bothLoaded) {
            message("Cue a track to the other deck first")
            return
        }

        sync()
        if (!_state.value.synced) return

        val here = _state.value.focus
        val there = 1 - here
        engine.decks[there].isPlaying = true

        val bpm = _state.value.decks[here].effectiveBpm
        if (bpm <= 0f) return
        val totalMs = (bars * BeatGrid.DEFAULT_BEATS_PER_BAR * 60_000.0 / bpm).toLong()
        val from = _state.value.crossfade
        val to = if (there == 1) 1f else 0f

        _state.update { it.copy(autoMixing = true) }
        autoMixJob?.cancel()
        autoMixJob = scope.launch {
            var elapsed = 0L
            while (isActive && elapsed < totalMs) {
                delay(AUTO_MIX_STEP_MS)
                elapsed += AUTO_MIX_STEP_MS
                setCrossfade(from + (to - from) * (elapsed.toFloat() / totalMs))
            }
            setCrossfade(to)
            // The deck that has just become the audible one is the deck the puck should be on.
            _state.update { it.copy(autoMixing = false, focus = there) }
            message("Mixed into ${_state.value.decks[there].title}")
        }
    }

    fun cancelAutoMix() {
        autoMixJob?.cancel()
        autoMixJob = null
        _state.update { it.copy(autoMixing = false) }
    }

    /**
     * Isolate is **momentary**: hold an arm and only that stem plays, let go and everything comes
     * straight back. Holding several arms solos all of them together.
     *
     * It restores by itself because isolate is a separate mask over the mix and never touches the
     * slider gains — there is no previous state to save, because nothing was overwritten.
     */
    fun onIsolateStart(barIndex: Int) {
        if (_state.value.locked || _state.value.mode != PuckMode.MAIN) return
        applyIsolated(focusedDeck().isolated + barIndex)
    }

    fun onIsolateEnd(barIndex: Int) {
        applyIsolated(focusedDeck().isolated - barIndex)
    }

    private fun applyIsolated(isolated: Set<Int>) {
        focusedHandle().setIsolated(isolated.mapNotNull { Stem.ORDER.getOrNull(it) }.toSet())
        updateFocused { it.copy(isolated = isolated) }
    }

    fun toggleMute(barIndex: Int) {
        val muted = focusedDeck().muted.toMutableSet()
        if (!muted.add(barIndex)) muted.remove(barIndex)
        focusedHandle().setMuted(muted.mapNotNull { Stem.ORDER.getOrNull(it) }.toSet())
        updateFocused { it.copy(muted = muted) }
    }

    // ------------------------------------------------------------- buttons

    fun onButtonDown(button: PuckButton) {
        pressed += button
        if (resolveChord()) return
        longPressJobs[button] = scope.launch {
            delay(LONG_PRESS_MS)
            onLongPress(button)
        }
    }

    fun onButtonUp(button: PuckButton) {
        pressed -= button
        val job = longPressJobs.remove(button)
        val wasLongPress = job?.isCompleted == true
        job?.cancel()
        stopScrub()

        if (pressed.isEmpty() && chordConsumed) {
            chordConsumed = false
            return
        }
        if (chordConsumed || wasLongPress) return
        onTap(button)
    }

    private fun resolveChord(): Boolean {
        val hasCenter = PuckButton.CENTER in pressed
        when {
            // Centre + volume-down is the slider lock. It was centre + power, which stopped being
            // reachable the moment power came off the face — the rocker is the one part of the
            // hardware's button set the phone still physically has, so the chord moves onto it and
            // sits symmetrically opposite the effects chord. POWER stays in the condition because
            // a gamepad arrives as these same intents and does have a button to spare.
            hasCenter && (PuckButton.VOL_DOWN in pressed || PuckButton.POWER in pressed) ->
                toggleLock()

            hasCenter && PuckButton.VOL_UP in pressed -> openEffects()
            PuckButton.PREV in pressed && PuckButton.NEXT in pressed -> toggleFocus()
            else -> return false
        }
        chordConsumed = true
        longPressJobs.values.forEach { it.cancel() }
        longPressJobs.clear()
        return true
    }

    private fun onTap(button: PuckButton) {
        if (_state.value.locked && button != PuckButton.POWER) return
        when (button) {
            PuckButton.CENTER -> onCenterTap()
            PuckButton.POWER -> if (_state.value.locked) toggleLock() else exitMenu()
            PuckButton.PREV -> seekRelative(-SKIP_MS)
            PuckButton.NEXT -> seekRelative(SKIP_MS)
            PuckButton.VOL_UP -> nudgeMaster(MASTER_STEP)
            PuckButton.VOL_DOWN -> nudgeMaster(-MASTER_STEP)
        }
    }

    private fun onLongPress(button: PuckButton) {
        if (_state.value.locked) return
        when (button) {
            PuckButton.VOL_UP -> startRecording()
            PuckButton.PREV -> startScrub(-1)
            PuckButton.NEXT -> startScrub(1)
            PuckButton.CENTER -> openLoops()
            else -> Unit
        }
    }

    /**
     * One tap pauses. Further taps strip what has been added — effects, then isolates — which is
     * the hardware's "undo the last thing" behaviour once playback is already stopped.
     */
    private fun onCenterTap() {
        if (_state.value.mode != PuckMode.MAIN) {
            exitMenu()
            return
        }
        val deck = focusedDeck()
        if (!deck.loaded) return
        if (deck.playing) {
            focusedHandle().isPlaying = false
            updateFocused { it.copy(playing = false) }
            return
        }
        if (effectStackDepth > 0) {
            engine.clearEffects()
            effectStackDepth = 0
            _state.update { it.copy(effect = null, effectIntensity = 0f) }
            return
        }
        if (deck.isolated.isNotEmpty()) {
            focusedHandle().setIsolated(emptySet())
            updateFocused { it.copy(isolated = emptySet()) }
            return
        }
        focusedHandle().isPlaying = true
        updateFocused { it.copy(playing = true) }
    }

    fun togglePlayPause() {
        val deck = focusedDeck()
        if (!deck.loaded) return
        val playing = !deck.playing
        focusedHandle().isPlaying = playing
        updateFocused { it.copy(playing = playing) }
    }

    private fun toggleLock() = _state.update { it.copy(locked = !it.locked) }

    fun openEffects() {
        selectEffect(_state.value.effect ?: Effect.REVERB)
        _state.update { it.copy(mode = PuckMode.EFFECTS) }
    }

    private fun openLoops() {
        val message = if (grids[_state.value.focus]?.valid == true) null else "No beat grid — speed only"
        _state.update { it.copy(mode = PuckMode.LOOPS, message = message) }
    }

    fun exitMenu() = _state.update { it.copy(mode = PuckMode.MAIN, message = null) }

    fun seekRelative(deltaMs: Long) {
        val deck = focusedDeck()
        if (!deck.loaded) return
        focusedHandle().seekToMillis(
            (deck.positionMs + deltaMs).coerceAtLeast(0),
            sampleRates[_state.value.focus],
        )
    }

    fun seekTo(positionMs: Long) {
        if (!focusedDeck().loaded) return
        focusedHandle().seekToMillis(positionMs.coerceAtLeast(0), sampleRates[_state.value.focus])
    }

    private fun startScrub(direction: Int) {
        scrubJob?.cancel()
        scrubJob = scope.launch {
            while (isActive) {
                seekRelative(direction * SCRUB_STEP_MS)
                delay(SCRUB_INTERVAL_MS)
            }
        }
    }

    private fun stopScrub() {
        scrubJob?.cancel()
        scrubJob = null
    }

    // -------------------------------------------------------------- scrubbing

    /**
     * A real scrub: the cursor chases the finger at whatever rate that takes, and pitches with it.
     *
     * This is what the bar could not do before the deck could read at a fractional rate. Seeking
     * once per touch move was tried and it buzzes — sixty step discontinuities a second is not a
     * scrub, it is sixty clicks — so the bar used to wait for the finger to lift.
     *
     * Pitch tracks the drag on purpose, so [setKeepPitch] is suspended for the duration. A scrub
     * with the pitch held still does not sound like moving a record, it sounds like a search.
     */
    fun beginScrub(positionMs: Long) {
        if (!focusedDeck().loaded) return
        val index = _state.value.focus
        val handle = engine.decks[index]
        scrubTargetFrames = framesOf(positionMs, index)
        handle.keepPitch = false
        _state.update { it.copy(scrubbing = true) }
        stopSync()

        barScrubJob?.cancel()
        barScrubJob = scope.launch {
            val framesPerTick = sampleRates[index] * SCRUB_TICK_MS / 1000f
            while (isActive) {
                val error = scrubTargetFrames - handle.positionFrames
                if (abs(error) > framesOf(SCRUB_JUMP_MS, index)) {
                    // Further than the cursor could cover at a playable rate. Chasing it would be
                    // a long rush to somewhere the finger has already left, so it jumps — through
                    // the deck's seek fade, so the jump itself is silent.
                    handle.rate = 1f
                    handle.seekToFrame(scrubTargetFrames)
                } else {
                    handle.rate = (error / framesPerTick).coerceIn(-MAX_SCRUB_RATE, MAX_SCRUB_RATE)
                }
                delay(SCRUB_TICK_MS)
            }
        }
    }

    fun updateScrub(positionMs: Long) {
        scrubTargetFrames = framesOf(positionMs, _state.value.focus)
    }

    fun endScrub(positionMs: Long) {
        barScrubJob?.cancel()
        barScrubJob = null
        val deck = focusedDeck()
        if (!deck.loaded) return
        val index = _state.value.focus
        val handle = engine.decks[index]
        // Land exactly where the finger left rather than wherever the servo had reached.
        handle.seekToFrame(framesOf(positionMs, index))
        handle.rate = deck.speed
        handle.keepPitch = _state.value.keepPitch
        _state.update { it.copy(scrubbing = false) }
    }

    private fun framesOf(millis: Long, deckIndex: Int): Long =
        millis.coerceAtLeast(0) * sampleRates[deckIndex] / 1000L

    fun nudgeMaster(delta: Float) = setMaster(_state.value.master + delta)

    fun setMaster(value: Float) {
        val master = value.coerceIn(0f, 1f)
        engine.setMasterVolume(master)
        _state.update { it.copy(master = master) }
    }

    // ----------------------------------------------------------- recording

    fun startRecording() {
        if (engine.isRecording || _state.value.decks.none { it.loaded }) return
        val file = storage.recordingFile("mix-${System.currentTimeMillis()}")
        val maxFrames = RECORD_SECONDS.toLong() * engine.streamSampleRate.coerceAtLeast(1)

        if (!engine.startRecording(file, maxFrames)) {
            message("Could not start recording")
            return
        }
        _state.update { it.copy(recording = true, recordProgress = 0f) }

        recordTimerJob?.cancel()
        recordTimerJob = scope.launch {
            while (isActive && engine.isRecording) {
                val progress = engine.recordedFrames.toFloat() / maxFrames.toFloat().coerceAtLeast(1f)
                _state.update { it.copy(recordProgress = progress.coerceIn(0f, 1f)) }
                delay(POSITION_POLL_MS)
            }
            finishRecording(file)
        }
    }

    fun stopRecording() {
        recordTimerJob?.cancel()
        if (engine.isRecording) {
            scope.launch { finishRecording(null) }
        }
    }

    private suspend fun finishRecording(file: File?) {
        val target = file ?: return run {
            engine.stopRecording()
            _state.update { it.copy(recording = false, recordProgress = 0f) }
        }
        engine.stopRecording()
        val frames = engine.recordedFrames
        val durationMs = frames * 1000 / engine.streamSampleRate.coerceAtLeast(1)
        if (frames > 0) {
            val deck = focusedDeck()
            library.addRecording(
                path = target.absolutePath,
                title = "${deck.title} mix",
                durationMs = durationMs,
                sourceTrackId = deck.trackId.takeIf { it >= 0 },
            )
        }
        _state.update {
            it.copy(
                recording = false,
                recordProgress = 0f,
                message = if (frames > 0) "Saved a ${durationMs / 1000}s mix" else null,
            )
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // ------------------------------------------------------------- plumbing

    private fun focusedDeck(): DeckState = _state.value.focused

    private fun focusedHandle(): StemPlayerEngine.Deck = engine.decks[_state.value.focus]

    private fun updateFocused(transform: (DeckState) -> DeckState) =
        updateDeck(_state.value.focus, transform)

    private fun updateDeck(index: Int, transform: (DeckState) -> DeckState) =
        _state.update { state ->
            state.copy(
                decks = state.decks.mapIndexed { at, deck ->
                    if (at == index) transform(deck) else deck
                },
            )
        }

    private fun message(text: String) = _state.update { it.copy(message = text) }

    companion object {
        const val DECKS = 2

        fun deckName(index: Int): String = if (index == 0) "A" else "B"

        /** ~60 Hz, so the lights track the beat rather than stepping behind it. */
        private const val LED_POLL_MS = 16L
        /** Refresh clock/diagnostics every Nth LED frame — roughly ten times a second. */
        private const val TRANSPORT_EVERY = 6
        private const val POSITION_POLL_MS = 100L
        private const val LONG_PRESS_MS = 400L
        private const val SKIP_MS = 10_000L
        private const val SCRUB_STEP_MS = 1_500L
        private const val SCRUB_INTERVAL_MS = 90L
        private const val MASTER_STEP = 0.08f
        /** The hardware's default recording length. */
        const val RECORD_SECONDS = 60
        /** Arms 0-1 are the selection strip in both two-axis menus; 2-3 are the other axis. */
        private const val SELECTION_ARMS = 2

        /**
         * Loop lengths offered on the strip, in beats. Index 0 is off.
         *
         * Beats rather than bars all the way down, so a half-bar loop is expressible and the
         * arithmetic never has to assume a time signature it did not measure.
         */
        val LOOP_BEATS = listOf(0, 1, 2, 4, 8, 16, 32, 64)

        /** How close to the centre of the speed arm still counts as exactly 1x. */
        private const val SPEED_DETENT = 0.02f
        private const val SCRUB_TICK_MS = 16L
        /** Past this the cursor jumps instead of chasing. */
        private const val SCRUB_JUMP_MS = 1_200L
        private const val MAX_SCRUB_RATE = 4f

        /**
         * How far a deck may be pulled to match the other.
         *
         * Beyond about a quarter either way the stretcher's grains start repeating or dropping
         * audibly, and two tracks that far apart in tempo do not belong over each other anyway.
         */
        private const val MIN_SYNC_RATIO = 0.75f
        private const val MAX_SYNC_RATIO = 1.33f
        private const val SYNC_TRIM_MS = 2_000L
        /** Fraction of the phase error corrected per trim, and the ceiling on one trim. */
        private const val PHASE_TRIM_GAIN = 0.02
        private const val PHASE_TRIM_MAX = 0.01

        const val AUTO_MIX_BARS = 8
        private const val AUTO_MIX_STEP_MS = 40L
    }
}
