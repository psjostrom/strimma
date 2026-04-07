package com.psjostrom.strimma.data

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.psjostrom.strimma.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import androidx.datastore.core.DataMigration
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private object MgdlSettingsMigration : DataMigration<Preferences> {
    private val THRESHOLD_KEYS = listOf(
        floatPreferencesKey("bg_low"),
        floatPreferencesKey("bg_high"),
        floatPreferencesKey("alert_low"),
        floatPreferencesKey("alert_high"),
        floatPreferencesKey("alert_urgent_low"),
        floatPreferencesKey("alert_urgent_high")
    )
    private val KEY_VERSION = intPreferencesKey("settings_version")
    private val MGDL_FACTOR = GlucoseUnit.MGDL_FACTOR.toFloat()

    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        return (currentData[KEY_VERSION] ?: 0) < 2
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mutable = currentData.toMutablePreferences()
        for (key in THRESHOLD_KEYS) {
            currentData[key]?.let { mutable[key] = Math.round(it * MGDL_FACTOR).toFloat() }
        }
        mutable[KEY_VERSION] = 2
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() { /* Nothing to clean up — one-shot migration */ }
}

// Detects existing users by checking for keys that only they would have.
// Safe regardless of migration ordering — does not depend on map emptiness.
private object SetupCompletedMigration : DataMigration<Preferences> {
    private val KEY = booleanPreferencesKey("setup_completed")
    private val EXISTING_USER_KEYS = listOf(
        stringPreferencesKey("glucose_unit"),
        stringPreferencesKey("glucose_source"),
        stringPreferencesKey("nightscout_url")
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        return KEY !in currentData.asMap()
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mutable = currentData.toMutablePreferences()
        // Existing users (have app-specific settings) skip wizard; fresh installs see it
        mutable[KEY] = EXISTING_USER_KEYS.any { it in currentData.asMap() }
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() { /* One-shot migration */ }
}

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(SetupCompletedMigration, MgdlSettingsMigration) }
)

@Suppress("TooManyFunctions") // One getter+setter per setting
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val widgetSettingsRepository: WidgetSettingsRepository,
    private val dataStore: DataStore<Preferences>
) {
    private val _secretVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val secretVersion: kotlinx.coroutines.flow.StateFlow<Int> = _secretVersion

    private val _credentialsLost = kotlinx.coroutines.flow.MutableStateFlow(false)
    val credentialsLost: kotlinx.coroutines.flow.StateFlow<Boolean> = _credentialsLost

    fun clearCredentialsLostFlag() { _credentialsLost.value = false }

    @Suppress("TooGenericExceptionCaught") // Keystore corruption throws various exception types
    private val encryptedPrefs by lazy {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            com.psjostrom.strimma.receiver.DebugLog.log(
                "EncryptedSharedPreferences corrupted, recreating: ${e.javaClass.simpleName}"
            )
            context.deleteSharedPreferences(ENCRYPTED_PREFS_NAME)
            _credentialsLost.value = true
            try {
                createEncryptedPrefs()
            } catch (e2: Exception) {
                com.psjostrom.strimma.receiver.DebugLog.log(
                    "EncryptedSharedPreferences unrecoverable, falling back to plain: ${e2.javaClass.simpleName}"
                )
                context.getSharedPreferences(ENCRYPTED_PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    private fun createEncryptedPrefs(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context, ENCRYPTED_PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val ENCRYPTED_PREFS_NAME = "strimma_secrets"
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

        private val KEY_FOLLOWER_POLL_SECONDS = intPreferencesKey("follower_poll_seconds")

        private const val KEY_LLU_EMAIL = "llu_email"
        private const val KEY_LLU_PASSWORD = "llu_password"

        private val KEY_TREATMENTS_SYNC_ENABLED = booleanPreferencesKey("treatments_sync_enabled")
        private val KEY_INSULIN_TYPE = stringPreferencesKey("insulin_type")
        private val KEY_CUSTOM_DIA = floatPreferencesKey("custom_dia")
        private const val DEFAULT_CUSTOM_DIA_HOURS = 5.0

        private val KEY_WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        private const val KEY_WEB_SERVER_SECRET = "web_server_secret"
        private val KEY_HBA1C_UNIT = stringPreferencesKey("hba1c_unit")
        private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        private const val KEY_START_ON_BOOT_SYNC = "start_on_boot"


        private val KEY_HC_WRITE_ENABLED = booleanPreferencesKey("hc_write_enabled")
        private val KEY_HC_LAST_SYNC = longPreferencesKey("hc_last_sync")
        private val KEY_HC_CHANGES_TOKEN = stringPreferencesKey("hc_changes_token")

        private val KEY_SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        private val KEY_SETUP_STEP = intPreferencesKey("setup_step")

        // Workout BG targets stored in mg/dL, converted at display time via GlucoseUnit
        private val KEY_WORKOUT_CALENDAR_ID = longPreferencesKey("workout_calendar_id")
        private val KEY_WORKOUT_CALENDAR_NAME = stringPreferencesKey("workout_calendar_name")
        private val KEY_WORKOUT_LOOKAHEAD_HOURS = intPreferencesKey("workout_lookahead_hours")
        private val KEY_WORKOUT_TRIGGER_MINUTES = intPreferencesKey("workout_trigger_minutes")
        // Legacy keys — read by migrateLegacyTarget* for backward-compatible fallback
        private val KEY_WORKOUT_EASY_LOW = floatPreferencesKey("workout_easy_low")
        private val KEY_WORKOUT_EASY_HIGH = floatPreferencesKey("workout_easy_high")
        private val KEY_WORKOUT_STRENGTH_LOW = floatPreferencesKey("workout_strength_low")
        private val KEY_WORKOUT_STRENGTH_HIGH = floatPreferencesKey("workout_strength_high")

        // Exercise target keys (keyed by ExerciseCategory.name)
        private fun exerciseTargetLowKey(name: String) = floatPreferencesKey("exercise_target_low_$name")
        private fun exerciseTargetHighKey(name: String) = floatPreferencesKey("exercise_target_high_$name")
        private val KEY_MAX_HEART_RATE = intPreferencesKey("max_heart_rate")

        // Meal time slot boundaries (minutes from midnight)
        private val KEY_MEAL_BREAKFAST_START = intPreferencesKey("meal_breakfast_start")
        private val KEY_MEAL_BREAKFAST_END = intPreferencesKey("meal_breakfast_end")
        private val KEY_MEAL_LUNCH_START = intPreferencesKey("meal_lunch_start")
        private val KEY_MEAL_LUNCH_END = intPreferencesKey("meal_lunch_end")
        private val KEY_MEAL_DINNER_START = intPreferencesKey("meal_dinner_start")
        private val KEY_MEAL_DINNER_END = intPreferencesKey("meal_dinner_end")

        private const val DEFAULT_WORKOUT_LOOKAHEAD_HOURS = 3
        private const val DEFAULT_WORKOUT_TRIGGER_MINUTES = 120

        private val KEY_TIDEPOOL_ENABLED = booleanPreferencesKey("tidepool_enabled")
        private val KEY_TIDEPOOL_ENVIRONMENT = stringPreferencesKey("tidepool_environment")
        private val KEY_TIDEPOOL_ONLY_WHILE_CHARGING = booleanPreferencesKey("tidepool_only_while_charging")
        private val KEY_TIDEPOOL_ONLY_WHILE_WIFI = booleanPreferencesKey("tidepool_only_while_wifi")
        private val KEY_TIDEPOOL_USER_ID = stringPreferencesKey("tidepool_user_id")
        private val KEY_TIDEPOOL_DATASET_ID = stringPreferencesKey("tidepool_dataset_id")
        private val KEY_TIDEPOOL_LAST_UPLOAD_END = longPreferencesKey("tidepool_last_upload_end")
        private val KEY_TIDEPOOL_LAST_UPLOAD_TIME = longPreferencesKey("tidepool_last_upload_time")
        private val KEY_TIDEPOOL_LAST_ERROR = stringPreferencesKey("tidepool_last_error")
        private const val KEY_TIDEPOOL_REFRESH_TOKEN = "tidepool_refresh_token"

        // Settings defaults (mg/dL)
        private const val DEFAULT_GRAPH_WINDOW_HOURS = 4
        const val DEFAULT_BG_LOW = 72f
        const val DEFAULT_BG_HIGH = 180f
        private const val DEFAULT_ALERT_LOW = 72f
        private const val DEFAULT_ALERT_HIGH = 180f
        private const val DEFAULT_ALERT_URGENT_LOW = 54f
        private const val DEFAULT_ALERT_URGENT_HIGH = 234f
        const val DEFAULT_NOTIF_GRAPH_MINUTES = 60
        const val DEFAULT_PREDICTION_MINUTES = 15
        private const val DEFAULT_FOLLOWER_POLL_SECONDS = 60
        private const val DEFAULT_CUSTOM_DIA_FLOAT = 5.0f
        private const val MINUTES_PER_DAY = 1440
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

    suspend fun setNightscoutUrl(url: String) {
        dataStore.edit { it[KEY_NIGHTSCOUT_URL] = url.trim() }
    }
    suspend fun setGraphWindowHours(hours: Int) { dataStore.edit { it[KEY_GRAPH_WINDOW_HOURS] = hours } }
    suspend fun setBgLow(value: Float) { dataStore.edit { it[KEY_BG_LOW] = value } }
    suspend fun setBgHigh(value: Float) { dataStore.edit { it[KEY_BG_HIGH] = value } }

    fun getNightscoutSecret(): String = encryptedPrefs.getString(KEY_NIGHTSCOUT_SECRET, "") ?: ""
    fun setNightscoutSecret(secret: String) {
        encryptedPrefs.edit { putString(KEY_NIGHTSCOUT_SECRET, secret) }
        _secretVersion.value++
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

    val themeMode: Flow<ThemeMode> = dataStore.data.map {
        try { ThemeMode.valueOf(it[KEY_THEME_MODE] ?: "System") } catch (_: Exception) { ThemeMode.System }
    }
    suspend fun setThemeMode(mode: ThemeMode) { dataStore.edit { it[KEY_THEME_MODE] = mode.name } }

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
        dataStore.edit { prefs ->
            prefs[KEY_GLUCOSE_SOURCE] = source.name
            // Sync to SharedPreferences — prevents process-death desync between the two stores
            context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
                .edit { putString(KEY_GLUCOSE_SOURCE_SYNC, source.name) }
        }
    }
    fun getGlucoseSourceSync(): GlucoseSource {
        val name = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GLUCOSE_SOURCE_SYNC, null) ?: return GlucoseSource.COMPANION
        return try { GlucoseSource.valueOf(name) } catch (_: Exception) { GlucoseSource.COMPANION }
    }

    val followerPollSeconds: Flow<Int> = dataStore.data.map { it[KEY_FOLLOWER_POLL_SECONDS] ?: DEFAULT_FOLLOWER_POLL_SECONDS }
    suspend fun setFollowerPollSeconds(seconds: Int) { dataStore.edit { it[KEY_FOLLOWER_POLL_SECONDS] = seconds } }

    fun getLluEmail(): String = encryptedPrefs.getString(KEY_LLU_EMAIL, "") ?: ""
    fun setLluEmail(email: String) {
        encryptedPrefs.edit { putString(KEY_LLU_EMAIL, email) }
    }

    fun getLluPassword(): String = encryptedPrefs.getString(KEY_LLU_PASSWORD, "") ?: ""
    fun setLluPassword(password: String) {
        encryptedPrefs.edit { putString(KEY_LLU_PASSWORD, password) }
    }

    val treatmentsSyncEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_TREATMENTS_SYNC_ENABLED] ?: false }
    suspend fun setTreatmentsSyncEnabled(enabled: Boolean) { dataStore.edit { it[KEY_TREATMENTS_SYNC_ENABLED] = enabled } }

    val insulinType: Flow<InsulinType> = dataStore.data.map {
        try { InsulinType.valueOf(it[KEY_INSULIN_TYPE] ?: "FIASP") } catch (_: Exception) { InsulinType.FIASP }
    }
    suspend fun setInsulinType(type: InsulinType) { dataStore.edit { it[KEY_INSULIN_TYPE] = type.name } }

    val customDIA: Flow<Float> = dataStore.data.map { it[KEY_CUSTOM_DIA] ?: DEFAULT_CUSTOM_DIA_FLOAT }
    suspend fun setCustomDIA(hours: Float) { dataStore.edit { it[KEY_CUSTOM_DIA] = hours } }

    // Meal time slot boundaries (minutes from midnight)
    val mealBreakfastStart: Flow<Int> = dataStore.data.map { it[KEY_MEAL_BREAKFAST_START] ?: 360 }
    val mealBreakfastEnd: Flow<Int> = dataStore.data.map { it[KEY_MEAL_BREAKFAST_END] ?: 600 }
    val mealLunchStart: Flow<Int> = dataStore.data.map { it[KEY_MEAL_LUNCH_START] ?: 690 }
    val mealLunchEnd: Flow<Int> = dataStore.data.map { it[KEY_MEAL_LUNCH_END] ?: 870 }
    val mealDinnerStart: Flow<Int> = dataStore.data.map { it[KEY_MEAL_DINNER_START] ?: 1020 }
    val mealDinnerEnd: Flow<Int> = dataStore.data.map { it[KEY_MEAL_DINNER_END] ?: 1260 }

    suspend fun setMealSlotBoundary(key: String, minutes: Int) {
        if (minutes < 0 || minutes >= MINUTES_PER_DAY) return
        val prefKey = when (key) {
            "breakfast_start" -> KEY_MEAL_BREAKFAST_START
            "breakfast_end" -> KEY_MEAL_BREAKFAST_END
            "lunch_start" -> KEY_MEAL_LUNCH_START
            "lunch_end" -> KEY_MEAL_LUNCH_END
            "dinner_start" -> KEY_MEAL_DINNER_START
            "dinner_end" -> KEY_MEAL_DINNER_END
            else -> return
        }
        dataStore.edit { it[prefKey] = minutes }
    }

    val webServerEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_WEB_SERVER_ENABLED] ?: false }
    suspend fun setWebServerEnabled(enabled: Boolean) { dataStore.edit { it[KEY_WEB_SERVER_ENABLED] = enabled } }

    fun getWebServerSecret(): String = encryptedPrefs.getString(KEY_WEB_SERVER_SECRET, "") ?: ""
    fun setWebServerSecret(secret: String) {
        encryptedPrefs.edit { putString(KEY_WEB_SERVER_SECRET, secret) }
    }

    val startOnBoot: Flow<Boolean> = dataStore.data.map { it[KEY_START_ON_BOOT] ?: true }
    suspend fun setStartOnBoot(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_START_ON_BOOT] = enabled
            // Sync to SharedPreferences — prevents process-death desync between the two stores
            context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
                .edit { putBoolean(KEY_START_ON_BOOT_SYNC, enabled) }
        }
    }
    fun getStartOnBootSync(): Boolean {
        return context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_START_ON_BOOT_SYNC, true)
    }



    val hcWriteEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_HC_WRITE_ENABLED] ?: false }
    suspend fun setHcWriteEnabled(enabled: Boolean) { dataStore.edit { it[KEY_HC_WRITE_ENABLED] = enabled } }

    val hcLastSync: Flow<Long> = dataStore.data.map { it[KEY_HC_LAST_SYNC] ?: 0L }
    suspend fun setHcLastSync(timestamp: Long) { dataStore.edit { it[KEY_HC_LAST_SYNC] = timestamp } }

    suspend fun getHcChangesToken(): String? = dataStore.data.first()[KEY_HC_CHANGES_TOKEN]
    suspend fun setHcChangesToken(token: String?) {
        dataStore.edit {
            if (token != null) it[KEY_HC_CHANGES_TOKEN] = token
            else it.remove(KEY_HC_CHANGES_TOKEN)
        }
    }

    val setupCompleted: Flow<Boolean> = dataStore.data.map { it[KEY_SETUP_COMPLETED] ?: false }
    suspend fun setSetupCompleted(completed: Boolean) { dataStore.edit { it[KEY_SETUP_COMPLETED] = completed } }

    val setupStep: Flow<Int> = dataStore.data.map { it[KEY_SETUP_STEP] ?: 0 }
    suspend fun setSetupStep(step: Int) { dataStore.edit { it[KEY_SETUP_STEP] = step } }

    val hbA1cUnit: Flow<HbA1cUnit> = dataStore.data.map {
        try { HbA1cUnit.valueOf(it[KEY_HBA1C_UNIT] ?: "MMOL_MOL") } catch (_: Exception) { HbA1cUnit.MMOL_MOL }
    }
    suspend fun setHbA1cUnit(unit: HbA1cUnit) { dataStore.edit { it[KEY_HBA1C_UNIT] = unit.name } }

    // Tidepool
    val tidepoolEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_TIDEPOOL_ENABLED] ?: false }
    suspend fun setTidepoolEnabled(enabled: Boolean) { dataStore.edit { it[KEY_TIDEPOOL_ENABLED] = enabled } }

    val tidepoolEnvironment: Flow<String> = dataStore.data.map { it[KEY_TIDEPOOL_ENVIRONMENT] ?: "INTEGRATION" }
    suspend fun setTidepoolEnvironment(env: String) { dataStore.edit { it[KEY_TIDEPOOL_ENVIRONMENT] = env } }

    val tidepoolOnlyWhileCharging: Flow<Boolean> = dataStore.data.map { it[KEY_TIDEPOOL_ONLY_WHILE_CHARGING] ?: false }
    suspend fun setTidepoolOnlyWhileCharging(enabled: Boolean) { dataStore.edit { it[KEY_TIDEPOOL_ONLY_WHILE_CHARGING] = enabled } }

    val tidepoolOnlyWhileWifi: Flow<Boolean> = dataStore.data.map { it[KEY_TIDEPOOL_ONLY_WHILE_WIFI] ?: false }
    suspend fun setTidepoolOnlyWhileWifi(enabled: Boolean) { dataStore.edit { it[KEY_TIDEPOOL_ONLY_WHILE_WIFI] = enabled } }

    val tidepoolUserId: Flow<String> = dataStore.data.map { it[KEY_TIDEPOOL_USER_ID] ?: "" }
    suspend fun setTidepoolUserId(id: String) { dataStore.edit { it[KEY_TIDEPOOL_USER_ID] = id } }

    val tidepoolDatasetId: Flow<String> = dataStore.data.map { it[KEY_TIDEPOOL_DATASET_ID] ?: "" }
    suspend fun setTidepoolDatasetId(id: String) { dataStore.edit { it[KEY_TIDEPOOL_DATASET_ID] = id } }

    val tidepoolLastUploadEnd: Flow<Long> = dataStore.data.map { it[KEY_TIDEPOOL_LAST_UPLOAD_END] ?: 0L }
    suspend fun setTidepoolLastUploadEnd(ts: Long) { dataStore.edit { it[KEY_TIDEPOOL_LAST_UPLOAD_END] = ts } }

    val tidepoolLastUploadTime: Flow<Long> = dataStore.data.map { it[KEY_TIDEPOOL_LAST_UPLOAD_TIME] ?: 0L }
    suspend fun setTidepoolLastUploadTime(ts: Long) { dataStore.edit { it[KEY_TIDEPOOL_LAST_UPLOAD_TIME] = ts } }

    val tidepoolLastError: Flow<String> = dataStore.data.map { it[KEY_TIDEPOOL_LAST_ERROR] ?: "" }
    suspend fun setTidepoolLastError(error: String) { dataStore.edit { it[KEY_TIDEPOOL_LAST_ERROR] = error } }

    fun getTidepoolRefreshToken(): String = encryptedPrefs.getString(KEY_TIDEPOOL_REFRESH_TOKEN, "") ?: ""
    fun setTidepoolRefreshToken(token: String) {
        encryptedPrefs.edit { putString(KEY_TIDEPOOL_REFRESH_TOKEN, token) }
    }

    val workoutCalendarId: Flow<Long> = dataStore.data.map { it[KEY_WORKOUT_CALENDAR_ID] ?: -1L }
    suspend fun setWorkoutCalendarId(id: Long) { dataStore.edit { it[KEY_WORKOUT_CALENDAR_ID] = id } }

    val workoutCalendarName: Flow<String> = dataStore.data.map { it[KEY_WORKOUT_CALENDAR_NAME] ?: "" }
    suspend fun setWorkoutCalendarName(name: String) { dataStore.edit { it[KEY_WORKOUT_CALENDAR_NAME] = name } }

    val workoutLookaheadHours: Flow<Int> = dataStore.data.map {
        it[KEY_WORKOUT_LOOKAHEAD_HOURS] ?: DEFAULT_WORKOUT_LOOKAHEAD_HOURS
    }
    suspend fun setWorkoutLookaheadHours(hours: Int) { dataStore.edit { it[KEY_WORKOUT_LOOKAHEAD_HOURS] = hours } }

    val workoutTriggerMinutes: Flow<Int> = dataStore.data.map {
        it[KEY_WORKOUT_TRIGGER_MINUTES] ?: DEFAULT_WORKOUT_TRIGGER_MINUTES
    }
    suspend fun setWorkoutTriggerMinutes(minutes: Int) { dataStore.edit { it[KEY_WORKOUT_TRIGGER_MINUTES] = minutes } }

    val maxHeartRate: Flow<Int?> = dataStore.data.map { it[KEY_MAX_HEART_RATE] }
    suspend fun setMaxHeartRate(hr: Int?) {
        dataStore.edit {
            if (hr != null) it[KEY_MAX_HEART_RATE] = hr
            else it.remove(KEY_MAX_HEART_RATE)
        }
    }

    fun exerciseTargetLow(category: com.psjostrom.strimma.data.health.ExerciseCategory): Flow<Float> =
        dataStore.data.map { prefs ->
            prefs[exerciseTargetLowKey(category.name)]
                ?: migrateLegacyTargetLow(prefs, category)
                ?: category.defaultMetabolicProfile.defaultTargetLowMgdl
        }

    fun exerciseTargetHigh(category: com.psjostrom.strimma.data.health.ExerciseCategory): Flow<Float> =
        dataStore.data.map { prefs ->
            prefs[exerciseTargetHighKey(category.name)]
                ?: migrateLegacyTargetHigh(prefs, category)
                ?: category.defaultMetabolicProfile.defaultTargetHighMgdl
        }

    suspend fun setExerciseTarget(
        category: com.psjostrom.strimma.data.health.ExerciseCategory,
        low: Float,
        high: Float
    ) {
        dataStore.edit {
            it[exerciseTargetLowKey(category.name)] = low
            it[exerciseTargetHighKey(category.name)] = high
        }
    }

    private fun migrateLegacyTargetLow(
        prefs: androidx.datastore.preferences.core.Preferences,
        category: com.psjostrom.strimma.data.health.ExerciseCategory
    ): Float? = when (category) {
        com.psjostrom.strimma.data.health.ExerciseCategory.RUNNING ->
            prefs[KEY_WORKOUT_EASY_LOW]
        com.psjostrom.strimma.data.health.ExerciseCategory.STRENGTH ->
            prefs[KEY_WORKOUT_STRENGTH_LOW]
        else -> null
    }

    private fun migrateLegacyTargetHigh(
        prefs: androidx.datastore.preferences.core.Preferences,
        category: com.psjostrom.strimma.data.health.ExerciseCategory
    ): Float? = when (category) {
        com.psjostrom.strimma.data.health.ExerciseCategory.RUNNING ->
            prefs[KEY_WORKOUT_EASY_HIGH]
        com.psjostrom.strimma.data.health.ExerciseCategory.STRENGTH ->
            prefs[KEY_WORKOUT_STRENGTH_HIGH]
        else -> null
    }

    @Suppress("CyclomaticComplexMethod") // Flat serialization of all settings
    suspend fun exportToJson(): String {
        val prefs = dataStore.data.first()

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

        val secrets = JSONObject().apply {
            put("nightscout_secret", getNightscoutSecret())
            put("web_server_secret", getWebServerSecret())
            put("llu_email", getLluEmail())
            put("llu_password", getLluPassword())
        }

        return JSONObject().apply {
            put("version", 2)
            put("exported_at", java.time.Instant.now().toString())
            put("settings", settings)
            put("secrets", secrets)
            put("widget", widgetSettingsRepository.exportToJson())
        }.toString(2)
    }

    @Suppress("CyclomaticComplexMethod") // Flat deserialization of all settings
    suspend fun importFromJson(json: String) {
        val root = JSONObject(json)
        val settings = root.getJSONObject("settings")
        // v1 exports stored thresholds in mmol/L — convert to mg/dL on import
        val isV1 = root.optInt("version", 1) < 2
        fun importThreshold(key: String): Float {
            val value = settings.getDouble(key).toFloat()
            return if (isV1) Math.round(value * GlucoseUnit.MGDL_FACTOR.toFloat()).toFloat() else value
        }

        dataStore.edit { prefs ->
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
            if (settings.has("theme_mode")) prefs[KEY_THEME_MODE] = settings.getString("theme_mode")
            if (settings.has("notif_graph_minutes")) prefs[KEY_NOTIF_GRAPH_MINUTES] = settings.getInt("notif_graph_minutes")
            if (settings.has("notif_prediction_minutes")) prefs[KEY_NOTIF_PREDICTION_MINUTES] = settings.getInt("notif_prediction_minutes")
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

            // Sync to SharedPreferences atomically with DataStore edit
            val sourceName = settings.optString("glucose_source", "COMPANION")
            val bootEnabled = settings.optBoolean("start_on_boot", true)
            context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
                .edit {
                    putString(KEY_GLUCOSE_SOURCE_SYNC, sourceName)
                    putBoolean(KEY_START_ON_BOOT_SYNC, bootEnabled)
                }
        }

        if (root.has("secrets")) {
            val secrets = root.getJSONObject("secrets")
            if (secrets.has("nightscout_secret")) setNightscoutSecret(secrets.getString("nightscout_secret"))
            if (secrets.has("web_server_secret")) setWebServerSecret(secrets.getString("web_server_secret"))
            if (secrets.has("llu_email")) setLluEmail(secrets.getString("llu_email"))
            if (secrets.has("llu_password")) setLluPassword(secrets.getString("llu_password"))
        }

        if (root.has("widget")) {
            widgetSettingsRepository.importFromJson(root.getJSONObject("widget"))
        }
    }
}
