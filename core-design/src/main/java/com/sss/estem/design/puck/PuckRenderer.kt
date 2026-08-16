package com.sss.estem.design.puck

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import com.sss.estem.design.EstemColors
import com.sss.estem.design.LedPalette
import kotlin.math.cos
import kotlin.math.sin

/** Everything the renderer needs to draw one frame. Pure data — no engine, no Compose state. */
data class PuckVisualState(
    val stemLevels: List<Float>,
    /**
     * Live signal magnitude per stem, 0f..1f. The number of lit LEDs comes from the slider; this
     * is what makes them move — the device's lights flash in time with the music rather than
     * sitting at a fixed brightness.
     */
    val stemEnergies: List<Float> = emptyList(),
    val isolatedStems: Set<Int> = emptySet(),
    val mutedStems: Set<Int> = emptySet(),
    val pressedButtons: Set<PuckButton> = emptySet(),
    val locked: Boolean = false,
    val recording: Boolean = false,
    val playing: Boolean = false,
    /**
     * True while this deck is locked to the other one's tempo.
     *
     * The hardware's blue is its pairing state — two devices agreeing on something. Two decks
     * agreeing on a tempo is the same idea, so sync gets the same colour, and it takes the whole
     * panel rather than one arm because it is a property of the machine, not of a stem.
     */
    val synced: Boolean = false,
    /**
     * When a menu is open the four arms stop being four stems and become one two-axis control,
     * exactly as on the hardware: one axis selects, the other sets intensity.
     */
    val twoAxis: TwoAxisState? = null,
)

/**
 * @param selection 0..7 — which of eight effects, or which loop length.
 * @param intensity 0f..1f.
 */
data class TwoAxisState(val selection: Int, val intensity: Float)

private const val PI_F = 3.14159265f

/**
 * Draws the device from directly above: a cream dome with a finely knurled rim, four arms of
 * light in a cross, and a lit centre button.
 *
 * Three things carry the likeness, all taken from the reference photographs. The body is shaded
 * as a **dome** — one broad soft crown of light, no hard edges, rolling off to a shaded rim. An
 * unlit LED is drawn as **nothing at all**, because the plastic is opaque until something lights
 * up behind it. And LED colour **tracks distance from the centre**, warm orange close in cooling
 * to blue at the outer end, which is why a single arm in the photo is orange at one end and blue
 * at the other.
 */
fun DrawScope.drawPuck(layout: PuckLayout, state: PuckVisualState) {
    drawContactShadow(layout)
    drawKnurledEdge(layout)
    drawShell(layout)
    if (state.twoAxis != null) {
        drawTwoAxis(layout, state.twoAxis, state.locked)
    } else {
        drawStemArms(layout, state)
    }
    drawCenterButton(layout, state)
}

/**
 * The fine teeth around the circumference. They sit *under* the dome and peek out by a hair, which
 * is how they read in the photo — a texture at the silhouette, not a drawn gear.
 */
private fun DrawScope.drawKnurledEdge(layout: PuckLayout) {
    // Many very short teeth. In the photo the knurl is a texture you only notice at the
    // silhouette — long spikes turn it into a drawn gear.
    val teeth = 220
    val inner = layout.radius * 0.992f
    val outer = layout.radius * 1.006f
    for (i in 0 until teeth) {
        val a = 2f * PI_F * i / teeth
        val c = cos(a)
        val s = sin(a)
        drawLine(
            color = EstemColors.ShellEdge.copy(alpha = 0.42f),
            start = Offset(layout.center.x + inner * c, layout.center.y + inner * s),
            end = Offset(layout.center.x + outer * c, layout.center.y + outer * s),
            strokeWidth = layout.radius * 0.0045f,
        )
    }
}

/**
 * The shadow the object drops on the surface behind it.
 *
 * This is the single biggest thing separating "a shaded circle" from "an object in a photograph",
 * and it is why [PuckLayout] leaves a margin. A `DrawScope` has no blur, so the softness comes out
 * of the gradient and the flattening out of a vertical squash — a shadow cast onto a surface
 * receding away from the viewer is an ellipse, never a circle.
 */
internal fun DrawScope.drawContactShadow(layout: PuckLayout) {
    val r = layout.radius
    val anchor = Offset(layout.center.x + r * 0.06f, layout.center.y + r * 0.84f)

    scale(scaleX = 1.02f, scaleY = 0.26f, pivot = anchor) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    EstemColors.ShadowInk.copy(alpha = 0.42f),
                    EstemColors.ShadowInk.copy(alpha = 0.26f),
                    EstemColors.ShadowInk.copy(alpha = 0.07f),
                    Color.Transparent,
                ),
                center = anchor,
                radius = r * 1.06f,
            ),
            radius = r * 1.06f,
            center = anchor,
        )
    }
}

/**
 * The body, lit as a sphere by a single key light from the upper left.
 *
 * Four things in order, and the order is the point: a body gradient that runs all the way to a
 * dark terminator, a matte crown where the key lands, the occluded rim, and finally a thread of
 * **bounce light** along the lower-right edge. That last one is what the eye actually reads as
 * roundness — without it a sphere just looks like a circle with a gradient on it, because nothing
 * tells you there is a surface underneath throwing light back up.
 */
internal fun DrawScope.drawShell(layout: PuckLayout) {
    val c = layout.center
    val r = layout.radius
    val key = c - Offset(r * 0.30f, r * 0.38f)

    // Body. Reaching a genuinely dark value at the far edge is what gives the form its volume;
    // the old gradient stopped at mid-shadow and stayed flat.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                EstemColors.ShellHighlight,
                Color(0xFFE8E3D7),
                EstemColors.Shell,
                Color(0xFFC3BCA9),
                EstemColors.ShellShadow,
                EstemColors.ShellTerminator,
            ),
            center = key,
            radius = r * 1.62f,
        ),
        radius = r,
        center = c,
    )

    // Matte plastic: a broad soft crown rather than a tight specular dot. The reference object is
    // not glossy, so the highlight has a long falloff and never reaches white.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.34f),
                Color.White.copy(alpha = 0.10f),
                Color.Transparent,
            ),
            center = key,
            radius = r * 0.72f,
        ),
        radius = r,
        center = c,
    )

    // Occlusion at the silhouette, so the edge turns away on every side.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                Color.Transparent,
                EstemColors.ShellShadow.copy(alpha = 0.14f),
                EstemColors.ShellTerminator.copy(alpha = 0.42f),
            ),
            center = c,
            radius = r,
        ),
        radius = r,
        center = c,
    )

    drawBounceLight(c, r)
}

/**
 * Light coming back up off the surface the object sits on, caught by the underside of the body.
 *
 * Drawn as a gradient anchored *outside* the silhouette rather than as arcs along it. Stroked arcs
 * have hard ends and hard sides, so stacking them to fake a blur produces visible crescent seams —
 * a gradient whose origin sits just beyond the lower-right rim is bright exactly where the bounce
 * would land and falls off smoothly in every direction, with nothing to seam.
 */
private fun DrawScope.drawBounceLight(c: Offset, r: Float) {
    val origin = c + Offset(r * 0.68f, r * 0.70f)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                EstemColors.ShellHighlight.copy(alpha = 0.50f),
                EstemColors.ShellHighlight.copy(alpha = 0.16f),
                Color.Transparent,
            ),
            center = origin,
            radius = r * 0.78f,
        ),
        radius = r,
        center = c,
    )
}

private fun DrawScope.drawStemArms(layout: PuckLayout, state: PuckVisualState) {
    layout.arms.forEach { arm ->
        if (arm.index in state.mutedStems) return@forEach

        val level = state.stemLevels.getOrElse(arm.index) { 0f }
        val lit = if (state.locked) 0 else PuckLayout.litCount(level)
        val isolated = arm.index in state.isolatedStems
        val isolatedElsewhere = state.isolatedStems.isNotEmpty() && !isolated
        val energy = state.stemEnergies.getOrElse(arm.index) { 1f }

        for (i in 0 until lit) {
            val t = i.toFloat() / (PuckLayout.LED_COUNT - 1)
            val color = when {
                // Ordered by how much each one is a state of the whole device rather than of one
                // stem: recording is the loudest claim it can make, then a held solo, then sync.
                state.recording -> LedPalette.LedRecording
                isolated -> LedPalette.LedIsolated
                state.synced -> LedPalette.LedSync
                // Loud passages push the whole arm toward its cool end, so the panel visibly
                // shifts colour with the music instead of holding one fixed ramp.
                else -> LedPalette.ledForPosition(t + energy * 0.22f)
            }
            val strength = when {
                isolatedElsewhere -> 0.30f
                // Never fully dark — the LED is on, it just breathes. The floor sets how bright a
                // silent stem still reads; the span above it is what makes the panel move with the
                // music, so raising the floor has to keep enough of that span to stay visible.
                else -> 0.45f + 0.55f * energy
            }
            drawLed(arm.ledCenter(i), layout.radius, color, strength)
        }
    }
}

/**
 * Two-axis menu. Eight effects no longer fit on one arm now that an arm has five LEDs, so the
 * selection runs across the first two arms as a single ten-light strip and the remaining two
 * carry the intensity.
 */
private fun DrawScope.drawTwoAxis(layout: PuckLayout, axis: TwoAxisState, locked: Boolean) {
    if (locked) return

    val perArm = PuckLayout.LED_COUNT
    val selection = axis.selection.coerceIn(0, SELECTION_ARMS * perArm - 1)
    layout.arms.getOrNull(selection / perArm)?.let { arm ->
        drawLed(arm.ledCenter(selection % perArm), layout.radius, LedPalette.LedIsolated, 1f)
    }

    val intensityArms = layout.arms.drop(SELECTION_ARMS)
    val total = intensityArms.size * perArm
    val lit = (axis.intensity.coerceIn(0f, 1f) * total).toInt()
    intensityArms.forEachIndexed { armIndex, arm ->
        for (i in 0 until perArm) {
            val flat = armIndex * perArm + i
            if (flat >= lit) break
            drawLed(arm.ledCenter(i), layout.radius, LedPalette.ledForPosition(i / (perArm - 1f)), 1f)
        }
    }
}

/** Arms 0 and 1 show which effect is selected; 2 and 3 show how hard it hits. */
private const val SELECTION_ARMS = 2

/**
 * One LED seen through the shell: a wide diffuse halo, a tighter glow, then a small near-white
 * core. The layering is what sells "light under plastic" rather than a painted dot — a single
 * flat circle always looks stuck on top.
 */
private fun DrawScope.drawLed(
    position: Offset,
    puckRadius: Float,
    color: Color,
    strength: Float,
) {
    val core = puckRadius * 0.042f

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.46f * strength), Color.Transparent),
            center = position,
            radius = core * 4.0f,
        ),
        radius = core * 4.0f,
        center = position,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.95f * strength), Color.Transparent),
            center = position,
            radius = core * 2.1f,
        ),
        radius = core * 2.1f,
        center = position,
    )
    drawCircle(
        color = lerp(color, Color.White, 0.62f).copy(alpha = strength),
        radius = core * 0.98f,
        center = position,
    )
}

/**
 * The centre button: a raised cap with its own small dome, sitting proud of the body.
 *
 * It used to be a moulded line that was almost invisible unlit, on the reasoning that a cap would
 * break the single-piece look. In practice that read as nothing at all — the one control on the
 * face has to look like a control. It is lit by the same key light as the body, drops its own
 * small shadow, and sinks into the shell when pressed rather than just darkening.
 */
private fun DrawScope.drawCenterButton(layout: PuckLayout, state: PuckVisualState) {
    val rect = layout.buttons[PuckButton.CENTER] ?: return
    val c = rect.center
    val r = rect.width / 2f
    val pressed = PuckButton.CENTER in state.pressedButtons
    // Pressed, the cap sits almost flush: the shadow collapses and the bevel loses its lift.
    val lift = if (pressed) 0.30f else 1f

    // The seam the cap sits in, and its own shadow on the far side from the key.
    val shadowAt = c + Offset(r * 0.09f * lift, r * 0.15f * lift)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                EstemColors.ShellTerminator.copy(alpha = 0.50f),
                EstemColors.ShellTerminator.copy(alpha = 0.20f),
                Color.Transparent,
            ),
            center = shadowAt,
            radius = r * 1.40f,
        ),
        radius = r * 1.40f,
        center = shadowAt,
    )

    // The cap: a miniature of the body, so it belongs to the same object and the same light.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                EstemColors.ShellHighlight,
                EstemColors.Shell,
                Color(0xFFC5BEAB),
            ),
            center = c - Offset(r * 0.34f, r * 0.40f),
            radius = r * 1.75f,
        ),
        radius = r,
        center = c,
    )

    // Bevel: one continuous stroked ring carrying a light-to-dark gradient across it, so the lip
    // is bright where the key lands and dark opposite. Drawing it as two arcs instead leaves the
    // ends visible as a floating C — the ring has to close, the *shading* is what gives direction.
    val bevel = r * 0.13f
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.85f * lift),
                EstemColors.ShellHighlight.copy(alpha = 0.30f * lift),
                EstemColors.ShellTerminator.copy(alpha = 0.55f),
            ),
            start = Offset(c.x - r, c.y - r),
            end = Offset(c.x + r, c.y + r),
        ),
        radius = r - bevel / 2f,
        center = c,
        style = Stroke(width = bevel),
    )

    if (!state.locked) {
        val color = when {
            state.recording -> LedPalette.LedRecording
            state.synced -> LedPalette.LedSync
            else -> LedPalette.LedCenter
        }
        drawLed(c, layout.radius, color, if (state.playing) 1f else 0.60f)
    }
}

/** Kept for the back face, which shares the dome. */
internal fun DrawScope.drawRim(layout: PuckLayout) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                Color.Transparent,
                EstemColors.ShellShadow.copy(alpha = 0.42f),
            ),
            center = layout.center,
            radius = layout.radius,
        ),
        radius = layout.radius,
        center = layout.center,
    )
}
