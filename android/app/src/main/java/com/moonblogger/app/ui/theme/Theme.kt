package com.moonblogger.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Tema Material 3 de MoonBlogger. Solo variante clara, coherente con la web.
 * Colores y formas centralizados a partir de `web/app/tokens.css`.
 */
private val MoonColorScheme = lightColorScheme(
    primary = MoonPrimary,
    onPrimary = Color.White,
    primaryContainer = MoonPink,
    onPrimaryContainer = MoonText,
    secondary = MoonFocus,
    onSecondary = Color.White,
    secondaryContainer = MoonLavender,
    onSecondaryContainer = MoonText,
    tertiary = MoonPrimaryHover,
    onTertiary = Color.White,
    background = MoonBg,
    onBackground = MoonText,
    surface = MoonSurface,
    onSurface = MoonText,
    surfaceVariant = MoonSurfaceAlt,
    onSurfaceVariant = MoonTextMuted,
    outline = MoonBorder,
    outlineVariant = MoonBorder,
    surfaceTint = MoonPrimary,
    error = Color(0xFFB3261E),
    onError = Color.White,
)

@Composable
fun MoonBloggerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MoonColorScheme,
        shapes = MoonShapes,
        typography = MoonTypography,
        content = content,
    )
}
