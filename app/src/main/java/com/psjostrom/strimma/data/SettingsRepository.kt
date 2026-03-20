package com.psjostrom.strimma.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Suppress("TooManyFunctions") // One getter+setter per setting
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "strimma_secrets", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private val KEY_NIGHTSCOUT_URL = stringPreferencesKey("nightscout_url")
        private val KEY_GRAPH_WINDOW_HOURS = intPreferencesKey("graph_window_hours")
        private val KEY_BG_LOW = floatPreferencesKey("bg_low")
        private val KEY_BG_HIGH = floatPreferencesKey("bg_high")
        private const val KEY_NIGHTSCOUT_SECRET = "nightscout_secret"

        private val KEY_ALERT_LOW_ENABLED = booleanPreferencesKey("alert_low_enabled")
        private val KEY_ALERT_HIGH_ENABLED = booleanPreferencesKey("alert_high_enabled")
        private val KEY_ALERT_URGENT_LOW_ENABLED = booleanPreferencesKey("alert_urgent_low_enabled")
        private val KEY_ALERT_LOW = floatPreferencesKey("alert_low")
        private val KEY_ALERT_HIGH = floatPreferencesKey("alert_high")
        private val KEY_ALERT_URGENT_LOW = floatPreferencesKey("alert_urgent_low")
        private val KEY_ALERT_URGENT_HIGH_ENABLED = booleanPreferencesKey("alert_urgent_high_enabled")
        private val KEY_ALERT_URGENT_HIGH = floatPreferencesKey("alert_urgent_high")
        private val KEY_ALERT_STALE_ENABLED = booleanPreferencesKey("alert_stale_enabled")
        private val KEY_ALERT_LOW_SOON_ENABLED = booleanPreferencesKey("alert_low_soon_enabled")
        private val KEY_ALERT_HIGH_SOON_ENABLED = booleanPreferencesKey("alert_high_soon_enabled")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_NOTIF_GRAPH_MINUTES = intPreferencesKey("notif_graph_minutes")
        private val KEY_NOTIF_PREDICTION_MINUTES = intPreferencesKey("notif_prediction_minutes")
        private val KEY_GLUCOSE_UNIT = stringPreferencesKey("glucose_unit")
        private val KEY_BG_BROADCAST_ENABLED = booleanPreferencesKey("bg_broadcast_enabled")
        private val KEY_GLUCOSE_SOURCE = stringPreferencesKey("glucose_source")
        private const val SYNC_PREFS = "strimma_sync"
        private const val KEY_GLUCOSE_SOURCE_SYNC = "glucose_source"

        private val KEY_FOLLOWER_URL = stringPreferencesKey("follower_url")
        private val KEY_FOLLOWER_POLL_SECONDS = intPreferencesKey("follower_poll_seconds")
        private const val KEY_FOLLOWER_SECRET = "follower_secret"

        private val KEY_TREATMENTS_SYNC_ENABLED = booleanPreferencesKey("treatments_sync_enabled")
        private val KEY_INSULIN_TYPE = stringPreferencesKey("insulin_type")
        private val KEY_CUSTOM_DIA = floatPreferencesKey("custom_dia")
        private const val DEFAULT_CUSTOM_DIA_HOURS = 5.0

        private val KEY_WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        private const val KEY_WEB_SERVER_SECRET = "web_server_secret"

        // Settings defaults
        private const val DEFAULT_GRAPH_WINDOW_HOURS = 4
        private const val DEFAULT_BG_LOW = 4.0f
        private const val DEFAULT_BG_HIGH = 10.0f
        private const val DEFAULT_ALERT_LOW = 4.0f
        private const val DEFAULT_ALERT_HIGH = 10.0f
        private const val DEFAULT_ALERT_URGENT_LOW = 3.0f
        private const val DEFAULT_ALERT_URGENT_HIGH = 13.0f
        private const val DEFAULT_NOTIF_GRAPH_MINUTES = 60
        private const val DEFAULT_PREDICTION_MINUTES = 15
        private const val DEFAULT_FOLLOWER_POLL_SECONDS = 60
        private const val DEFAULT_CUSTOM_DIA_FLOAT = 5.0f
        private const val DEFAULT_WIDGET_OPACITY = 0.85f
        private const val DEFAULT_WIDGET_GRAPH_MINUTES = 120
        private const val DEFAULT_WIDGET_GRAPH_MINUTES_MAX = 180
        private const val DEFAULT_NOTIF_GRAPH_MINUTES_MAX = 30
    }

    val nightscoutUrl: Flow<String> = dataStore.data.map { it[KEY_NIGHTSCOUT_URL] ?: "" }
    val graphWindowHours: Flow<Int> = dataStore.data.map { it[KEY_GRAPH_WINDOW_HOURS] ?: DEFAULT_GRAPH_WINDOW_HOURS }
    val bgLow: Flow<Float> = dataStore.data.map { it[KEY_BG_LOW] ?: DEFAULT_BG_LOW }
    val bgHigh: Flow<Float> = dataStore.data.map { it[KEY_BG_HIGH] ?: DEFAULT_BG_HIGH }

    val alertLowEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_LOW_ENABLED] ?: true }
    val alertHighEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_HIGH_ENABLED] ?: true }
    val alertUrgentLowEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_URGENT_LOW_ENABLED] ?: true }
    val alertLow: Flow<Float> = dataStore.data.map { it[KEY_ALERT_LOW] ?: DEFAULT_ALERT_LOW }
    val alertHigh: Flow<Float> = dataStore.data.map { it[KEY_ALERT_HIGH] ?: DEFAULT_ALERT_HIGH }
    val alertUrgentLow: Flow<Float> = dataStore.data.map { it[KEY_ALERT_URGENT_LOW] ?: DEFAULT_ALERT_URGENT_LOW }
    val alertUrgentHighEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_URGENT_HIGH_ENABLED] ?: true }
    val alertUrgentHigh: Flow<Float> = dataStore.data.map { it[KEY_ALERT_URGENT_HIGH] ?: DEFAULT_ALERT_URGENT_HIGH }
    val alertStaleEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_STALE_ENABLED] ?: true }
    val alertLowSoonEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_LOW_SOON_ENABLED] ?: true }
    val alertHighSoonEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_HIGH_SOON_ENABLED] ?: true }

    suspend fun setNightscoutUrl(url: String) { dataStore.edit { it[KEY_NIGHTSCOUT_URL] = url } }
    suspend fun setGraphWindowHours(hours: Int) { dataStore.edit { it[KEY_GRAPH_WINDOW_HOURS] = hours } }
    suspend fun setBgLow(value: Float) { dataStore.edit { it[KEY_BG_LOW] = value } }
    suspend fun setBgHigh(value: Float) { dataStore.edit { it[KEY_BG_HIGH] = value } }

    fun getNightscoutSecret(): String = encryptedPrefs.getString(KEY_NIGHTSCOUT_SECRET, "") ?: ""
    fun setNightscoutSecret(secret: String) {
        encryptedPrefs.edit().putString(KEY_NIGHTSCOUT_SECRET, secret).apply()
    }

    suspend fun setAlertLowEnabled(enabled: Boolean) { dataStore.edit { it[KEY_ALERT_LOW_ENABLED] = enabled } }
    suspend fun setAlertHighEnabled(enabled: Boolean) { dataStore.edit { it[KEY_ALERT_HIGH_ENABLED] = enabled } }
    suspend fun setAlertUrgentLowEnabled(enabled: Boolean) { dataStore.edit { it[KEY_ALERT_URGENT_LOW_ENABLED] = enabled } }
    suspend fun setAlertLow(value: Float) { dataStore.edit { it[KEY_ALERT_LOW] = value } }
    suspend fun setAlertHigh(value: Float) { dataStore.edit { it[KEY_ALERT_HIGH] = value } }
    suspend fun setAlertUrgentLow(value: Float) { dataStore.edit { it[KEY_ALERT_URGENT_LOW] = value } }
    suspend fun setAlertUrgentHighEnabled(enabled: Boolean) { dataStore.edit { it[KEY_ALERT_URGENT_HIGH_ENABLED] = enabled } }
    suspend fun setAlertUrgentHigh(value: Float) { dataStore.edit { it[KEY_ALERT_URGENT_HIGH] = value } }
    suspend fun setAlertStaleEnabled(enabled: Boolean) { dataStore.edit { it[KEY_ALERT_STALE_ENABLED] = enabled } }
    suspend fun setAlertLowSoonEnabled(enabled: Boolean) { dataStore.edit { it[KEY_ALERT_LOW_SOON_ENABLED] = enabled } }
    suspend fun setAlertHighSoonEnabled(enabled: Boolean) { dataStore.edit { it[KEY_ALERT_HIGH_SOON_ENABLED] = enabled } }

    val themeMode: Flow<String> = dataStore.data.map { it[KEY_THEME_MODE] ?: "System" }
    suspend fun setThemeMode(mode: String) { dataStore.edit { it[KEY_THEME_MODE] = mode } }

    val notifGraphMinutes: Flow<Int> = dataStore.data.map { it[KEY_NOTIF_GRAPH_MINUTES] ?: DEFAULT_NOTIF_GRAPH_MINUTES }
    suspend fun setNotifGraphMinutes(minutes: Int) { dataStore.edit { it[KEY_NOTIF_GRAPH_MINUTES] = minutes } }

    val predictionMinutes: Flow<Int> = dataStore.data.map { it[KEY_NOTIF_PREDICTION_MINUTES] ?: DEFAULT_PREDICTION_MINUTES }
    suspend fun setPredictionMinutes(minutes: Int) { dataStore.edit { it[KEY_NOTIF_PREDICTION_MINUTES] = minutes } }

    val glucoseUnit: Flow<GlucoseUnit> = dataStore.data.map {
        try { GlucoseUnit.valueOf(it[KEY_GLUCOSE_UNIT] ?: "MMOL") } catch (_: Exception) { GlucoseUnit.MMOL }
    }
    suspend fun setGlucoseUnit(unit: GlucoseUnit) { dataStore.edit { it[KEY_GLUCOSE_UNIT] = unit.name } }

    val bgBroadcastEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_BG_BROADCAST_ENABLED] ?: false }
    suspend fun setBgBroadcastEnabled(enabled: Boolean) { dataStore.edit { it[KEY_BG_BROADCAST_ENABLED] = enabled } }

    val glucoseSource: Flow<GlucoseSource> = dataStore.data.map {
        try { GlucoseSource.valueOf(it[KEY_GLUCOSE_SOURCE] ?: "COMPANION") }
        catch (_: Exception) { GlucoseSource.COMPANION }
    }
    suspend fun setGlucoseSource(source: GlucoseSource) {
        dataStore.edit { it[KEY_GLUCOSE_SOURCE] = source.name }
        context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GLUCOSE_SOURCE_SYNC, source.name).apply()
    }
    fun getGlucoseSourceSync(): GlucoseSource {
        val name = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GLUCOSE_SOURCE_SYNC, null) ?: return GlucoseSource.COMPANION
        return try { GlucoseSource.valueOf(name) } catch (_: Exception) { GlucoseSource.COMPANION }
    }

    val followerUrl: Flow<String> = dataStore.data.map { it[KEY_FOLLOWER_URL] ?: "" }
    suspend fun setFollowerUrl(url: String) { dataStore.edit { it[KEY_FOLLOWER_URL] = url } }

    val followerPollSeconds: Flow<Int> = dataStore.data.map { it[KEY_FOLLOWER_POLL_SECONDS] ?: DEFAULT_FOLLOWER_POLL_SECONDS }
    suspend fun setFollowerPollSeconds(seconds: Int) { dataStore.edit { it[KEY_FOLLOWER_POLL_SECONDS] = seconds } }

    fun getFollowerSecret(): String = encryptedPrefs.getString(KEY_FOLLOWER_SECRET, "") ?: ""
    fun setFollowerSecret(secret: String) {
        encryptedPrefs.edit().putString(KEY_FOLLOWER_SECRET, secret).apply()
    }

    val treatmentsSyncEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_TREATMENTS_SYNC_ENABLED] ?: false }
    suspend fun setTreatmentsSyncEnabled(enabled: Boolean) { dataStore.edit { it[KEY_TREATMENTS_SYNC_ENABLED] = enabled } }

    val insulinType: Flow<InsulinType> = dataStore.data.map {
        try { InsulinType.valueOf(it[KEY_INSULIN_TYPE] ?: "FIASP") } catch (_: Exception) { InsulinType.FIASP }
    }
    suspend fun setInsulinType(type: InsulinType) { dataStore.edit { it[KEY_INSULIN_TYPE] = type.name } }

    val customDIA: Flow<Float> = dataStore.data.map { it[KEY_CUSTOM_DIA] ?: DEFAULT_CUSTOM_DIA_FLOAT }
    suspend fun setCustomDIA(hours: Float) { dataStore.edit { it[KEY_CUSTOM_DIA] = hours } }

    val webServerEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_WEB_SERVER_ENABLED] ?: false }
    suspend fun setWebServerEnabled(enabled: Boolean) { dataStore.edit { it[KEY_WEB_SERVER_ENABLED] = enabled } }

    fun getWebServerSecret(): String = encryptedPrefs.getString(KEY_WEB_SERVER_SECRET, "") ?: ""
    fun setWebServerSecret(secret: String) {
        encryptedPrefs.edit().putString(KEY_WEB_SERVER_SECRET, secret).apply()
    }


    @Suppress("CyclomaticComplexMethod") // Flat serialization of all settings
    suspend fun exportToJson(): String {
        val prefs = dataStore.data.first()
        val widgetPrefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)

        val settings = JSONObject().apply {
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
            put("theme_mode", prefs[KEY_THEME_MODE] ?: "System")
            put("notif_graph_minutes", prefs[KEY_NOTIF_GRAPH_MINUTES] ?: DEFAULT_NOTIF_GRAPH_MINUTES)
            put("notif_prediction_minutes", prefs[KEY_NOTIF_PREDICTION_MINUTES] ?: DEFAULT_PREDICTION_MINUTES)
            put("glucose_unit", prefs[KEY_GLUCOSE_UNIT] ?: "MMOL")
            put("bg_broadcast_enabled", prefs[KEY_BG_BROADCAST_ENABLED] ?: false)
            put("glucose_source", prefs[KEY_GLUCOSE_SOURCE] ?: "COMPANION")
            put("follower_url", prefs[KEY_FOLLOWER_URL] ?: "")
            put("follower_poll_seconds", prefs[KEY_FOLLOWER_POLL_SECONDS] ?: DEFAULT_FOLLOWER_POLL_SECONDS)
            put("treatments_sync_enabled", prefs[KEY_TREATMENTS_SYNC_ENABLED] ?: false)
            put("insulin_type", prefs[KEY_INSULIN_TYPE] ?: "FIASP")
            put("custom_dia", prefs[KEY_CUSTOM_DIA]?.toDouble() ?: DEFAULT_CUSTOM_DIA_HOURS)
            put("web_server_enabled", prefs[KEY_WEB_SERVER_ENABLED] ?: false)
        }

        val secrets = JSONObject().apply {
            put("nightscout_secret", getNightscoutSecret())
            put("follower_secret", getFollowerSecret())
            put("web_server_secret", getWebServerSecret())
        }

        val widget = JSONObject().apply {
            put("opacity", widgetPrefs.getFloat("opacity", DEFAULT_WIDGET_OPACITY).toDouble())
            put("graph_minutes", widgetPrefs.getInt("graph_minutes", DEFAULT_NOTIF_GRAPH_MINUTES))
            put("show_prediction", widgetPrefs.getBoolean("show_prediction", false))
        }

        return JSONObject().apply {
            put("version", 1)
            put("exported_at", java.time.Instant.now().toString())
            put("settings", settings)
            put("secrets", secrets)
            put("widget", widget)
        }.toString(2)
    }

    @Suppress("CyclomaticComplexMethod") // Flat deserialization of all settings
    suspend fun importFromJson(json: String) {
        val root = JSONObject(json)
        val settings = root.getJSONObject("settings")

        dataStore.edit { prefs ->
            if (settings.has("nightscout_url")) prefs[KEY_NIGHTSCOUT_URL] = settings.getString("nightscout_url")
            if (settings.has("graph_window_hours")) prefs[KEY_GRAPH_WINDOW_HOURS] = settings.getInt("graph_window_hours")
            if (settings.has("bg_low")) prefs[KEY_BG_LOW] = settings.getDouble("bg_low").toFloat()
            if (settings.has("bg_high")) prefs[KEY_BG_HIGH] = settings.getDouble("bg_high").toFloat()
            if (settings.has("alert_low_enabled")) prefs[KEY_ALERT_LOW_ENABLED] = settings.getBoolean("alert_low_enabled")
            if (settings.has("alert_high_enabled")) prefs[KEY_ALERT_HIGH_ENABLED] = settings.getBoolean("alert_high_enabled")
            if (settings.has("alert_urgent_low_enabled"))
                prefs[KEY_ALERT_URGENT_LOW_ENABLED] = settings.getBoolean("alert_urgent_low_enabled")
            if (settings.has("alert_urgent_high_enabled"))
                prefs[KEY_ALERT_URGENT_HIGH_ENABLED] = settings.getBoolean("alert_urgent_high_enabled")
            if (settings.has("alert_low")) prefs[KEY_ALERT_LOW] = settings.getDouble("alert_low").toFloat()
            if (settings.has("alert_high")) prefs[KEY_ALERT_HIGH] = settings.getDouble("alert_high").toFloat()
            if (settings.has("alert_urgent_low")) prefs[KEY_ALERT_URGENT_LOW] = settings.getDouble("alert_urgent_low").toFloat()
            if (settings.has("alert_urgent_high")) prefs[KEY_ALERT_URGENT_HIGH] = settings.getDouble("alert_urgent_high").toFloat()
            if (settings.has("alert_stale_enabled")) prefs[KEY_ALERT_STALE_ENABLED] = settings.getBoolean("alert_stale_enabled")
            if (settings.has("alert_low_soon_enabled")) prefs[KEY_ALERT_LOW_SOON_ENABLED] = settings.getBoolean("alert_low_soon_enabled")
            if (settings.has("alert_high_soon_enabled")) prefs[KEY_ALERT_HIGH_SOON_ENABLED] = settings.getBoolean("alert_high_soon_enabled")
            if (settings.has("theme_mode")) prefs[KEY_THEME_MODE] = settings.getString("theme_mode")
            if (settings.has("notif_graph_minutes")) prefs[KEY_NOTIF_GRAPH_MINUTES] = settings.getInt("notif_graph_minutes")
            if (settings.has("notif_prediction_minutes")) prefs[KEY_NOTIF_PREDICTION_MINUTES] = settings.getInt("notif_prediction_minutes")
            if (settings.has("glucose_unit")) prefs[KEY_GLUCOSE_UNIT] = settings.getString("glucose_unit")
            if (settings.has("bg_broadcast_enabled")) prefs[KEY_BG_BROADCAST_ENABLED] = settings.getBoolean("bg_broadcast_enabled")
            if (settings.has("glucose_source")) prefs[KEY_GLUCOSE_SOURCE] = settings.getString("glucose_source")
            if (settings.has("follower_url")) prefs[KEY_FOLLOWER_URL] = settings.getString("follower_url")
            if (settings.has("follower_poll_seconds")) prefs[KEY_FOLLOWER_POLL_SECONDS] = settings.getInt("follower_poll_seconds")
            if (settings.has("treatments_sync_enabled")) prefs[KEY_TREATMENTS_SYNC_ENABLED] = settings.getBoolean("treatments_sync_enabled")
            if (settings.has("insulin_type")) prefs[KEY_INSULIN_TYPE] = settings.getString("insulin_type")
            if (settings.has("custom_dia")) prefs[KEY_CUSTOM_DIA] = settings.getDouble("custom_dia").toFloat()
            if (settings.has("web_server_enabled")) prefs[KEY_WEB_SERVER_ENABLED] = settings.getBoolean("web_server_enabled")

            // Sync glucose source to SharedPreferences atomically with DataStore edit
            val sourceName = settings.optString("glucose_source", "COMPANION")
            context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_GLUCOSE_SOURCE_SYNC, sourceName).commit()
        }

        if (root.has("secrets")) {
            val secrets = root.getJSONObject("secrets")
            if (secrets.has("nightscout_secret")) setNightscoutSecret(secrets.getString("nightscout_secret"))
            if (secrets.has("follower_secret")) setFollowerSecret(secrets.getString("follower_secret"))
            if (secrets.has("web_server_secret")) setWebServerSecret(secrets.getString("web_server_secret"))
        }

        if (root.has("widget")) {
            val widget = root.getJSONObject("widget")
            val validGraphMinutes = setOf(
                DEFAULT_NOTIF_GRAPH_MINUTES_MAX, DEFAULT_NOTIF_GRAPH_MINUTES,
                DEFAULT_WIDGET_GRAPH_MINUTES, DEFAULT_WIDGET_GRAPH_MINUTES_MAX
            )
            context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE).edit().apply {
                if (widget.has("opacity")) putFloat("opacity", widget.getDouble("opacity").toFloat().coerceIn(0f, 1f))
                if (widget.has("graph_minutes")) {
                    val mins = widget.getInt("graph_minutes")
                    if (mins in validGraphMinutes) putInt("graph_minutes", mins)
                }
                if (widget.has("show_prediction")) putBoolean("show_prediction", widget.getBoolean("show_prediction"))
                apply()
            }
        }
    }
}
