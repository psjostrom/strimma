package com.psjostrom.strimma.data.workout

/**
 * Combined threshold snapshot used by every **live, current-moment** BG-display
 * and alert consumer.
 *
 * The display fields define the graph's in-range band. [alertProtocol] contains
 * the complete alert configuration selected for the current mode, and
 * [workoutModeOn] identifies which protocol is active.
 *
 * In normal mode (workout Off), display fields come from settings.bgLow/bgHigh
 * and [alertProtocol] comes from the regular alert settings. In workout mode
 * (On), display fields come from exerciseAlertLow/High and [alertProtocol]
 * comes from the exercise alert settings.
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
    val alertProtocol: AlertProtocol,
    val workoutModeOn: Boolean,
)
