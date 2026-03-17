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
    }

    val springaUrl: Flow<String> = dataStore.data.map { it[KEY_SPRINGA_URL] ?: "" }
    val graphWindowHours: Flow<Int> = dataStore.data.map { it[KEY_GRAPH_WINDOW_HOURS] ?: 4 }
    val bgLow: Flow<Float> = dataStore.data.map { it[KEY_BG_LOW] ?: 4.0f }
    val bgHigh: Flow<Float> = dataStore.data.map { it[KEY_BG_HIGH] ?: 10.0f }

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
}
