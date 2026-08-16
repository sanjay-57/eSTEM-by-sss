package com.sss.estem.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import com.sss.estem.data.model.Stem
import com.sss.estem.design.EstemColors
import com.sss.estem.design.LedPalette
import com.sss.estem.design.puck.PuckVisualState
import com.sss.estem.design.puck.TwoAxisState
import com.sss.estem.player.PlayerController
import com.sss.estem.player.PlayerState
import com.sss.estem.player.PuckMode
import com.sss.estem.ui.player.PuckSurface

/**
 * The front face, and almost nothing else. The puck is the page — the only chrome is what the
 * device itself cannot show: which track is loaded and where it is up to.
 */
@Composable
fun FrontPage(player: PlayerController) {
    val state by player.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(state)
            if (state.focused.loaded) {
                Spacer(Modifier.height(10.dp))
                SeekBar(
                    state = state,
                    onScrubStart = player::beginScrub,
                    onScrubMove = player::updateScrub,
                    onScrubEnd = player::endScrub,
                )
            }
            Spacer(Modifier.height(18.dp))

            PuckSurface(
                visual = state.toVisualState(),
                onBarDrag = player::onBarDrag,
                onIsolateStart = player::onIsolateStart,
                onIsolateEnd = player::onIsolateEnd,
                onButtonDown = player::onButtonDown,
                onButtonUp = player::onButtonUp,
            )

            Spacer(Modifier.height(14.dp))
            StemLegend(state)
            Spacer(Modifier.height(10.dp))
            HintLine(state)

            // Only once there is something to mix *with*. One track loaded is a stem player, and a
            // crossfader with nothing on the other side of it is a control that can only do harm.
            if (state.other.loaded) {
                Spacer(Modifier.height(14.dp))
                MixStrip(
                    state = state,
                    onFocus = player::setFocus,
                    onCrossfade = player::setCrossfade,
                    onSync = { if (state.synced) player.stopSync() else player.sync() },
                    onAutoMix = player::autoMix,
                )
            }
        }
    }
}

@Composable
private fun Header(state: PlayerState) {
    val deck = state.focused
    if (!deck.loaded) {
        Text(
            text = "No track loaded",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Swipe left twice to pick one",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        text = deck.title,
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    deck.artist?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (state.recording) {
        Text(
            text = "● REC  ${(state.recordProgress * PlayerController.RECORD_SECONDS).toInt()}s",
            style = MaterialTheme.typography.labelMedium,
            color = LedPalette.LedRecording,
        )
    }
}

/**
 * Where the track is, and the only way to move it now that prev and next have gone off the face.
 *
 * **This scrubs.** It used to wait for the finger to lift, because seeking on every touch move is
 * sixty step discontinuities a second and that is a buzz, not a scrub. Now the deck can read at a
 * fractional rate, so the drag steers the cursor's *speed* instead of teleporting it: the audio
 * chases the finger, pitching up and down with it and running backwards when the finger does.
 *
 * The handle still follows the finger rather than the engine, because the engine's position is
 * polled and would otherwise drag the handle backwards between frames.
 */
@Composable
private fun SeekBar(
    state: PlayerState,
    onScrubStart: (Long) -> Unit,
    onScrubMove: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
) {
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val duration = state.focused.durationMs.coerceAtLeast(1L)
    val shown = scrubMs ?: state.focused.positionMs
    val fraction = (shown.toFloat() / duration).coerceIn(0f, 1f)
    val haptics = LocalHapticFeedback.current

    // Position is polled every 100 ms, so for up to that long after a seek the engine is still
    // reporting the old spot. Holding the scrubbed value until it agrees stops the handle
    // snapping backwards and then jumping forward again the moment the finger lifts.
    LaunchedEffect(state.focused.positionMs, dragging) {
        val target = scrubMs
        if (!dragging && target != null &&
            abs(state.focused.positionMs - target) < SEEK_SETTLE_MS
        ) {
            scrubMs = null
        }
    }

    Column(Modifier.fillMaxWidth(0.86f)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(duration) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown()
                            // The knob is not a target you have to hit — anywhere on the bar takes
                            // the playhead there, which is the only thing that works one-handed.
                            fun msAt(x: Float): Long =
                                ((x / size.width).coerceIn(0f, 1f) * duration).toLong()

                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            dragging = true
                            val from = msAt(down.position.x)
                            scrubMs = from
                            onScrubStart(from)
                            down.consume()

                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Main)
                                    .changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUp()) {
                                    change.consume()
                                    break
                                }
                                val at = msAt(change.position.x)
                                scrubMs = at
                                onScrubMove(at)
                                change.consume()
                            }

                            onScrubEnd(scrubMs ?: from)
                            dragging = false
                        }
                    }
                },
        ) {
            val cy = size.height / 2f
            val knob = 7.dp.toPx()
            val track = 5.dp.toPx()
            val left = knob
            val right = size.width - knob
            val x = left + (right - left) * fraction

            drawLine(
                color = EstemColors.ShellShadow.copy(alpha = 0.55f),
                start = Offset(left, cy),
                end = Offset(right, cy),
                strokeWidth = track,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = LedPalette.LedOuter,
                start = Offset(left, cy),
                end = Offset(x, cy),
                strokeWidth = track,
                cap = StrokeCap.Round,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(LedPalette.LedOuter.copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(x, cy),
                    radius = knob * 2.4f,
                ),
                radius = knob * 2.4f,
                center = Offset(x, cy),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        EstemColors.ShellHighlight,
                        EstemColors.Shell,
                        EstemColors.ShellShadow,
                    ),
                    center = Offset(x - knob * 0.35f, cy - knob * 0.40f),
                    radius = knob * 1.8f,
                ),
                radius = knob,
                center = Offset(x, cy),
            )
            drawCircle(LedPalette.LedOuter, radius = knob * 0.34f, center = Offset(x, cy))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(shown),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatTime(state.focused.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StemLegend(state: PlayerState) {
    Row(
        modifier = Modifier.fillMaxWidth(0.86f),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Stem.ORDER.forEachIndexed { index, stem ->
            val isolated = index in state.focused.isolated
            Text(
                text = stem.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = if (isolated) LedPalette.LedIsolated
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The two-deck controls: which deck the face is driving, the crossfader between them, and the two
 * ways of getting from one track to the other.
 *
 * Deliberately *below* the puck and only present when a second track is loaded. The object is the
 * page; this is the mixer the object is plugged into, and it should read as an addition to the
 * device rather than as part of its face — the hardware has four arms and one button, not a
 * crossfader.
 */
@Composable
private fun MixStrip(
    state: PlayerState,
    onFocus: (Int) -> Unit,
    onCrossfade: (Float) -> Unit,
    onSync: () -> Unit,
    onAutoMix: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(0.86f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.decks.forEachIndexed { index, deck ->
                val focused = index == state.focus
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = deck.loaded) { onFocus(index) },
                    horizontalAlignment = if (index == 0) Alignment.Start else Alignment.End,
                ) {
                    Text(
                        text = PlayerController.deckName(index) +
                            if (deck.bpm > 0f) "  ${deck.effectiveBpm.toInt()}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        // The focused deck is the one the arms move, so it is the one lit.
                        color = if (focused) LedPalette.LedOuter
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (deck.loaded) deck.title else "empty",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Crossfader(position = state.crossfade, onChange = onCrossfade)
        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MixButton(
                label = if (state.synced) "SYNCED" else "SYNC",
                active = state.synced,
                onClick = onSync,
            )
            MixButton(
                label = if (state.autoMixing) "CANCEL" else "AUTO ${PlayerController.AUTO_MIX_BARS}",
                active = state.autoMixing,
                onClick = { onAutoMix(PlayerController.AUTO_MIX_BARS) },
            )
        }
    }
}

@Composable
private fun MixButton(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (active) LedPalette.LedSync else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Drag anywhere on it. Same reasoning as the seek bar: a knob you have to hit is not usable with
 * one thumb, and this is the control most likely to be moved in a hurry.
 */
@Composable
private fun Crossfader(position: Float, onChange: (Float) -> Unit) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                        onChange(at(down.position.x))
                        down.consume()
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Main)
                                .changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUp()) {
                                change.consume()
                                break
                            }
                            onChange(at(change.position.x))
                            change.consume()
                        }
                    }
                }
            },
    ) {
        val cy = size.height / 2f
        val knob = 8.dp.toPx()
        val left = knob
        val right = size.width - knob
        val x = left + (right - left) * position.coerceIn(0f, 1f)

        drawLine(
            color = EstemColors.ShellShadow.copy(alpha = 0.55f),
            start = Offset(left, cy),
            end = Offset(right, cy),
            strokeWidth = 5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        // A centre notch, because the middle of a crossfader is a place you aim for.
        drawLine(
            color = EstemColors.ShellShadow,
            start = Offset(size.width / 2f, cy - 7.dp.toPx()),
            end = Offset(size.width / 2f, cy + 7.dp.toPx()),
            strokeWidth = 1.5.dp.toPx(),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    EstemColors.ShellHighlight,
                    EstemColors.Shell,
                    EstemColors.ShellShadow,
                ),
                center = Offset(x - knob * 0.35f, cy - knob * 0.40f),
                radius = knob * 1.8f,
            ),
            radius = knob,
            center = Offset(x, cy),
        )
    }
}

@Composable
private fun HintLine(state: PlayerState) {
    val text = when {
        state.locked -> "Locked — centre + volume-down to unlock"
        state.mode == PuckMode.EFFECTS ->
            "${state.effect?.displayName ?: "—"} · ${(state.effectIntensity * 100).toInt()}%  " +
                "· tap centre to exit"
        state.mode == PuckMode.LOOPS -> buildString {
            val deck = state.focused
            append(if (deck.loopBeats > 0) "Loop ${deck.loopBeats} beat" else "Loop off")
            if (deck.loopBeats > 1) append("s")
            append(" · ${"%.2f".format(deck.speed)}x")
            append(if (state.keepPitch) " (tempo)" else " (pitch)")
            if (deck.bpm > 0f) append(" · ${deck.bpm.toInt()} bpm")
        }
        state.scrubbing -> "Scrubbing"
        state.synced -> "Synced at ${state.focused.effectiveBpm.toInt()} bpm"
        state.focused.isolated.isNotEmpty() -> "Soloing — let go to bring the rest back"
        else -> "Hold an arm to solo it · volume-up + centre for effects · hold volume-up to record"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

internal fun PlayerState.toVisualState(): PuckVisualState = PuckVisualState(
    // Dark until something is actually loaded. Showing four full slots with no track reads as
    // "playing at full volume", which is the opposite of what is happening.
    stemLevels = if (focused.loaded) focused.stemLevels else List(Stem.COUNT) { 0f },
    stemEnergies = focused.stemEnergies,
    isolatedStems = focused.isolated,
    mutedStems = focused.muted,
    locked = locked,
    recording = recording,
    playing = focused.playing,
    synced = synced,
    twoAxis = when (mode) {
        PuckMode.EFFECTS -> TwoAxisState(effect?.ordinal ?: 0, effectIntensity)
        PuckMode.LOOPS -> TwoAxisState(
            selection = PlayerController.LOOP_BEATS.indexOf(focused.loopBeats).coerceAtLeast(0),
            intensity = focused.speedAxis,
        )
        PuckMode.MAIN -> null
    },
)

/** Close enough that the engine has clearly taken the seek, and one poll is 100 ms. */
private const val SEEK_SETTLE_MS = 450L

internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
