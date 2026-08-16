package com.sss.estem.design.puck

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The buttons. VOL_UP/VOL_DOWN exist but are driven by the phone's own volume rocker. */
enum class PuckButton { POWER, CENTER, PREV, NEXT, VOL_UP, VOL_DOWN }

/**
 * One stem's slider: a line of LEDs running outward from the centre of the disc.
 *
 * This radial arrangement is what the hardware actually has, and it is why the manual describes
 * volume as sliding "away from the centre" — distance from the middle *is* the level. Four
 * parallel bars cannot express that; four arms can.
 */
data class PuckArm(
    val index: Int,
    val origin: Offset,
    val angleRad: Float,
    val innerRadius: Float,
    val outerRadius: Float,
) {
    val direction: Offset = Offset(cos(angleRad), sin(angleRad))
    val start: Offset = origin + direction * innerRadius
    val end: Offset = origin + direction * outerRadius
    val length: Float = outerRadius - innerRadius

    /** Centre of LED [i], counting outward from the middle of the disc. */
    fun ledCenter(i: Int): Offset {
        val t = (i + 0.5f) / PuckLayout.LED_COUNT
        return origin + direction * (innerRadius + length * t)
    }

    /** How far along the arm a point falls, 0 at the inner end and 1 at the outer end. */
    fun projection(point: Offset): Float {
        val relative = point - origin
        val along = relative.x * direction.x + relative.y * direction.y
        return ((along - innerRadius) / length).coerceIn(-0.5f, 1.5f)
    }

    /** Perpendicular distance from the arm's axis. */
    fun offAxisDistance(point: Offset): Float {
        val relative = point - origin
        val along = relative.x * direction.x + relative.y * direction.y
        val projected = origin + direction * along
        return hypot(point.x - projected.x, point.y - projected.y)
    }
}

private operator fun Offset.times(scale: Float) = Offset(x * scale, y * scale)

/**
 * Resolved pixel geometry for one puck. Plain data with no Compose state, so the same numbers
 * drive drawing and hit-testing — a hit test that disagrees with what was drawn is how gesture
 * bugs happen.
 */
data class PuckLayout(
    val center: Offset,
    val radius: Float,
    /** Four arms in [com.sss.estem.data.model.Stem] order, clockwise from the upper left. */
    val arms: List<PuckArm>,
    val buttons: Map<PuckButton, Rect>,
) {

    /** Nearest arm to a touch, or null if the touch is not near any of them. */
    fun armAt(position: Offset): Int? {
        val tolerance = radius * ARM_TOUCH_TOLERANCE
        return arms
            .filter { arm ->
                val t = arm.projection(position)
                t in -0.25f..1.25f && arm.offAxisDistance(position) <= tolerance
            }
            .minByOrNull { it.offAxisDistance(position) }
            ?.index
    }

    fun buttonAt(position: Offset): PuckButton? =
        buttons.entries.firstOrNull { it.value.contains(position) }?.key

    /**
     * True at the far end of an arm. Press-and-hold there isolates that stem, matching the
     * hardware's press-and-hold on a slider's outer edge.
     */
    fun isOuterEnd(armIndex: Int, position: Offset): Boolean {
        val arm = arms.getOrNull(armIndex) ?: return false
        return arm.projection(position) >= OUTER_END_THRESHOLD
    }

    companion object {
        /**
         * LEDs per arm. Few and large, as on the device — a long run of small dots reads as a
         * progress bar rather than as discrete lights under the shell.
         */
        const val LED_COUNT = 5

        private const val ARM_TOUCH_TOLERANCE = 0.17f
        private const val OUTER_END_THRESHOLD = 0.76f

        /**
         * The arms form a cross — up, right, down, left — as in the reference top-down view,
         * in [com.sss.estem.data.model.Stem] order.
         */
        private val ARM_ANGLES_DEGREES = listOf(270f, 0f, 90f, 180f)

        /**
         * The body deliberately does not fill the canvas. The margin is where the cast shadow
         * goes, and an object with nowhere to cast one reads as a flat disc however carefully the
         * face is shaded.
         */
        private const val BODY_FRACTION = 0.88f

        fun of(size: Size): PuckLayout {
            val radius = minOf(size.width, size.height) / 2f * BODY_FRACTION
            val center = Offset(size.width / 2f, size.height / 2f)

            val arms = ARM_ANGLES_DEGREES.mapIndexed { index, degrees ->
                PuckArm(
                    index = index,
                    origin = center,
                    // Starts clear of the centre button, which is now a raised cap rather than a
                    // moulded line and would otherwise sit under the innermost LED.
                    angleRad = (degrees * PI_F / 180f),
                    innerRadius = radius * 0.20f,
                    outerRadius = radius * 0.80f,
                )
            }

            fun circle(cx: Float, cy: Float, r: Float) = Rect(cx - r, cy - r, cx + r, cy + r)

            // The centre is the only thing on the face. Prev, next and power used to be moulded
            // into the rim; seeking is now the bar under the title, which does the same job better
            // than two taps ever did, and the face is quieter for losing all three.
            val buttons = mapOf(
                PuckButton.CENTER to circle(center.x, center.y, radius * 0.155f),
            )

            return PuckLayout(center = center, radius = radius, arms = arms, buttons = buttons)
        }

        /**
         * How many LEDs are lit for a level, counting outward from the centre. Unlike the old
         * parallel bars there is no mirroring — the arm simply fills outward, so distance from
         * the middle reads directly as volume.
         */
        fun litCount(level: Float): Int =
            (level.coerceIn(0f, 1f) * LED_COUNT).toInt().coerceIn(0, LED_COUNT)

        private const val PI_F = 3.14159265f
    }
}
