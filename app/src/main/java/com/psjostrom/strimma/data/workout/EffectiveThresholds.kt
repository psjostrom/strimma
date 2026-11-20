package com.psjostrom.strimma.data.workout

/** The alert set that [EffectiveThresholds.alertProtocol] was taken from. */
enum class AlertSet { DAY, NIGHT, EXERCISE }

/**
 * Combined threshold snapshot used by every **live, current-moment** BG-display
 * and alert consumer.
 *
 * The display fields define the graph's in-range band. [alertProtocol] contains
 * the complete alert configuration selected for the current moment, and
 * [activeSet] identifies which set that is.
 *
 * In workout mode (On), the set is [AlertSet.EXERCISE]: display fields come from
 * exerciseAlertLow/High and [alertProtocol] from the exercise alert settings.
 * Otherwise, inside the night window with night alerts enabled, the set is
 * [AlertSet.NIGHT]: [alertProtocol] comes from the night alert settings and the
 * display fields stay at settings.bgLow/bgHigh. Otherwise the set is
 * [AlertSet.DAY]: display fields come from settings.bgLow/bgHigh and
 * [alertProtocol] from the regular alert settings.
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
    val activeSet: AlertSet,
)
