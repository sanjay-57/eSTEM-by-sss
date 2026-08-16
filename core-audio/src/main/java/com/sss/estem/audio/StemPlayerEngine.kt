package com.sss.estem.audio

import com.sss.estem.data.model.Stem
import java.io.File

/**
 * The eight effects, in the order the hardware's effects menu lists them. The ordinal is the
 * value sent across JNI, so it must match `EffectId` in effects.h.
 */
enum class Effect(val displayName: String) {
    REVERB("Reverb"),
    DELAY("Delay"),
    LOW_PASS("Low pass"),
    HIGH_PASS("High pass"),
    BIT_CRUSH("Bitcrush"),
    CHORUS("Chorus"),
    TREMOLO("Fade"),
    STUTTER("Stutter");

    companion object {
        const val COUNT = 8
        const val NONE_ID = -1
    }
}

/**
 * Kotlin face of the native engine.
 *
 * Every method here is safe to call from the main thread: nothing blocks on the audio thread,
 * and the native side takes no locks the render callback could contend on. [Deck.loadTrack] does
 * file I/O, so keep it off the main thread anyway.
 *
 * Two decks, one stream. Everything that belongs to a song — its cursor, sliders, speed, loop —
 * hangs off [a] or [b]; everything that belongs to the machine — the effects rack, master volume,
 * the recorder, the crossfader — lives here.
 */
class StemPlayerEngine : AutoCloseable {

    private var handle: Long = nativeCreate()

    private val closed get() = handle == 0L

    /** Deck A, and the only one anything uses until a second track is cued. */
    val a: Deck = Deck(0)

    /** Deck B: the one you mix into. */
    val b: Deck = Deck(1)

    val decks: List<Deck> = listOf(a, b)

    fun start(): Boolean = if (closed) false else nativeStart(handle)

    fun stop() {
        if (!closed) nativeStop(handle)
    }

    val isRunning: Boolean get() = !closed && nativeIsRunning(handle)

    /**
     * One song and its four stems, addressed independently of the other deck.
     *
     * A handle, not a container: the state lives natively, and this is the set of things that can
     * be said to one deck rather than to the machine as a whole.
     */
    inner class Deck internal constructor(val index: Int) {

        /**
         * @param stemFiles the four stem files, indexed by [Stem.ordinal].
         * @return false if any file is missing or unreadable, leaving the deck unloaded.
         */
        fun loadTrack(stemFiles: List<File>, sampleRate: Int, channelCount: Int): Boolean {
            if (closed) return false
            require(stemFiles.size == Stem.COUNT) {
                "Expected ${Stem.COUNT} stems, got ${stemFiles.size}"
            }
            return nativeLoadTrack(
                handle,
                index,
                stemFiles.map { it.absolutePath }.toTypedArray(),
                sampleRate,
                channelCount,
            )
        }

        fun unloadTrack() {
            if (!closed) nativeUnloadTrack(handle, index)
        }

        // ---- transport ----

        var isPlaying: Boolean
            get() = !closed && nativeIsPlaying(handle, index)
            set(value) {
                if (!closed) nativeSetPlaying(handle, index, value)
            }

        fun seekToFrame(frame: Long) {
            if (!closed) nativeSeek(handle, index, frame)
        }

        fun seekToMillis(millis: Long, sampleRate: Int) =
            seekToFrame(millis * sampleRate / 1000L)

        val positionFrames: Long get() = if (closed) 0 else nativePositionFrames(handle, index)

        val totalFrames: Long get() = if (closed) 0 else nativeTotalFrames(handle, index)

        fun setLooping(looping: Boolean) {
            if (!closed) nativeSetLooping(handle, index, looping)
        }

        /**
         * Cursor speed, where 1 is the track's own. Negative runs it backwards.
         *
         * On its own this repitches, like a turntable — which is exactly right for a scrub, and is
         * why dragging the bar can now sound like dragging a record instead of like sixty seeks a
         * second. Ramped natively, so it is safe to write on every touch move.
         */
        var rate: Float
            get() = if (closed) 1f else nativeGetRate(handle, index)
            set(value) {
                if (!closed) nativeSetRate(handle, index, value)
            }

        /**
         * The region [setLooping] loops between. Pass (-1, -1) for the whole track.
         *
         * Frames rather than milliseconds because loop points are snapped to a beat grid that is
         * itself measured in frames, and rounding a bar boundary through milliseconds and back is
         * what makes a loop drift audibly over a minute.
         */
        fun setLoopRegion(startFrame: Long, endFrame: Long) {
            if (!closed) nativeSetLoopRegion(handle, index, startFrame, endFrame)
        }

        /**
         * Whether [rate] moves tempo alone (true) or pitch with it (false).
         *
         * True reads through a WSOLA time-stretcher, so this deck can be pulled to the other's
         * tempo without its vocal sliding up. False is a turntable, which is what a scrub wants.
         *
         * Ignored while [rate] is negative — reverse is always a turntable.
         */
        var keepPitch: Boolean
            get() = !closed && nativeKeepPitch(handle, index)
            set(value) {
                if (!closed) nativeSetKeepPitch(handle, index, value)
            }

        // ---- stem mixing ----

        /** Slider position for one stem, 0f..1f. Ramped natively; safe to call on every touch move. */
        fun setStemGain(stem: Stem, gain: Float) {
            if (!closed) nativeSetStemGain(handle, index, stem.ordinal, gain)
        }

        fun stemGain(stem: Stem): Float =
            if (closed) 0f else nativeGetStemGain(handle, index, stem.ordinal)

        /**
         * Non-empty set means only those stems are audible — the hardware's press-and-hold-outer-edge
         * state, which is separate from the slider positions and restores them when released.
         */
        fun setIsolated(stems: Set<Stem>) {
            if (!closed) nativeSetIsolateMask(handle, index, stems.toMask())
        }

        fun setMuted(stems: Set<Stem>) {
            if (!closed) nativeSetMuteMask(handle, index, stems.toMask())
        }

        /**
         * Live per-stem signal magnitude, 0f..1f, indexed by [Stem.ordinal] — what the LEDs pulse
         * with. Measured before the slider gain, so a stem turned right down still reads its own
         * material.
         *
         * @param into a reusable array of at least [Stem.COUNT]; filled in place so a UI polling at
         * 60 Hz does not allocate on every frame.
         */
        fun readStemEnergies(into: FloatArray) {
            if (closed || into.size < Stem.COUNT) return
            nativeStemEnergies(handle, index, into)
        }
    }

    // ---- the machine, rather than either song ----

    fun setMasterVolume(volume: Float) {
        if (!closed) nativeSetMasterVolume(handle, volume)
    }

    /**
     * 0 is all of deck [a], 1 is all of deck [b].
     *
     * Equal-power natively: two different songs are uncorrelated, so a linear fade dips audibly
     * through the middle where their powers, not their amplitudes, are what add.
     */
    var crossfade: Float
        get() = if (closed) 0f else nativeCrossfade(handle)
        set(value) {
            if (!closed) nativeSetCrossfade(handle, value)
        }

    // ---- effects ----

    /** Pass null to disengage the rack entirely. */
    fun setEffect(effect: Effect?) {
        if (!closed) nativeSetEffect(handle, effect?.ordinal ?: Effect.NONE_ID)
    }

    /** The horizontal axis of the hardware's effects menu: none to maximum. */
    fun setEffectIntensity(intensity: Float) {
        if (!closed) nativeSetEffectIntensity(handle, intensity)
    }

    fun clearEffects() {
        if (!closed) nativeClearEffects(handle)
    }

    // ---- recording ----

    /** @param maxFrames 0 for unlimited; the hardware's default is 60 s. */
    fun startRecording(file: File, maxFrames: Long): Boolean =
        !closed && nativeStartRecording(handle, file.absolutePath, maxFrames)

    fun stopRecording() {
        if (!closed) nativeStopRecording(handle)
    }

    val isRecording: Boolean get() = !closed && nativeIsRecording(handle)

    val recordedFrames: Long get() = if (closed) 0 else nativeRecordedFrames(handle)

    // ---- diagnostics ----

    val streamSampleRate: Int get() = if (closed) 0 else nativeStreamSampleRate(handle)

    /** Underruns since the stream opened. Non-zero means the render callback is missing deadlines. */
    val xRunCount: Int get() = if (closed) 0 else nativeXRunCount(handle)

    /**
     * Output buffer size in frames. Opens at two bursts and grows itself if the device cannot hold
     * that deadline, so watching this climb is watching latency being traded for glitch-free audio.
     */
    val bufferFrames: Int get() = if (closed) 0 else nativeBufferFrames(handle)

    override fun close() {
        if (closed) return
        nativeStop(handle)
        nativeDestroy(handle)
        handle = 0L
    }

    private fun Set<Stem>.toMask(): Int =
        fold(0) { mask, stem -> mask or (1 shl stem.ordinal) }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeLoadTrack(
        handle: Long,
        deck: Int,
        paths: Array<String>,
        sampleRate: Int,
        channelCount: Int,
    ): Boolean

    private external fun nativeUnloadTrack(handle: Long, deck: Int)
    private external fun nativeSetPlaying(handle: Long, deck: Int, playing: Boolean)
    private external fun nativeIsPlaying(handle: Long, deck: Int): Boolean
    private external fun nativeSeek(handle: Long, deck: Int, frame: Long)
    private external fun nativePositionFrames(handle: Long, deck: Int): Long
    private external fun nativeTotalFrames(handle: Long, deck: Int): Long
    private external fun nativeSetLooping(handle: Long, deck: Int, looping: Boolean)
    private external fun nativeSetRate(handle: Long, deck: Int, rate: Float)
    private external fun nativeGetRate(handle: Long, deck: Int): Float
    private external fun nativeSetLoopRegion(
        handle: Long,
        deck: Int,
        startFrame: Long,
        endFrame: Long,
    )

    private external fun nativeSetKeepPitch(handle: Long, deck: Int, keep: Boolean)
    private external fun nativeKeepPitch(handle: Long, deck: Int): Boolean
    private external fun nativeSetStemGain(handle: Long, deck: Int, stem: Int, gain: Float)
    private external fun nativeGetStemGain(handle: Long, deck: Int, stem: Int): Float
    private external fun nativeSetIsolateMask(handle: Long, deck: Int, mask: Int)
    private external fun nativeStemEnergies(handle: Long, deck: Int, into: FloatArray)
    private external fun nativeSetMuteMask(handle: Long, deck: Int, mask: Int)
    private external fun nativeSetMasterVolume(handle: Long, volume: Float)
    private external fun nativeSetCrossfade(handle: Long, position: Float)
    private external fun nativeCrossfade(handle: Long): Float
    private external fun nativeSetEffect(handle: Long, effectId: Int)
    private external fun nativeSetEffectIntensity(handle: Long, intensity: Float)
    private external fun nativeClearEffects(handle: Long)
    private external fun nativeStartRecording(handle: Long, path: String, maxFrames: Long): Boolean
    private external fun nativeStopRecording(handle: Long)
    private external fun nativeIsRecording(handle: Long): Boolean
    private external fun nativeRecordedFrames(handle: Long): Long
    private external fun nativeStreamSampleRate(handle: Long): Int
    private external fun nativeXRunCount(handle: Long): Int
    private external fun nativeBufferFrames(handle: Long): Int

    companion object {
        init {
            System.loadLibrary("estem_audio")
        }
    }
}
