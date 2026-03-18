package com.psjostrom.strimma.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

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
    large = RoundedCornerShape(16.dp)
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

    MaterialTheme(
        colorScheme = if (isDark) DarkColorScheme else LightColorScheme,
        shapes = StrimmaShapes,
        content = content
    )
}
