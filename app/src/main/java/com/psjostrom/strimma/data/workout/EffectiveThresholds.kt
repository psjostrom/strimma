package com.psjostrom.strimma.data.workout

/**
 * Combined threshold set used by every **live, current-moment** BG-display and
 * alert consumer.
 *
 * In normal mode (workout Off), display fields come from settings.bgLow/bgHigh
 * while alert fields come from settings.alertLow/High/UrgentLow/UrgentHigh —
 * they are independent settings.
 *
 * In workout mode (On), all 6 fields are derived from the 4 workoutAlert*
 * settings: workoutAlertLow drives both displayLow AND alertLow, etc.
 *
 * **Do NOT use for historical analysis** (Story view, monthly TIR, AGP).
 * Historical analysis must read the user's standard targets directly from
 * SettingsRepository — using a transient runtime threshold to compute a past
 * month's TIR would silently corrupt the report.
 */
data class EffectiveThresholds(
    // Display: graph in-range band, hero color, widget, foreground notification, web server
    val displayLowMgdl: Float,
    val displayHighMgdl: Float,
    // Alerts: low/high firing
    val alertLowMgdl: Float,
    val alertHighMgdl: Float,
    val alertUrgentLowMgdl: Float,
    val alertUrgentHighMgdl: Float,
)
