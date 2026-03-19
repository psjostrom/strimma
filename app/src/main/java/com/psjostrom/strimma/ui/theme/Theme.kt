package com.psjostrom.strimma.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class ThemeMode { Dark, Light, System }

private val DarkColorScheme = darkColorScheme(
    primary = InRange,
    secondary = DarkTextSecondary,
    background = DarkBg,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceCard,
    onPrimary = DarkBg,
    onSecondary = DarkBg,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkTextTertiary,
    outlineVariant = DarkSurfaceBorder,
    error = BelowLow
)

private val LightColorScheme = lightColorScheme(
    primary = InRange,
    secondary = LightTextSecondary,
    background = LightBg,
    surface = LightSurface,
    surfaceVariant = LightSurfaceCard,
    onPrimary = LightBg,
    onSecondary = LightBg,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary,
    onSurfaceVariant = LightTextSecondary,
    outline = LightTextTertiary,
    outlineVariant = LightSurfaceBorder,
    error = BelowLow
)

private val StrimmaShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp)
)

@Composable
fun StrimmaTheme(
    themeMode: ThemeMode = ThemeMode.Dark,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = if (isDark) DarkColorScheme else LightColorScheme,
        shapes = StrimmaShapes,
        content = content
    )
}
