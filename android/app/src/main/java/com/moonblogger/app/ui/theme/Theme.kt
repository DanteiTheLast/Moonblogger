package com.moonblogger.app.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemePreference(val label: String) { SYSTEM("Sistema"), LIGHT("Claro"), DARK("Oscuro") }
class ThemeController(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var preference by mutableStateOf(runCatching { ThemePreference.valueOf(preferences.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemePreference.SYSTEM))
        private set
    fun select(value: ThemePreference) { preferences.edit().putString("theme", value.name).apply(); preference = value }
}
val LocalThemeController = staticCompositionLocalOf<ThemeController?> { null }
data class MoonAccents(val structural: Color, val onStructural: Color, val shadow: Color, val largeShadow: Color)
val LocalMoonAccents = staticCompositionLocalOf { MoonAccents(Color(0xFF233B59), Color(0xFFFFF5E9), Color(0xFFD2D8E8), Color(0xFFBBC4DE)) }
internal val MoonLight = lightColorScheme(
    primary = Color(0xFFA04C72), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3EE), onPrimaryContainer = Color(0xFF46334F),
    secondary = Color(0xFF233B59), onSecondary = Color(0xFFFFF5E9),
    secondaryContainer = Color(0xFFDCE3EE), onSecondaryContainer = Color(0xFF46334F),
    background = Color(0xFFE4EAF2), onBackground = Color(0xFF46334F),
    surface = Color(0xFFFFF9F1), onSurface = Color(0xFF46334F),
    surfaceContainerLowest = Color(0xFFFFF9F1), surfaceContainerLow = Color(0xFFFFF9F1), surfaceContainer = Color(0xFFFFF9F1), surfaceContainerHigh = Color(0xFFFFF9F1), surfaceContainerHighest = Color(0xFFDCE3EE),
    surfaceVariant = Color(0xFFDCE3EE), onSurfaceVariant = Color(0xFF6E596A),
    outline = Color(0xFF6E596A), outlineVariant = Color(0xFF46334F), surfaceTint = Color.Transparent,
)
internal val MoonDark = darkColorScheme(
    primary = Color(0xFFFFB9D5), onPrimary = Color(0xFF30233B),
    primaryContainer = Color(0xFF30233B), onPrimaryContainer = Color(0xFFF5EAF4),
    secondary = Color(0xFFD9C7D8), onSecondary = Color(0xFF30233B),
    secondaryContainer = Color(0xFF30233B), onSecondaryContainer = Color(0xFFF5EAF4),
    background = Color(0xFF1B1420), onBackground = Color(0xFFF5EAF4),
    surface = Color(0xFF3B2B43), onSurface = Color(0xFFF5EAF4),
    surfaceContainerLowest = Color(0xFF30233B), surfaceContainerLow = Color(0xFF3B2B43), surfaceContainer = Color(0xFF3B2B43), surfaceContainerHigh = Color(0xFF3B2B43), surfaceContainerHighest = Color(0xFF30233B),
    surfaceVariant = Color(0xFF30233B), onSurfaceVariant = Color(0xFFD9C7D8),
    outline = Color(0xFFD9C7D8), outlineVariant = Color(0xFF806789), surfaceTint = Color.Transparent,
)
@Composable
fun MoonBloggerTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ThemeController(context.applicationContext) }
    val dark = when (controller.preference) { ThemePreference.SYSTEM -> isSystemInDarkTheme(); ThemePreference.DARK -> true; ThemePreference.LIGHT -> false }
    val scheme = if (dark) MoonDark else MoonLight
    val accents = if (dark) MoonAccents(Color(0xFF30233B), Color(0xFFF5EAF4), Color(0xFF806789), Color(0xFF1A1219)) else LocalMoonAccents.current
    val view = LocalView.current
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    CompositionLocalProvider(LocalThemeController provides controller, LocalMoonAccents provides accents) {
        MaterialTheme(colorScheme = scheme, shapes = MoonShapes, typography = MoonTypography) {
            Surface(modifier = Modifier.fillMaxSize(), color = scheme.background, content = content)
        }
    }
}
