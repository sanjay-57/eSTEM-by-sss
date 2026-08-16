package com.sss.estem.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF3A342A)

/**
 * One scheme, always. The app is a picture of a specific object on a specific surface; letting the
 * system flip it to dark would make it a picture of something else.
 */
private val StageScheme = lightColorScheme(
    primary = Ink,
    onPrimary = EstemColors.ShellHighlight,
    secondary = EstemColors.ShellShadow,
    onSecondary = EstemColors.Stage,
    background = EstemColors.Stage,
    onBackground = EstemColors.OnStage,
    surface = EstemColors.StageElevated,
    onSurface = EstemColors.OnStage,
    onSurfaceVariant = EstemColors.OnStageMuted,
    outline = EstemColors.Outline,
    error = LedPalette.LedRecording,
)

@Composable
fun EstemTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StageScheme, content = content)
}

/** The stage the device sits on. Same palette — kept as a separate name for call-site clarity. */
@Composable
fun EstemStageTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StageScheme, content = content)
}
