package com.psjostrom.strimma.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.psjostrom.strimma.data.workout.AlertProtocol
import com.psjostrom.strimma.data.workout.NightWindow
import org.json.JSONObject

// The night set is a third alert set that replaces the Day set inside the night window.
// Its keys, protocol reads and export/import live here so SettingsRepository stays within detekt size limits.

internal val KEY_NIGHT_ALERTS_ENABLED = booleanPreferencesKey("night_alerts_enabled")
internal val KEY_NIGHT_ALERTS_START_MINUTE = intPreferencesKey("night_alerts_start_minute")
internal val KEY_NIGHT_ALERTS_END_MINUTE = intPreferencesKey("night_alerts_end_minute")
// Set once the night set holds user-owned values, so a later enable never overwrites them with the Day set.
internal val KEY_NIGHT_ALERTS_INITIALIZED = booleanPreferencesKey("night_alerts_initialized")
internal val KEY_NIGHT_ALERT_LOW = floatPreferencesKey("night_alert_low")
internal val KEY_NIGHT_ALERT_HIGH = floatPreferencesKey("night_alert_high")
internal val KEY_NIGHT_ALERT_URGENT_LOW = floatPreferencesKey("night_alert_urgent_low")
internal val KEY_NIGHT_ALERT_URGENT_HIGH = floatPreferencesKey("night_alert_urgent_high")
internal val KEY_NIGHT_ALERT_LOW_ENABLED = booleanPreferencesKey("night_alert_low_enabled")
internal val KEY_NIGHT_ALERT_HIGH_ENABLED = booleanPreferencesKey("night_alert_high_enabled")
internal val KEY_NIGHT_ALERT_URGENT_LOW_ENABLED = booleanPreferencesKey("night_alert_urgent_low_enabled")
internal val KEY_NIGHT_ALERT_URGENT_HIGH_ENABLED = booleanPreferencesKey("night_alert_urgent_high_enabled")
internal val KEY_NIGHT_ALERT_LOW_SOON_ENABLED = booleanPreferencesKey("night_alert_low_soon_enabled")
internal val KEY_NIGHT_ALERT_HIGH_SOON_ENABLED = booleanPreferencesKey("night_alert_high_soon_enabled")
internal val KEY_NIGHT_ALERT_STALE_ENABLED = booleanPreferencesKey("night_alert_stale_enabled")

private val NIGHT_ALERT_FIELDS = listOf(
    "low_enabled", "high_enabled", "urgent_low_enabled", "urgent_high_enabled",
    "low_soon_enabled", "high_soon_enabled", "stale_enabled",
    "low", "high", "urgent_low", "urgent_high",
).map { "night_alert_$it" }

internal fun MutablePreferences.putNightAlertProtocol(protocol: AlertProtocol) {
    this[KEY_NIGHT_ALERT_URGENT_LOW_ENABLED] = protocol.urgentLowEnabled
    this[KEY_NIGHT_ALERT_LOW_ENABLED] = protocol.lowEnabled
    this[KEY_NIGHT_ALERT_HIGH_ENABLED] = protocol.highEnabled
    this[KEY_NIGHT_ALERT_URGENT_HIGH_ENABLED] = protocol.urgentHighEnabled
    this[KEY_NIGHT_ALERT_URGENT_LOW] = protocol.urgentLowMgdl
    this[KEY_NIGHT_ALERT_LOW] = protocol.lowMgdl
    this[KEY_NIGHT_ALERT_HIGH] = protocol.highMgdl
    this[KEY_NIGHT_ALERT_URGENT_HIGH] = protocol.urgentHighMgdl
    this[KEY_NIGHT_ALERT_LOW_SOON_ENABLED] = protocol.lowSoonEnabled
    this[KEY_NIGHT_ALERT_HIGH_SOON_ENABLED] = protocol.highSoonEnabled
    this[KEY_NIGHT_ALERT_STALE_ENABLED] = protocol.staleEnabled
}

internal fun Preferences.nightWindow(): NightWindow = NightWindow(
    startMinute = this[KEY_NIGHT_ALERTS_START_MINUTE] ?: NightWindow.DEFAULT.startMinute,
    endMinute = this[KEY_NIGHT_ALERTS_END_MINUTE] ?: NightWindow.DEFAULT.endMinute,
)

internal fun Preferences.nightAlertProtocol() = AlertProtocol(
    urgentLowEnabled = this[KEY_NIGHT_ALERT_URGENT_LOW_ENABLED] ?: true,
    lowEnabled = this[KEY_NIGHT_ALERT_LOW_ENABLED] ?: true,
    highEnabled = this[KEY_NIGHT_ALERT_HIGH_ENABLED] ?: true,
    urgentHighEnabled = this[KEY_NIGHT_ALERT_URGENT_HIGH_ENABLED] ?: true,
    urgentLowMgdl = this[KEY_NIGHT_ALERT_URGENT_LOW] ?: SettingsRepository.DEFAULT_ALERT_URGENT_LOW,
    lowMgdl = this[KEY_NIGHT_ALERT_LOW] ?: SettingsRepository.DEFAULT_ALERT_LOW,
    highMgdl = this[KEY_NIGHT_ALERT_HIGH] ?: SettingsRepository.DEFAULT_ALERT_HIGH,
    urgentHighMgdl = this[KEY_NIGHT_ALERT_URGENT_HIGH] ?: SettingsRepository.DEFAULT_ALERT_URGENT_HIGH,
    lowSoonEnabled = this[KEY_NIGHT_ALERT_LOW_SOON_ENABLED] ?: true,
    highSoonEnabled = this[KEY_NIGHT_ALERT_HIGH_SOON_ENABLED] ?: true,
    staleEnabled = this[KEY_NIGHT_ALERT_STALE_ENABLED] ?: true,
)

private fun MutablePreferences.importNightWindow(settings: JSONObject) {
    val current = nightWindow()
    val imported = NightWindow(
        startMinute = settings.optInt("night_alerts_start_minute", current.startMinute),
        endMinute = settings.optInt("night_alerts_end_minute", current.endMinute),
    )
    val day = 0 until SettingsRepository.MINUTES_PER_DAY
    require(imported.startMinute in day && imported.endMinute in day) {
        "Night window minutes must be within a day"
    }
    // Equal start and end means "always on", which an import must not switch on by accident.
    if (imported.startMinute == imported.endMinute) return
    this[KEY_NIGHT_ALERTS_START_MINUTE] = imported.startMinute
    this[KEY_NIGHT_ALERTS_END_MINUTE] = imported.endMinute
}

// `seedNightFromDay` copies the Day set into the night keys once, so night fields the file omits
// come from the Day set instead of hardcoded defaults.
internal fun MutablePreferences.importNightAlerts(settings: JSONObject, seedNightFromDay: () -> Unit) {
    if (settings.has("night_alerts_enabled")) {
        this[KEY_NIGHT_ALERTS_ENABLED] = settings.getBoolean("night_alerts_enabled")
    }
    importNightWindow(settings)
    if (NIGHT_ALERT_FIELDS.none(settings::has)) return
    seedNightFromDay()
    val current = nightAlertProtocol()
    fun flag(field: String, fallback: Boolean) = settings.optBoolean("night_alert_$field", fallback)
    fun threshold(field: String, fallback: Float) =
        settings.optDouble("night_alert_$field", fallback.toDouble()).toFloat()
    val imported = current.copy(
        urgentLowEnabled = flag("urgent_low_enabled", current.urgentLowEnabled),
        lowEnabled = flag("low_enabled", current.lowEnabled),
        highEnabled = flag("high_enabled", current.highEnabled),
        urgentHighEnabled = flag("urgent_high_enabled", current.urgentHighEnabled),
        lowSoonEnabled = flag("low_soon_enabled", current.lowSoonEnabled),
        highSoonEnabled = flag("high_soon_enabled", current.highSoonEnabled),
        staleEnabled = flag("stale_enabled", current.staleEnabled),
        urgentLowMgdl = threshold("urgent_low", current.urgentLowMgdl),
        lowMgdl = threshold("low", current.lowMgdl),
        highMgdl = threshold("high", current.highMgdl),
        urgentHighMgdl = threshold("urgent_high", current.urgentHighMgdl),
    )
    // No ordering check: the first enable copies the Day set, and Day thresholds are stored unvalidated.
    putNightAlertProtocol(imported)
}

internal fun JSONObject.putNightAlertSettings(prefs: Preferences) {
    put("night_alerts_enabled", prefs[KEY_NIGHT_ALERTS_ENABLED] ?: false)
    val window = prefs.nightWindow()
    put("night_alerts_start_minute", window.startMinute)
    put("night_alerts_end_minute", window.endMinute)
    // An uncopied night set holds no user values. Exporting defaults would stop the importing device from copying its Day set.
    if (KEY_NIGHT_ALERTS_INITIALIZED !in prefs) return
    val protocol = prefs.nightAlertProtocol()
    put("night_alert_low_enabled", protocol.lowEnabled)
    put("night_alert_high_enabled", protocol.highEnabled)
    put("night_alert_urgent_low_enabled", protocol.urgentLowEnabled)
    put("night_alert_urgent_high_enabled", protocol.urgentHighEnabled)
    put("night_alert_low_soon_enabled", protocol.lowSoonEnabled)
    put("night_alert_high_soon_enabled", protocol.highSoonEnabled)
    put("night_alert_stale_enabled", protocol.staleEnabled)
    put("night_alert_low", protocol.lowMgdl.toDouble())
    put("night_alert_high", protocol.highMgdl.toDouble())
    put("night_alert_urgent_low", protocol.urgentLowMgdl.toDouble())
    put("night_alert_urgent_high", protocol.urgentHighMgdl.toDouble())
}
