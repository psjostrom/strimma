package com.psjostrom.strimma.ui.theme

import androidx.compose.ui.graphics.Color

// --- Dark palette (purple-warm, shared foundation with Springa) ---
val DarkBg = Color(0xFF111018)
val DarkSurface = Color(0xFF181520)
val DarkSurfaceCard = Color(0xFF1E1A2A)
val DarkSurfaceBorder = Color(0xFF2C2840)
val DarkTextPrimary = Color(0xFFFFFFFF)
val DarkTextSecondary = Color(0xFFA898C0)
val DarkTextTertiary = Color(0xFF6A5F80)

// --- Light palette ---
val LightBg = Color(0xFFF4F2F7)
val LightSurface = Color(0xFFFAFAFC)
val LightSurfaceCard = Color(0xFFFAFAFC)
val LightSurfaceBorder = Color(0xFFD5D0E0)
val LightTextPrimary = Color(0xFF18151F)
val LightTextSecondary = Color(0xFF6A5F80)
val LightTextTertiary = Color(0xFF9088A0)

// --- Status colors (same in both themes) ---
val InRange = Color(0xFF56CCF2)
val InRangeZone = Color(0x1256CCF2)
val AboveHigh = Color(0xFFFFB800)
val BelowLow = Color(0xFFFF4D6A)
val Stale = Color(0xFF6A5F80)

// --- TIR rating color ---
val TirGood = Color(0xFF4ADE80)

// --- Semantic tinted backgrounds (dark) — derived from status colors, surface-adaptive ---
val TintInRange = InRange.copy(alpha = 0.10f)
val TintGood = TirGood.copy(alpha = 0.10f)
val TintWarning = AboveHigh.copy(alpha = 0.12f)
val TintDanger = BelowLow.copy(alpha = 0.10f)

// --- Semantic tinted backgrounds (light) — derived from status colors, surface-adaptive ---
val LightTintInRange = InRange.copy(alpha = 0.12f)
val LightTintWarning = AboveHigh.copy(alpha = 0.14f)
val LightTintDanger = BelowLow.copy(alpha = 0.12f)

// --- AGP 5-tier colors ---
val VeryLow = Color(0xFFE53935)
val VeryHigh = Color(0xFFEF6C00)

// --- Treatment marker colors ---
val BolusBlue = Color(0xFF5B8DEF)
val CarbGreen = Color(0xFF4CAF50)

// --- Exercise marker color ---
val ExerciseDefault = Color(0xFF8B8BBA)

// Graph tooltip/canvas colors for dark graph surfaces (graphs always render dark)
val GraphTooltipBg = Color(0xE6181520)
val GraphAxisText = Color(0xFFA898C0)
