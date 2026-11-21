package com.psjostrom.strimma.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_ALERT_COOLDOWN_MINUTES
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_ALERT_HIGH
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_ALERT_LOW
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_ALERT_URGENT_HIGH
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_ALERT_URGENT_LOW
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_BG_HIGH
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_BG_LOW
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_CUSTOM_DIA_HOURS
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_FOLLOWER_POLL_SECONDS
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_GRAPH_WINDOW_HOURS
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_NOTIF_GRAPH_MINUTES
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_PREDICTION_MINUTES
import com.psjostrom.strimma.data.SettingsRepository.Companion.SETTINGS_EXPORT_VERSION
import com.psjostrom.strimma.data.notification.NotificationActionConfig
import com.psjostrom.strimma.data.notification.SnoozeDuration
import org.json.JSONObject

// Settings export and import read and write the settings block of the backup JSON. They live
// outside SettingsRepository so the class stays within detekt size limits. The JSON envelope,
// secrets and widget settings stay in SettingsRepository.

@Suppress("CyclomaticComplexMethod") // Flat serialization of all settings
internal fun settingsToJson(prefs: Preferences): JSONObject = JSONObject().apply {
    put("nightscout_url", prefs[KEY_NIGHTSCOUT_URL] ?: "")
    put("graph_window_hours", prefs[KEY_GRAPH_WINDOW_HOURS] ?: DEFAULT_GRAPH_WINDOW_HOURS)
    put("bg_low", prefs[KEY_BG_LOW]?.toDouble() ?: DEFAULT_BG_LOW.toDouble())
    put("bg_high", prefs[KEY_BG_HIGH]?.toDouble() ?: DEFAULT_BG_HIGH.toDouble())
    put("alert_low_enabled", prefs[KEY_ALERT_LOW_ENABLED] ?: true)
    put("alert_high_enabled", prefs[KEY_ALERT_HIGH_ENABLED] ?: true)
    put("alert_urgent_low_enabled", prefs[KEY_ALERT_URGENT_LOW_ENABLED] ?: true)
    put("alert_urgent_high_enabled", prefs[KEY_ALERT_URGENT_HIGH_ENABLED] ?: true)
    put("alert_low", prefs[KEY_ALERT_LOW]?.toDouble() ?: DEFAULT_ALERT_LOW.toDouble())
    put("alert_high", prefs[KEY_ALERT_HIGH]?.toDouble() ?: DEFAULT_ALERT_HIGH.toDouble())
    put("alert_urgent_low", prefs[KEY_ALERT_URGENT_LOW]?.toDouble() ?: DEFAULT_ALERT_URGENT_LOW.toDouble())
    put("alert_urgent_high", prefs[KEY_ALERT_URGENT_HIGH]?.toDouble() ?: DEFAULT_ALERT_URGENT_HIGH.toDouble())
    put("alert_stale_enabled", prefs[KEY_ALERT_STALE_ENABLED] ?: true)
    put("alert_low_soon_enabled", prefs[KEY_ALERT_LOW_SOON_ENABLED] ?: true)
    put("alert_high_soon_enabled", prefs[KEY_ALERT_HIGH_SOON_ENABLED] ?: true)
    put("pattern_alerts_enabled", prefs[KEY_PATTERN_ALERTS_ENABLED] ?: true)
    putExerciseAlertSettings(prefs)
    putNightAlertSettings(prefs)
    put("alert_cooldown_minutes", prefs[KEY_ALERT_COOLDOWN_MINUTES] ?: DEFAULT_ALERT_COOLDOWN_MINUTES)
    put("alert_snooze_duration", normalizedAlertSnoozeDurationName(prefs[KEY_ALERT_SNOOZE_DURATION]))
    put("theme_mode", prefs[KEY_THEME_MODE] ?: "System")
    put("notif_graph_minutes", prefs[KEY_NOTIF_GRAPH_MINUTES] ?: DEFAULT_NOTIF_GRAPH_MINUTES)
    put("notif_prediction_minutes", prefs[KEY_NOTIF_PREDICTION_MINUTES] ?: DEFAULT_PREDICTION_MINUTES)
    put("notif_action_type", prefs[KEY_NOTIF_ACTION_TYPE] ?: NotificationActionConfig.DEFAULT.type.name)
    put("notif_snooze_category", prefs[KEY_NOTIF_SNOOZE_CATEGORY] ?: NotificationActionConfig.DEFAULT.snoozeCategory.name)
    put("notif_snooze_duration", prefs[KEY_NOTIF_SNOOZE_DURATION] ?: NotificationActionConfig.DEFAULT.snoozeDuration.name)
    put("glucose_unit", prefs[KEY_GLUCOSE_UNIT] ?: "MMOL")
    put("hba1c_unit", prefs[KEY_HBA1C_UNIT] ?: "MMOL_MOL")
    put("bg_broadcast_enabled", prefs[KEY_BG_BROADCAST_ENABLED] ?: false)
    put("glucose_source", prefs[KEY_GLUCOSE_SOURCE] ?: "COMPANION")
    put("follower_poll_seconds", prefs[KEY_FOLLOWER_POLL_SECONDS] ?: DEFAULT_FOLLOWER_POLL_SECONDS)
    put("treatments_sync_enabled", prefs[KEY_TREATMENTS_SYNC_ENABLED] ?: false)
    put("insulin_type", prefs[KEY_INSULIN_TYPE] ?: "FIASP")
    put("custom_dia", prefs[KEY_CUSTOM_DIA]?.toDouble() ?: DEFAULT_CUSTOM_DIA_HOURS)
    put("web_server_enabled", prefs[KEY_WEB_SERVER_ENABLED] ?: false)
    put("start_on_boot", prefs[KEY_START_ON_BOOT] ?: true)
    put("hc_write_enabled", prefs[KEY_HC_WRITE_ENABLED] ?: false)
}

@Suppress("CyclomaticComplexMethod", "LongMethod") // Flat deserialization of all settings
internal fun applySettingsJson(prefs: MutablePreferences, settings: JSONObject, importVersion: Int) {
    // v1 exports stored thresholds in mmol/L — convert to mg/dL on import
    val isV1 = importVersion < 2
    fun importThreshold(key: String): Float = settings.importThreshold(key, isV1)

    if (settings.has("nightscout_url")) prefs[KEY_NIGHTSCOUT_URL] = settings.getString("nightscout_url").trim()
    if (settings.has("graph_window_hours")) prefs[KEY_GRAPH_WINDOW_HOURS] = settings.getInt("graph_window_hours")
    if (settings.has("bg_low")) prefs[KEY_BG_LOW] = importThreshold("bg_low")
    if (settings.has("bg_high")) prefs[KEY_BG_HIGH] = importThreshold("bg_high")
    if (settings.has("alert_low_enabled")) prefs[KEY_ALERT_LOW_ENABLED] = settings.getBoolean("alert_low_enabled")
    if (settings.has("alert_high_enabled")) prefs[KEY_ALERT_HIGH_ENABLED] = settings.getBoolean("alert_high_enabled")
    if (settings.has("alert_urgent_low_enabled"))
        prefs[KEY_ALERT_URGENT_LOW_ENABLED] = settings.getBoolean("alert_urgent_low_enabled")
    if (settings.has("alert_urgent_high_enabled"))
        prefs[KEY_ALERT_URGENT_HIGH_ENABLED] = settings.getBoolean("alert_urgent_high_enabled")
    if (settings.has("alert_low")) prefs[KEY_ALERT_LOW] = importThreshold("alert_low")
    if (settings.has("alert_high")) prefs[KEY_ALERT_HIGH] = importThreshold("alert_high")
    if (settings.has("alert_urgent_low")) prefs[KEY_ALERT_URGENT_LOW] = importThreshold("alert_urgent_low")
    if (settings.has("alert_urgent_high")) prefs[KEY_ALERT_URGENT_HIGH] = importThreshold("alert_urgent_high")
    if (settings.has("alert_stale_enabled")) prefs[KEY_ALERT_STALE_ENABLED] = settings.getBoolean("alert_stale_enabled")
    if (settings.has("alert_low_soon_enabled")) prefs[KEY_ALERT_LOW_SOON_ENABLED] = settings.getBoolean("alert_low_soon_enabled")
    if (settings.has("alert_high_soon_enabled")) prefs[KEY_ALERT_HIGH_SOON_ENABLED] = settings.getBoolean("alert_high_soon_enabled")
    if (settings.has("pattern_alerts_enabled")) prefs[KEY_PATTERN_ALERTS_ENABLED] = settings.getBoolean("pattern_alerts_enabled")
    prefs.importExerciseAlerts(
        settings,
        isV1,
        inheritRegularEnablement = importVersion < SETTINGS_EXPORT_VERSION,
    )
    if (settings.has("alert_cooldown_minutes")) prefs[KEY_ALERT_COOLDOWN_MINUTES] = settings.getInt("alert_cooldown_minutes")
    if (settings.has("alert_snooze_duration")) {
        prefs[KEY_ALERT_SNOOZE_DURATION] =
            normalizedAlertSnoozeDurationName(settings.getString("alert_snooze_duration"))
    }
    if (settings.has("theme_mode")) prefs[KEY_THEME_MODE] = settings.getString("theme_mode")
    if (settings.has("notif_graph_minutes")) prefs[KEY_NOTIF_GRAPH_MINUTES] = settings.getInt("notif_graph_minutes")
    if (settings.has("notif_prediction_minutes")) prefs[KEY_NOTIF_PREDICTION_MINUTES] = settings.getInt("notif_prediction_minutes")
    if (settings.has("notif_action_type")) prefs[KEY_NOTIF_ACTION_TYPE] = settings.getString("notif_action_type")
    if (settings.has("notif_snooze_category")) prefs[KEY_NOTIF_SNOOZE_CATEGORY] = settings.getString("notif_snooze_category")
    if (settings.has("notif_snooze_duration")) prefs[KEY_NOTIF_SNOOZE_DURATION] = settings.getString("notif_snooze_duration")
    if (settings.has("glucose_unit")) prefs[KEY_GLUCOSE_UNIT] = settings.getString("glucose_unit")
    if (settings.has("hba1c_unit")) prefs[KEY_HBA1C_UNIT] = settings.getString("hba1c_unit")
    if (settings.has("bg_broadcast_enabled")) prefs[KEY_BG_BROADCAST_ENABLED] = settings.getBoolean("bg_broadcast_enabled")
    if (settings.has("glucose_source")) prefs[KEY_GLUCOSE_SOURCE] = settings.getString("glucose_source")
    if (settings.has("follower_poll_seconds")) prefs[KEY_FOLLOWER_POLL_SECONDS] = settings.getInt("follower_poll_seconds")
    if (settings.has("treatments_sync_enabled")) prefs[KEY_TREATMENTS_SYNC_ENABLED] = settings.getBoolean("treatments_sync_enabled")
    if (settings.has("insulin_type")) prefs[KEY_INSULIN_TYPE] = settings.getString("insulin_type")
    if (settings.has("custom_dia")) prefs[KEY_CUSTOM_DIA] = settings.getDouble("custom_dia").toFloat()
    if (settings.has("web_server_enabled")) prefs[KEY_WEB_SERVER_ENABLED] = settings.getBoolean("web_server_enabled")
    if (settings.has("start_on_boot")) prefs[KEY_START_ON_BOOT] = settings.getBoolean("start_on_boot")
    if (settings.has("hc_write_enabled")) prefs[KEY_HC_WRITE_ENABLED] = settings.getBoolean("hc_write_enabled")
}

private fun normalizedAlertSnoozeDurationName(raw: String?): String =
    try {
        SnoozeDuration.valueOf(raw ?: SnoozeDuration.M30.name).name
    } catch (_: IllegalArgumentException) {
        SnoozeDuration.M30.name
    }
