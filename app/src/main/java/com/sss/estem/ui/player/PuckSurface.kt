package com.sss.estem.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import com.sss.estem.design.puck.PuckButton
import com.sss.estem.design.puck.PuckLayout
import com.sss.estem.design.puck.PuckVisualState
import com.sss.estem.design.puck.drawPuck
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The interactive puck.
 *
 * Multi-touch is handled at the raw pointer level rather than with the gesture detectors, because
 * the hardware's vocabulary needs things the detectors will not give: several arms dragged at
 * once, two buttons held together as a chord, and a press-and-hold that has to know it started at
 * an arm's outer end.
 */
@Composable
fun PuckSurface(
    visual: PuckVisualState,
    onBarDrag: (armIndex: Int, delta: Float) -> Unit,
    onIsolateStart: (armIndex: Int) -> Unit,
    onIsolateEnd: (armIndex: Int) -> Unit,
    onButtonDown: (PuckButton) -> Unit,
    onButtonUp: (PuckButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Geometry is derived from the measured size rather than computed inside the draw scope:
    // writing state during draw is what turns a resize into a recomposition loop.
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(measured) {
        if (measured == IntSize.Zero) null
        else PuckLayout.of(Size(measured.width.toFloat(), measured.height.toFloat()))
    }
    val haptics = LocalHapticFeedback.current

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .onSizeChanged { measured = it }
            .pointerInput(Unit) {
                // awaitPointerEventScope is a restricted suspend scope and cannot launch, so the
                // isolate hold-timer needs a real scope captured from outside it.
                coroutineScope {
                    val scope = this
                    val active = mutableMapOf<PointerId, Touch>()

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val current = layout ?: continue

                            for (change in event.changes) {
                                when {
                                    change.changedToDown() -> {
                                        val touch = classify(current, change.position) ?: continue
                                        active[change.id] = touch
                                        change.consume()

                                        when (touch) {
                                            is Touch.Button -> {
                                                haptics.performHapticFeedback(
                                                    HapticFeedbackType.TextHandleMove,
                                                )
                                                onButtonDown(touch.button)
                                            }

                                            // Hold anywhere on an arm to solo that stem for as
                                            // long as the finger stays down. A drag cancels it,
                                            // so the same touch can still ride the volume.
                                            is Touch.Arm -> {
                                                val id = change.id
                                                scope.launch {
                                                    delay(ISOLATE_HOLD_MS)
                                                    val still = active[id]
                                                    if (still === touch && !touch.dragged) {
                                                        haptics.performHapticFeedback(
                                                            HapticFeedbackType.LongPress,
                                                        )
                                                        touch.isolating = true
                                                        onIsolateStart(touch.index)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    change.changedToUp() -> {
                                        val touch = active.remove(change.id) ?: continue
                                        change.consume()
                                        when {
                                            touch is Touch.Button -> onButtonUp(touch.button)
                                            // Releasing the hold drops the solo, restoring
                                            // whatever the sliders were already set to.
                                            touch is Touch.Arm && touch.isolating ->
                                                onIsolateEnd(touch.index)
                                        }
                                    }

                                    change.positionChanged() -> {
                                        val touch = active[change.id]
                                        if (touch is Touch.Arm) {
                                            val arm = current.arms[touch.index]
                                            val projection = arm.projection(change.position)
                                            val delta = projection - touch.lastProjection
                                            if (delta != 0f) {
                                                // Tracked even when the drag is ignored, so that
                                                // letting go of a solo mid-gesture resumes from
                                                // where the finger is rather than jumping.
                                                touch.lastProjection = projection
                                                touch.dragged = true
                                                if (!active.isSoloing()) {
                                                    // Distance along the arm *is* the level, so
                                                    // the delta needs no further scaling.
                                                    onBarDrag(touch.index, delta)
                                                }
                                                change.consume()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
    ) {
        layout?.let { drawPuck(it, visual) }
    }
}

/**
 * True while any finger is soloing a stem.
 *
 * Once a solo is being held the whole face stops being four faders: a second finger can join the
 * solo by holding its own arm, but it cannot move a level. Soloing is a momentary look at the mix
 * and it has to leave the mix exactly as it found it — a stray drag under a held finger would
 * silently rewrite a slider that the release then "restores" to the wrong place.
 */
private fun Map<PointerId, Touch>.isSoloing(): Boolean =
    values.any { it is Touch.Arm && it.isolating }

private sealed interface Touch {
    data class Button(val button: PuckButton) : Touch

    class Arm(
        val index: Int,
        var lastProjection: Float,
        var dragged: Boolean = false,
        /** True while this touch is soloing its stem. Cleared when the finger lifts. */
        var isolating: Boolean = false,
    ) : Touch
}

private fun classify(layout: PuckLayout, position: Offset): Touch? {
    // Buttons win over arms: the centre button sits where all four arms converge.
    layout.buttonAt(position)?.let { return Touch.Button(it) }
    val armIndex = layout.armAt(position) ?: return null
    return Touch.Arm(
        index = armIndex,
        lastProjection = layout.arms[armIndex].projection(position),
    )
}

private const val ISOLATE_HOLD_MS = 280L
