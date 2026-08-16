package com.sss.estem.design.puck

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.sss.estem.design.EstemColors
import com.sss.estem.design.LedPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the back face shows about the device's current state. */
data class PuckBackState(
    /** Drives the ring of light around the grille. */
    val masterVolume: Float = 0.8f,
    val playing: Boolean = false,
    val recording: Boolean = false,
    /** Lights the Bluetooth glyph, as pairing mode does on the hardware. */
    val bluetooth: Boolean = false,
    val headphonesConnected: Boolean = false,
)

/**
 * Draws the back of the puck: a perforated speaker grille ringed by a volume indicator, with the
 * USB-C slot and 3.5 mm jack moulded into the lower rim.
 *
 * The grille is a real polar lattice rather than a texture — rings of holes at a roughly constant
 * spacing — because an evenly-spaced grid on a circle is the detail that makes a drawn speaker
 * look drawn.
 */
fun DrawScope.drawPuckBack(layout: PuckLayout, state: PuckBackState) {
    drawContactShadow(layout)
    drawShell(layout)
    drawVolumeRing(layout, state)
    drawGrille(layout, state)
    drawPorts(layout, state)
    drawRim(layout)
}

/**
 * The volume ring's geometry, in one place because it is both drawn and dragged.
 *
 * A control whose hit test is derived separately from its drawing is a control that will one day
 * respond a few degrees away from where it looks — so the arc, the band and the angle-to-value
 * mapping all live here and both sides read them.
 */
object VolumeRing {

    /** Starts lower-left and sweeps clockwise, leaving a gap at the bottom for the ports. */
    const val START_ANGLE = 130f
    const val SWEEP = 280f

    private const val RADIUS_FRACTION = 0.68f
    private const val STROKE_FRACTION = 0.030f

    /** Generously wider than the stroke — this is a fingertip on glass, not a mouse. */
    private const val TOUCH_BAND_FRACTION = 0.115f

    fun radius(layout: PuckLayout): Float = layout.radius * RADIUS_FRACTION

    fun stroke(layout: PuckLayout): Float = layout.radius * STROKE_FRACTION

    /** True when a touch lands close enough to the ring to be meant for it. */
    fun contains(layout: PuckLayout, position: Offset): Boolean {
        val offset = position - layout.center
        val distance = hypot(offset.x.toDouble(), offset.y.toDouble()).toFloat()
        if (abs(distance - radius(layout)) > layout.radius * TOUCH_BAND_FRACTION) return false
        // Inside the gap there is no track to grab, so a touch there belongs to the pager.
        return sweptDegrees(offset) != null
    }

    /**
     * The value a touch selects. Once a drag has started the finger is allowed to wander off the
     * band and even across the gap — it keeps steering by angle, and the gap resolves to whichever
     * end it is nearer. Letting go of the value because the finger drifted is the single most
     * annoying thing a radial control can do.
     */
    fun volumeAt(layout: PuckLayout, position: Offset): Float {
        val swept = (angleOf(position - layout.center) - START_ANGLE + 360f) % 360f
        if (swept <= SWEEP) return swept / SWEEP
        // In the gap at the bottom: snap to whichever end of the track the finger is nearer.
        return if (swept - SWEEP < (360f - SWEEP) / 2f) 1f else 0f
    }

    /** Where on screen the ring sits at [fraction] of its travel. */
    fun positionAt(layout: PuckLayout, fraction: Float): Offset {
        val angle = (START_ANGLE + SWEEP * fraction.coerceIn(0f, 1f)) * PI.toFloat() / 180f
        return Offset(
            layout.center.x + radius(layout) * cos(angle),
            layout.center.y + radius(layout) * sin(angle),
        )
    }

    /** Degrees travelled along the track, or null when the angle falls in the gap. */
    private fun sweptDegrees(offset: Offset): Float? {
        val swept = (angleOf(offset) - START_ANGLE + 360f) % 360f
        return if (swept <= SWEEP) swept else null
    }

    private fun angleOf(offset: Offset): Float {
        val degrees = atan2(offset.y.toDouble(), offset.x.toDouble()) * 180.0 / PI
        return ((degrees + 360.0) % 360.0).toFloat()
    }
}

private fun DrawScope.drawGrille(layout: PuckLayout, state: PuckBackState) {
    val grilleRadius = layout.radius * 0.54f
    val holeRadius = layout.radius * 0.016f
    val ringSpacing = holeRadius * 3.1f

    // Recessed disc the holes are punched into.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(EstemColors.ShellShadow.copy(alpha = 0.45f), EstemColors.Shell),
            center = layout.center,
            radius = grilleRadius * 1.3f,
        ),
        radius = grilleRadius,
        center = layout.center,
    )
    drawCircle(
        color = EstemColors.ShellShadow.copy(alpha = 0.55f),
        radius = grilleRadius,
        center = layout.center,
        style = Stroke(width = layout.radius * 0.008f),
    )

    val holeColor = EstemColors.Stage.copy(alpha = 0.42f)
    drawCircle(holeColor, radius = holeRadius, center = layout.center)

    var ringRadius = ringSpacing
    while (ringRadius <= grilleRadius - holeRadius * 2.4f) {
        // Constant arc spacing means outer rings get more holes, which is what a real grille does.
        val count = ((2 * PI * ringRadius) / ringSpacing).roundToInt().coerceAtLeast(6)
        val phase = ringRadius * 0.9f
        for (i in 0 until count) {
            val angle = (2 * PI * i / count) + phase
            val position = Offset(
                layout.center.x + (ringRadius * cos(angle)).toFloat(),
                layout.center.y + (ringRadius * sin(angle)).toFloat(),
            )
            drawCircle(holeColor, radius = holeRadius, center = position)
        }
        ringRadius += ringSpacing
    }

    if (state.playing) {
        // A faint warmth through the grille while sound is actually coming out of it.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(LedPalette.LedOuter.copy(alpha = 0.14f), Color.Transparent),
                center = layout.center,
                radius = grilleRadius,
            ),
            radius = grilleRadius,
            center = layout.center,
        )
    }
}

/**
 * A ring of light around the grille showing master volume — the back's only readout, and its only
 * control: it is dragged, so it is drawn as something with a handle rather than as a bar.
 */
private fun DrawScope.drawVolumeRing(layout: PuckLayout, state: PuckBackState) {
    val ringRadius = VolumeRing.radius(layout)
    val stroke = VolumeRing.stroke(layout)
    val topLeft = Offset(layout.center.x - ringRadius, layout.center.y - ringRadius)
    val size = androidx.compose.ui.geometry.Size(ringRadius * 2, ringRadius * 2)
    val volume = state.masterVolume.coerceIn(0f, 1f)
    val lit = if (state.recording) LedPalette.LedRecording else LedPalette.LedOuter

    // The track is a channel cut into the shell: a dark core with a lit lower lip, so it reads as
    // recessed and the light in it reads as sitting *in* something.
    drawArc(
        color = EstemColors.ShellHighlight.copy(alpha = 0.55f),
        startAngle = VolumeRing.START_ANGLE,
        sweepAngle = VolumeRing.SWEEP,
        useCenter = false,
        topLeft = Offset(topLeft.x, topLeft.y + stroke * 0.34f),
        size = size,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    drawArc(
        color = EstemColors.ShellTerminator.copy(alpha = 0.55f),
        startAngle = VolumeRing.START_ANGLE,
        sweepAngle = VolumeRing.SWEEP,
        useCenter = false,
        topLeft = topLeft,
        size = size,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )

    val filled = VolumeRing.SWEEP * volume
    if (filled > 0.5f) {
        drawArc(
            color = lit.copy(alpha = 0.30f),
            startAngle = VolumeRing.START_ANGLE,
            sweepAngle = filled,
            useCenter = false,
            topLeft = topLeft,
            size = size,
            style = Stroke(width = stroke * 2.1f, cap = StrokeCap.Round),
        )
        drawArc(
            color = lit,
            startAngle = VolumeRing.START_ANGLE,
            sweepAngle = filled,
            useCenter = false,
            topLeft = topLeft,
            size = size,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }

    drawKnob(VolumeRing.positionAt(layout, volume), stroke, lit)
}

/** The handle. Without one the ring looks like a readout, and nobody tries to drag a readout. */
private fun DrawScope.drawKnob(position: Offset, stroke: Float, lit: Color) {
    val r = stroke * 1.5f

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(lit.copy(alpha = 0.45f), Color.Transparent),
            center = position,
            radius = r * 2.6f,
        ),
        radius = r * 2.6f,
        center = position,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(EstemColors.ShellHighlight, EstemColors.Shell, EstemColors.ShellShadow),
            center = position - Offset(r * 0.35f, r * 0.40f),
            radius = r * 1.8f,
        ),
        radius = r,
        center = position,
    )
    drawCircle(
        color = EstemColors.ShellTerminator.copy(alpha = 0.45f),
        radius = r,
        center = position,
        style = Stroke(width = r * 0.16f),
    )
    drawCircle(color = lit, radius = r * 0.34f, center = position)
}

/** USB-C slot and 3.5 mm jack, moulded into the lower rim as on the hardware. */
private fun DrawScope.drawPorts(layout: PuckLayout, state: PuckBackState) {
    val y = layout.center.y + layout.radius * 0.78f

    val usbWidth = layout.radius * 0.20f
    val usbHeight = layout.radius * 0.055f
    val usbRect = Rect(
        layout.center.x - layout.radius * 0.20f - usbWidth / 2f,
        y - usbHeight / 2f,
        layout.center.x - layout.radius * 0.20f + usbWidth / 2f,
        y + usbHeight / 2f,
    )
    drawPath(
        Path().apply {
            addRoundRect(RoundRect(usbRect, CornerRadius(usbHeight / 2f, usbHeight / 2f)))
        },
        EstemColors.Stage.copy(alpha = 0.55f),
    )

    val jackRadius = layout.radius * 0.045f
    val jackCenter = Offset(layout.center.x + layout.radius * 0.20f, y)
    drawCircle(EstemColors.Stage.copy(alpha = 0.55f), radius = jackRadius, center = jackCenter)
    drawCircle(
        color = if (state.headphonesConnected) LedPalette.LedIsolated else EstemColors.ShellShadow,
        radius = jackRadius * 0.45f,
        center = jackCenter,
    )

    if (state.bluetooth) {
        drawCircle(
            color = LedPalette.LedSync,
            radius = layout.radius * 0.022f,
            center = Offset(layout.center.x, y),
        )
    }
}
