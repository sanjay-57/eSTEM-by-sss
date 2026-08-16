package com.sss.estem.design

import androidx.compose.ui.graphics.Color

/**
 * Sampled from the reference photographs rather than invented.
 *
 * The device is a bone/cream dome photographed against a warm beige ground, so the app's stage is
 * that same beige — a black background would make the object read as a cut-out rather than as a
 * thing sitting on a surface.
 */
object EstemColors {
    /** The warm ground the device sits on. */
    val Stage = Color(0xFFE9E4D6)
    val StageElevated = Color(0xFFDFD9C9)

    /** The dome itself: mid body, its lit crown, and the shaded roll-off at the rim. */
    val Shell = Color(0xFFD9D4C5)
    val ShellHighlight = Color(0xFFEFECE1)
    val ShellShadow = Color(0xFFB4AD9A)
    /** The far side of the body, where the key light has fallen off completely. */
    val ShellTerminator = Color(0xFF9A9280)
    /** The fine knurled teeth around the circumference. */
    val ShellEdge = Color(0xFFA79F8B)

    /**
     * The shadow the object casts on the ground. Warm rather than black: on a beige surface a
     * neutral shadow reads as a hole cut in the page instead of as light being blocked.
     */
    val ShadowInk = Color(0xFF6E6553)

    val OnStage = Color(0xFF2B2721)
    val OnStageMuted = Color(0xFF6F6759)
    val Outline = Color(0xFFC8C1B0)
}

/**
 * LED colours.
 *
 * The device does not light every LED the same colour. In the reference photo a single arm runs
 * warm orange near the middle and cools to blue at its outer end, so the hue encodes *how far out
 * the light has travelled* — a quiet stem glows warm, a loud one glows blue. [ledForPosition]
 * is that ramp.
 */
object LedPalette {
    val LedInner = Color(0xFFFF8A3D)
    val LedMid = Color(0xFFFFE6C0)
    val LedOuter = Color(0xFF7FB0FF)

    val LedIsolated = Color(0xFFFFC246)
    val LedRecording = Color(0xFFFF3B30)
    /** Reserved for the phase-2 deck-sync state, matching the hardware's Bluetooth blue. */
    val LedSync = Color(0xFF3B82F6)

    /** Resting colour of the centre button's light. */
    val LedCenter = Color(0xFF8FB8FF)

    /**
     * @param t 0 at the innermost LED of an arm, 1 at the outermost.
     */
    fun ledForPosition(t: Float): Color {
        val clamped = t.coerceIn(0f, 1f)
        return if (clamped < 0.5f) {
            androidx.compose.ui.graphics.lerp(LedInner, LedMid, clamped / 0.5f)
        } else {
            androidx.compose.ui.graphics.lerp(LedMid, LedOuter, (clamped - 0.5f) / 0.5f)
        }
    }
}
