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
        private val KEY_SPRINGA_URL = stringPreferencesKey("springa_url")
        private val KEY_GRAPH_WINDOW_HOURS = intPreferencesKey("graph_window_hours")
        private val KEY_BG_LOW = floatPreferencesKey("bg_low")
        private val KEY_BG_HIGH = floatPreferencesKey("bg_high")
        private const val KEY_API_SECRET = "api_secret"

        // Alert settings
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
    }

    val springaUrl: Flow<String> = dataStore.data.map { it[KEY_SPRINGA_URL] ?: "" }
    val graphWindowHours: Flow<Int> = dataStore.data.map { it[KEY_GRAPH_WINDOW_HOURS] ?: 4 }
    val bgLow: Flow<Float> = dataStore.data.map { it[KEY_BG_LOW] ?: 4.0f }
    val bgHigh: Flow<Float> = dataStore.data.map { it[KEY_BG_HIGH] ?: 10.0f }

    // Alert settings
    val alertLowEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_LOW_ENABLED] ?: true }
    val alertHighEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_HIGH_ENABLED] ?: true }
    val alertUrgentLowEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_URGENT_LOW_ENABLED] ?: true }
    val alertLow: Flow<Float> = dataStore.data.map { it[KEY_ALERT_LOW] ?: 4.0f }
    val alertHigh: Flow<Float> = dataStore.data.map { it[KEY_ALERT_HIGH] ?: 10.0f }
    val alertUrgentLow: Flow<Float> = dataStore.data.map { it[KEY_ALERT_URGENT_LOW] ?: 3.0f }
    val alertUrgentHighEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_URGENT_HIGH_ENABLED] ?: true }
    val alertUrgentHigh: Flow<Float> = dataStore.data.map { it[KEY_ALERT_URGENT_HIGH] ?: 13.0f }
    val alertStaleEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_ALERT_STALE_ENABLED] ?: true }

    suspend fun setSpringaUrl(url: String) {
        dataStore.edit { it[KEY_SPRINGA_URL] = url }
    }
    suspend fun setGraphWindowHours(hours: Int) {
        dataStore.edit { it[KEY_GRAPH_WINDOW_HOURS] = hours }
    }
    suspend fun setBgLow(value: Float) {
        dataStore.edit { it[KEY_BG_LOW] = value }
    }
    suspend fun setBgHigh(value: Float) {
        dataStore.edit { it[KEY_BG_HIGH] = value }
    }

    fun getApiSecret(): String = encryptedPrefs.getString(KEY_API_SECRET, "") ?: ""
    fun setApiSecret(secret: String) {
        encryptedPrefs.edit().putString(KEY_API_SECRET, secret).apply()
    }

    // Alert setters
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
}
