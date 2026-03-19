package com.psjostrom.strimma.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

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
    }

    val nightscoutUrl: Flow<String> = dataStore.data.map { it[KEY_NIGHTSCOUT_URL] ?: "" }
    val graphWindowHours: Flow<Int> = dataStore.data.map { it[KEY_GRAPH_WINDOW_HOURS] ?: 4 }
    val bgLow: Flow<Float> = dataStore.data.map { it[KEY_BG_LOW] ?: 4.0f }
    val bgHigh: Flow<Float> = dataStore.data.map { it[KEY_BG_HIGH] ?: 10.0f }

    val alertLowEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_LOW_ENABLED] ?: true }
    val alertHighEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_HIGH_ENABLED] ?: true }
    val alertUrgentLowEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_URGENT_LOW_ENABLED] ?: true }
    val alertLow: Flow<Float> = dataStore.data.map { it[KEY_ALERT_LOW] ?: 4.0f }
    val alertHigh: Flow<Float> = dataStore.data.map { it[KEY_ALERT_HIGH] ?: 10.0f }
    val alertUrgentLow: Flow<Float> = dataStore.data.map { it[KEY_ALERT_URGENT_LOW] ?: 3.0f }
    val alertUrgentHighEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_URGENT_HIGH_ENABLED] ?: true }
    val alertUrgentHigh: Flow<Float> = dataStore.data.map { it[KEY_ALERT_URGENT_HIGH] ?: 13.0f }
    val alertStaleEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_STALE_ENABLED] ?: true }

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

    val themeMode: Flow<String> = dataStore.data.map { it[KEY_THEME_MODE] ?: "System" }
    suspend fun setThemeMode(mode: String) { dataStore.edit { it[KEY_THEME_MODE] = mode } }

    val notifGraphMinutes: Flow<Int> = dataStore.data.map { it[KEY_NOTIF_GRAPH_MINUTES] ?: 60 }
    suspend fun setNotifGraphMinutes(minutes: Int) { dataStore.edit { it[KEY_NOTIF_GRAPH_MINUTES] = minutes } }

    val notifPredictionMinutes: Flow<Int> = dataStore.data.map { it[KEY_NOTIF_PREDICTION_MINUTES] ?: 10 }
    suspend fun setNotifPredictionMinutes(minutes: Int) { dataStore.edit { it[KEY_NOTIF_PREDICTION_MINUTES] = minutes } }

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

    val followerPollSeconds: Flow<Int> = dataStore.data.map { it[KEY_FOLLOWER_POLL_SECONDS] ?: 60 }
    suspend fun setFollowerPollSeconds(seconds: Int) { dataStore.edit { it[KEY_FOLLOWER_POLL_SECONDS] = seconds } }

    fun getFollowerSecret(): String = encryptedPrefs.getString(KEY_FOLLOWER_SECRET, "") ?: ""
    fun setFollowerSecret(secret: String) {
        encryptedPrefs.edit().putString(KEY_FOLLOWER_SECRET, secret).apply()
    }
}
