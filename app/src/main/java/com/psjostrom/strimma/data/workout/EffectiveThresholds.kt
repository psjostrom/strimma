package com.psjostrom.strimma.data.workout

/**
 * Combined threshold set used by every BG-display and alert consumer.
 *
 * In normal mode (workout Off), display fields come from settings.bgLow/bgHigh
 * while alert fields come from settings.alertLow/High/UrgentLow/UrgentHigh —
 * they are independent settings.
 *
 * In workout mode (On), all 6 fields are derived from the 4 workoutAlert*
 * settings: workoutAlertLow drives both displayLow AND alertLow, etc.
 */
data class EffectiveThresholds(
    // Display: graph in-range band, hero color, widget, web server, story view
    val displayLowMgdl: Float,
    val displayHighMgdl: Float,
    // Alerts: low/high firing
    val alertLowMgdl: Float,
    val alertHighMgdl: Float,
    val alertUrgentLowMgdl: Float,
    val alertUrgentHighMgdl: Float,
)
