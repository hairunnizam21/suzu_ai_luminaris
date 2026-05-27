package com.suzuai.luminaris.shared.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LuminarisDark = darkColorScheme(
    primary = LuminarisColors.AccentCyan,
    onPrimary = Color.Black,
    secondary = LuminarisColors.AccentGreen,
    onSecondary = Color.Black,
    background = LuminarisColors.Bg,
    onBackground = LuminarisColors.Text,
    surface = LuminarisColors.Surface,
    onSurface = LuminarisColors.Text,
    surfaceVariant = LuminarisColors.SurfaceAlt,
    onSurfaceVariant = LuminarisColors.TextDim,
    outline = LuminarisColors.Outline,
    error = LuminarisColors.AccentRed,
)

@Composable
fun LuminarisTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Single forced-dark palette; Luminaris is always the hacker terminal look.
    MaterialTheme(
        colorScheme = LuminarisDark,
        typography = LuminarisTypography,
        content = content,
    )
}
