package com.sss.estem.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sss.estem.AppContainer
import androidx.compose.foundation.gestures.awaitFirstDown
import com.sss.estem.design.LedPalette
import com.sss.estem.design.puck.PuckBackState
import com.sss.estem.design.puck.PuckLayout
import com.sss.estem.design.puck.VolumeRing
import com.sss.estem.design.puck.drawPuckBack
import com.sss.estem.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The back of the device: speaker grille, the volume ring around it, and the two ports moulded
 * into the lower rim.
 *
 * The ring around the grille is both the volume readout and the way to set it — the phone's
 * rocker is still the puck's rocker, but the ring is drawn on a touchscreen, so it is drawn as
 * something you can grab. What this page adds beyond the object is the handful of facts the
 * hardware keeps out of sight: what the audio stream is actually doing and how much disk the
 * stem cache is using.
 */
@Composable
fun BackPage(player: PlayerController, container: AppContainer) {
    val state by player.state.collectAsStateWithLifecycle()

    val cacheBytes by produceState(initialValue = -1L, state.focused.trackId) {
        value = withContext(Dispatchers.IO) { container.library.stemCacheBytes() }
    }

    var measured by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(measured) {
        if (measured == IntSize.Zero) null
        else PuckLayout.of(Size(measured.width.toFloat(), measured.height.toFloat()))
    }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "eSTEM",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "four stems · one cursor",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .onSizeChanged { measured = it }
                    .volumeRingDrag(layout, player::setMaster),
            ) {
                layout?.let {
                    drawPuckBack(
                        it,
                        PuckBackState(
                            masterVolume = state.master,
                            playing = state.focused.playing,
                            recording = state.recording,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Volume ${(state.master * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = "Drag the ring, or use the phone's volume keys",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))
            SpecPlate(
                sampleRate = state.streamSampleRate,
                xRuns = state.xRuns,
                bufferFrames = state.bufferFrames,
                cacheBytes = cacheBytes,
                keepPitch = state.keepPitch,
                onKeepPitch = player::setKeepPitch,
            )
        }
    }
}

/**
 * Makes the volume ring draggable.
 *
 * Written against raw pointer events rather than a drag detector for two reasons. A touch on the
 * track has to take effect immediately, not after the drag slop is crossed — this is a fader, and
 * a fader that ignores the first few millimetres feels broken. And once the finger is down it must
 * keep steering by angle even after it wanders off the band, which is what a finger on a circular
 * control always does.
 *
 * A touch that misses the ring is left entirely alone, so the horizontal swipe between pages still
 * works everywhere else on the face.
 */
private fun Modifier.volumeRingDrag(
    layout: PuckLayout?,
    onVolume: (Float) -> Unit,
): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    pointerInput(layout) {
        val current = layout ?: return@pointerInput
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!VolumeRing.contains(current, down.position)) continue

                down.consume()
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onVolume(VolumeRing.volumeAt(current, down.position))

                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Main)
                        .changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUp()) {
                        change.consume()
                        break
                    }
                    onVolume(VolumeRing.volumeAt(current, change.position))
                    change.consume()
                }
            }
        }
    }
}

/**
 * The moulded spec text a device has printed on its underside — except these values are live.
 * [xRuns] is the one that matters: anything above zero means the render callback missed a
 * deadline and the audio actually glitched.
 */
@Composable
private fun SpecPlate(
    sampleRate: Int,
    xRuns: Int,
    bufferFrames: Int,
    cacheBytes: Long,
    keepPitch: Boolean,
    onKeepPitch: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(0.82f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SpecRow("OUTPUT", if (sampleRate > 0) "$sampleRate Hz · 2 ch · float" else "idle")
        SpecRow("UNDERRUNS", xRuns.toString())
        // Shown next to the underruns because it is the answer to them: the buffer opens as small
        // as the device claims to allow and gives ground a burst at a time when that turns out to
        // be optimistic, so these two numbers only make sense read together.
        SpecRow(
            "BUFFER",
            if (bufferFrames > 0 && sampleRate > 0) {
                "$bufferFrames fr · %.1f ms".format(bufferFrames * 1000f / sampleRate)
            } else {
                "—"
            },
        )
        SpecRow(
            "STEM CACHE",
            when {
                cacheBytes < 0 -> "…"
                cacheBytes >= 1_000_000_000 -> "%.2f GB".format(cacheBytes / 1_000_000_000.0)
                cacheBytes >= 1_000_000 -> "%.0f MB".format(cacheBytes / 1_000_000.0)
                else -> "%.0f KB".format(cacheBytes / 1_000.0)
            },
        )
        // What the speed control does to pitch is a property of the machine, not of a track, so it
        // belongs on the plate with the rest of what the machine is doing rather than in a menu
        // you have to be holding a gesture to see.
        SpecRow(
            label = "SPEED CHANGES",
            value = if (keepPitch) "tempo only" else "tempo + pitch",
            onClick = { onKeepPitch(!keepPitch) },
        )
    }
}

@Composable
private fun SpecRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            // The one row that is a control rather than a readout says so by being the only one
            // in the LED colour; a tappable line that looks exactly like three static ones is
            // not discoverable at all.
            color = if (onClick != null) LedPalette.LedOuter else MaterialTheme.colorScheme.onSurface,
        )
    }
}
