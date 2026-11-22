package com.psjostrom.strimma.data

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.psjostrom.strimma.data.notification.NotificationActionConfig
import com.psjostrom.strimma.data.notification.NotificationActionType
import com.psjostrom.strimma.data.notification.SnoozeCategory
import com.psjostrom.strimma.data.notification.SnoozeDuration
import com.psjostrom.strimma.data.workout.AlertProtocol
import com.psjostrom.strimma.data.workout.NightWindow
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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

internal object ExerciseAlertSettingsMigration : DataMigration<Preferences> {
    internal val KEY_INITIALIZED = booleanPreferencesKey("exercise_alerts_initialized")
    private val ENABLEMENT_KEYS = listOf(
        KEY_ALERT_LOW_ENABLED to KEY_EXERCISE_ALERT_LOW_ENABLED,
        KEY_ALERT_HIGH_ENABLED to KEY_EXERCISE_ALERT_HIGH_ENABLED,
        KEY_ALERT_URGENT_LOW_ENABLED to KEY_EXERCISE_ALERT_URGENT_LOW_ENABLED,
        KEY_ALERT_URGENT_HIGH_ENABLED to KEY_EXERCISE_ALERT_URGENT_HIGH_ENABLED,
        KEY_ALERT_LOW_SOON_ENABLED to KEY_EXERCISE_ALERT_LOW_SOON_ENABLED,
        KEY_ALERT_HIGH_SOON_ENABLED to KEY_EXERCISE_ALERT_HIGH_SOON_ENABLED,
        KEY_ALERT_STALE_ENABLED to KEY_EXERCISE_ALERT_STALE_ENABLED,
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        return KEY_INITIALIZED !in currentData.asMap()
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        if (KEY_INITIALIZED in currentData.asMap()) return currentData

        val mutable = currentData.toMutablePreferences()
        for ((regularKey, exerciseKey) in ENABLEMENT_KEYS) {
            mutable[exerciseKey] = currentData[regularKey] ?: true
        }
        mutable[KEY_INITIALIZED] = true
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() { /* One-shot migration */ }
}

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(SetupCompletedMigration, MgdlSettingsMigration, ExerciseAlertSettingsMigration) }
)

// Physical keys stay workout_* for persistence compatibility.
internal val KEY_EXERCISE_ALERT_LOW = floatPreferencesKey("workout_alert_low")
internal val KEY_EXERCISE_ALERT_HIGH = floatPreferencesKey("workout_alert_high")
internal val KEY_EXERCISE_ALERT_URGENT_LOW = floatPreferencesKey("workout_alert_urgent_low")
internal val KEY_EXERCISE_ALERT_URGENT_HIGH = floatPreferencesKey("workout_alert_urgent_high")
internal val KEY_EXERCISE_ALERT_LOW_ENABLED = booleanPreferencesKey("exercise_alert_low_enabled")
internal val KEY_EXERCISE_ALERT_HIGH_ENABLED = booleanPreferencesKey("exercise_alert_high_enabled")
internal val KEY_EXERCISE_ALERT_URGENT_LOW_ENABLED = booleanPreferencesKey("exercise_alert_urgent_low_enabled")
internal val KEY_EXERCISE_ALERT_URGENT_HIGH_ENABLED = booleanPreferencesKey("exercise_alert_urgent_high_enabled")
internal val KEY_EXERCISE_ALERT_LOW_SOON_ENABLED = booleanPreferencesKey("exercise_alert_low_soon_enabled")
internal val KEY_EXERCISE_ALERT_HIGH_SOON_ENABLED = booleanPreferencesKey("exercise_alert_high_soon_enabled")
internal val KEY_EXERCISE_ALERT_STALE_ENABLED = booleanPreferencesKey("exercise_alert_stale_enabled")
internal val KEY_ALERT_LOW_ENABLED = booleanPreferencesKey("alert_low_enabled")
internal val KEY_ALERT_HIGH_ENABLED = booleanPreferencesKey("alert_high_enabled")
internal val KEY_ALERT_URGENT_LOW_ENABLED = booleanPreferencesKey("alert_urgent_low_enabled")
internal val KEY_ALERT_URGENT_HIGH_ENABLED = booleanPreferencesKey("alert_urgent_high_enabled")
internal val KEY_ALERT_LOW_SOON_ENABLED = booleanPreferencesKey("alert_low_soon_enabled")
internal val KEY_ALERT_HIGH_SOON_ENABLED = booleanPreferencesKey("alert_high_soon_enabled")
internal val KEY_ALERT_STALE_ENABLED = booleanPreferencesKey("alert_stale_enabled")

private val EXERCISE_ALERT_THRESHOLD_FIELDS = listOf(
    "exercise_alert_urgent_low",
    "exercise_alert_low",
    "exercise_alert_high",
    "exercise_alert_urgent_high",
)

internal fun JSONObject.importThreshold(key: String, isV1: Boolean): Float {
    val value = getDouble(key).toFloat()
    return if (isV1) Math.round(value * GlucoseUnit.MGDL_FACTOR.toFloat()).toFloat() else value
}

// DataStore emits the whole Preferences object on every write to any key. Each
// setting's flow drops repeats of its own value, so a write to one key does not
// re-emit the others. Collectors that restart work on emission (data source,
// followers, treatment sync, web server) would otherwise restart on unrelated writes.
private fun <T> DataStore<Preferences>.prefFlow(read: (Preferences) -> T): Flow<T> =
    data.map(read).distinctUntilChanged()

private fun JSONObject.importExerciseAlertProtocol(isV1: Boolean, current: AlertProtocol): AlertProtocol {
    fun thresholdOr(key: String, fallback: Float): Float =
        if (has(key)) importThreshold(key, isV1) else fallback

    val imported = current.copy(
        urgentLowMgdl = thresholdOr("exercise_alert_urgent_low", current.urgentLowMgdl),
        lowMgdl = thresholdOr("exercise_alert_low", current.lowMgdl),
        highMgdl = thresholdOr("exercise_alert_high", current.highMgdl),
        urgentHighMgdl = thresholdOr("exercise_alert_urgent_high", current.urgentHighMgdl),
    )
    if (EXERCISE_ALERT_THRESHOLD_FIELDS.any(::has)) {
        require(imported.hasOrderedThresholds()) {
            "Exercise alert thresholds must be ordered urgent low, low, high, urgent high"
        }
    }
    return imported
}

private fun Preferences.exerciseAlertProtocol(): AlertProtocol = AlertProtocol(
    urgentLowEnabled = this[KEY_EXERCISE_ALERT_URGENT_LOW_ENABLED] ?: true,
    lowEnabled = this[KEY_EXERCISE_ALERT_LOW_ENABLED] ?: true,
    highEnabled = this[KEY_EXERCISE_ALERT_HIGH_ENABLED] ?: true,
    urgentHighEnabled = this[KEY_EXERCISE_ALERT_URGENT_HIGH_ENABLED] ?: true,
    urgentLowMgdl = this[KEY_EXERCISE_ALERT_URGENT_LOW]
        ?: SettingsRepository.DEFAULT_WORKOUT_ALERT_URGENT_LOW,
    lowMgdl = this[KEY_EXERCISE_ALERT_LOW] ?: SettingsRepository.DEFAULT_WORKOUT_ALERT_LOW,
    highMgdl = this[KEY_EXERCISE_ALERT_HIGH] ?: SettingsRepository.DEFAULT_WORKOUT_ALERT_HIGH,
    urgentHighMgdl = this[KEY_EXERCISE_ALERT_URGENT_HIGH]
        ?: SettingsRepository.DEFAULT_WORKOUT_ALERT_URGENT_HIGH,
    lowSoonEnabled = this[KEY_EXERCISE_ALERT_LOW_SOON_ENABLED] ?: true,
    highSoonEnabled = this[KEY_EXERCISE_ALERT_HIGH_SOON_ENABLED] ?: true,
    staleEnabled = this[KEY_EXERCISE_ALERT_STALE_ENABLED] ?: true,
)

private val EXERCISE_ALERT_BOOLEAN_FIELDS = listOf(
    Triple("exercise_alert_low_enabled", KEY_EXERCISE_ALERT_LOW_ENABLED, KEY_ALERT_LOW_ENABLED),
    Triple("exercise_alert_high_enabled", KEY_EXERCISE_ALERT_HIGH_ENABLED, KEY_ALERT_HIGH_ENABLED),
    Triple("exercise_alert_urgent_low_enabled", KEY_EXERCISE_ALERT_URGENT_LOW_ENABLED, KEY_ALERT_URGENT_LOW_ENABLED),
    Triple("exercise_alert_urgent_high_enabled", KEY_EXERCISE_ALERT_URGENT_HIGH_ENABLED, KEY_ALERT_URGENT_HIGH_ENABLED),
    Triple("exercise_alert_low_soon_enabled", KEY_EXERCISE_ALERT_LOW_SOON_ENABLED, KEY_ALERT_LOW_SOON_ENABLED),
    Triple("exercise_alert_high_soon_enabled", KEY_EXERCISE_ALERT_HIGH_SOON_ENABLED, KEY_ALERT_HIGH_SOON_ENABLED),
    Triple("exercise_alert_stale_enabled", KEY_EXERCISE_ALERT_STALE_ENABLED, KEY_ALERT_STALE_ENABLED),
)

internal fun MutablePreferences.importExerciseAlerts(
    settings: JSONObject,
    isV1: Boolean,
    inheritRegularEnablement: Boolean,
) {
    val protocol = settings.importExerciseAlertProtocol(isV1, exerciseAlertProtocol())
    EXERCISE_ALERT_BOOLEAN_FIELDS.forEach { (field, exerciseKey, regularKey) ->
        when {
            settings.has(field) -> this[exerciseKey] = settings.getBoolean(field)
            inheritRegularEnablement -> this[exerciseKey] = this[regularKey] ?: true
        }
    }
    if (settings.has("exercise_alert_low")) this[KEY_EXERCISE_ALERT_LOW] = protocol.lowMgdl
    if (settings.has("exercise_alert_high")) this[KEY_EXERCISE_ALERT_HIGH] = protocol.highMgdl
    if (settings.has("exercise_alert_urgent_low")) this[KEY_EXERCISE_ALERT_URGENT_LOW] = protocol.urgentLowMgdl
    if (settings.has("exercise_alert_urgent_high")) this[KEY_EXERCISE_ALERT_URGENT_HIGH] = protocol.urgentHighMgdl
    if (inheritRegularEnablement ||
        (EXERCISE_ALERT_BOOLEAN_FIELDS.map { it.first } + EXERCISE_ALERT_THRESHOLD_FIELDS).any(settings::has)
    ) {
        this[ExerciseAlertSettingsMigration.KEY_INITIALIZED] = true
    }
}

@Suppress("TooManyFunctions") // One getter+setter per setting
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val widgetSettingsRepository: WidgetSettingsRepository,
    private val dataStore: DataStore<Preferences>
) {
    private val _secretVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val secretVersion: kotlinx.coroutines.flow.StateFlow<Int> = _secretVersion

    private val _lluCredentialsVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val lluCredentialsVersion: kotlinx.coroutines.flow.StateFlow<Int> = _lluCredentialsVersion

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
        private const val KEY_NIGHTSCOUT_SECRET = "nightscout_secret"

        private const val SYNC_PREFS = "strimma_sync"
        private const val KEY_GLUCOSE_SOURCE_SYNC = "glucose_source"

        private const val KEY_LLU_EMAIL = "llu_email"
        private const val KEY_LLU_PASSWORD = "llu_password"

        internal const val DEFAULT_CUSTOM_DIA_HOURS = 5.0

        private const val KEY_WEB_SERVER_SECRET = "web_server_secret"
        private const val KEY_START_ON_BOOT_SYNC = "start_on_boot"

        // Workout BG targets stored in mg/dL, converted at display time via GlucoseUnit
        // Legacy keys — read by migrateLegacyTarget* for backward-compatible fallback

        // Exercise target keys (keyed by ExerciseCategory.name)
        private fun exerciseTargetLowKey(name: String) = floatPreferencesKey("exercise_target_low_$name")
        private fun exerciseTargetHighKey(name: String) = floatPreferencesKey("exercise_target_high_$name")

        // Defaults: 6.0 / 5.0 / 14.0 / 16.0 mmol → mg/dL via factor 18.0182
        const val DEFAULT_WORKOUT_ALERT_LOW = 108f
        const val DEFAULT_WORKOUT_ALERT_URGENT_LOW = 90f
        const val DEFAULT_WORKOUT_ALERT_HIGH = 252f
        const val DEFAULT_WORKOUT_ALERT_URGENT_HIGH = 288f

        // Manual safety timeout (1–12 hours)
        const val DEFAULT_WORKOUT_MODE_MAX_HOURS = 3

        // Runtime state (set by WorkoutModeManager). Sentinel 0L = absent.
        // Snapshotted at toggle time so a clock jump does not extend or invalidate
        // an in-progress session and so that a mid-session change to maxHours does
        // not retroactively shorten an existing manual workout.

        // Meal time slot boundaries (minutes from midnight)

        private const val DEFAULT_WORKOUT_LOOKAHEAD_HOURS = 3
        private const val DEFAULT_WORKOUT_TRIGGER_MINUTES = 120

        private const val KEY_TIDEPOOL_REFRESH_TOKEN = "tidepool_refresh_token"

        // Settings defaults (mg/dL)
        internal const val DEFAULT_GRAPH_WINDOW_HOURS = 4
        const val DEFAULT_BG_LOW = 72f
        const val DEFAULT_BG_HIGH = 180f
        const val DEFAULT_ALERT_LOW = 72f
        const val DEFAULT_ALERT_HIGH = 180f
        const val DEFAULT_ALERT_URGENT_LOW = 54f
        const val DEFAULT_ALERT_URGENT_HIGH = 234f
        const val DEFAULT_NOTIF_GRAPH_MINUTES = 60
        const val DEFAULT_PREDICTION_MINUTES = 15
        internal const val DEFAULT_FOLLOWER_POLL_SECONDS = 60
        private const val DEFAULT_CUSTOM_DIA_FLOAT = 5.0f
        internal const val DEFAULT_ALERT_COOLDOWN_MINUTES = 0
        internal const val SETTINGS_EXPORT_VERSION = 3
        const val COOLDOWN_MINUTES_MIN = 0
        const val COOLDOWN_MINUTES_MAX = 60
        internal const val MINUTES_PER_DAY = 1440
    }

    val nightscoutUrl: Flow<String> = dataStore.prefFlow { it[KEY_NIGHTSCOUT_URL] ?: "" }
    val graphWindowHours: Flow<Int> = dataStore.prefFlow { it[KEY_GRAPH_WINDOW_HOURS] ?: DEFAULT_GRAPH_WINDOW_HOURS }
    val bgLow: Flow<Float> = dataStore.prefFlow { it[KEY_BG_LOW] ?: DEFAULT_BG_LOW }
    val bgHigh: Flow<Float> = dataStore.prefFlow { it[KEY_BG_HIGH] ?: DEFAULT_BG_HIGH }

    val alertLowEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_ALERT_LOW_ENABLED] ?: true }
    val alertHighEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_ALERT_HIGH_ENABLED] ?: true }
    val alertUrgentLowEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_ALERT_URGENT_LOW_ENABLED] ?: true }
    val alertLow: Flow<Float> = dataStore.prefFlow { it[KEY_ALERT_LOW] ?: DEFAULT_ALERT_LOW }
    val alertHigh: Flow<Float> = dataStore.prefFlow { it[KEY_ALERT_HIGH] ?: DEFAULT_ALERT_HIGH }
    val alertUrgentLow: Flow<Float> = dataStore.prefFlow { it[KEY_ALERT_URGENT_LOW] ?: DEFAULT_ALERT_URGENT_LOW }
    val alertUrgentHighEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_ALERT_URGENT_HIGH_ENABLED] ?: true }
    val alertUrgentHigh: Flow<Float> = dataStore.prefFlow { it[KEY_ALERT_URGENT_HIGH] ?: DEFAULT_ALERT_URGENT_HIGH }
    val alertStaleEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_ALERT_STALE_ENABLED] ?: true }
    val alertLowSoonEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_ALERT_LOW_SOON_ENABLED] ?: true }
    val alertHighSoonEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_ALERT_HIGH_SOON_ENABLED] ?: true }

    val regularAlertProtocol: Flow<AlertProtocol> = dataStore.prefFlow { it.regularAlertProtocol() }

    private fun Preferences.regularAlertProtocol() = AlertProtocol(
        urgentLowEnabled = this[KEY_ALERT_URGENT_LOW_ENABLED] ?: true,
        lowEnabled = this[KEY_ALERT_LOW_ENABLED] ?: true,
        highEnabled = this[KEY_ALERT_HIGH_ENABLED] ?: true,
        urgentHighEnabled = this[KEY_ALERT_URGENT_HIGH_ENABLED] ?: true,
        urgentLowMgdl = this[KEY_ALERT_URGENT_LOW] ?: DEFAULT_ALERT_URGENT_LOW,
        lowMgdl = this[KEY_ALERT_LOW] ?: DEFAULT_ALERT_LOW,
        highMgdl = this[KEY_ALERT_HIGH] ?: DEFAULT_ALERT_HIGH,
        urgentHighMgdl = this[KEY_ALERT_URGENT_HIGH] ?: DEFAULT_ALERT_URGENT_HIGH,
        lowSoonEnabled = this[KEY_ALERT_LOW_SOON_ENABLED] ?: true,
        highSoonEnabled = this[KEY_ALERT_HIGH_SOON_ENABLED] ?: true,
        staleEnabled = this[KEY_ALERT_STALE_ENABLED] ?: true,
    )

    val alertCooldownMinutes: Flow<Int> = dataStore.prefFlow {
        (it[KEY_ALERT_COOLDOWN_MINUTES] ?: DEFAULT_ALERT_COOLDOWN_MINUTES)
            .coerceIn(COOLDOWN_MINUTES_MIN, COOLDOWN_MINUTES_MAX)
    }
    suspend fun setAlertCooldownMinutes(minutes: Int) {
        dataStore.edit { it[KEY_ALERT_COOLDOWN_MINUTES] = minutes.coerceIn(COOLDOWN_MINUTES_MIN, COOLDOWN_MINUTES_MAX) }
    }

    val alertSnoozeDuration: Flow<SnoozeDuration> = dataStore.prefFlow {
        try {
            SnoozeDuration.valueOf(it[KEY_ALERT_SNOOZE_DURATION] ?: SnoozeDuration.M30.name)
        } catch (_: Exception) {
            SnoozeDuration.M30
        }
    }
    suspend fun setAlertSnoozeDuration(duration: SnoozeDuration) {
        dataStore.edit { it[KEY_ALERT_SNOOZE_DURATION] = duration.name }
    }

    val patternAlertsEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_PATTERN_ALERTS_ENABLED] ?: true }
    suspend fun setPatternAlertsEnabled(enabled: Boolean) { dataStore.edit { it[KEY_PATTERN_ALERTS_ENABLED] = enabled } }

    /** When true, probable compression lows are shaded on the graph and left out of statistics. */
    val compressionLowDetection: Flow<Boolean> = dataStore.prefFlow { it[KEY_COMPRESSION_LOW_DETECTION] ?: true }
    suspend fun setCompressionLowDetection(enabled: Boolean) {
        dataStore.edit { it[KEY_COMPRESSION_LOW_DETECTION] = enabled }
    }

    val patternCardDismissedDate: Flow<String> = dataStore.prefFlow { it[KEY_PATTERN_CARD_DISMISSED_DATE] ?: "" }
    suspend fun setPatternCardDismissedDate(date: String) { dataStore.edit { it[KEY_PATTERN_CARD_DISMISSED_DATE] = date } }

    // --- Exercise alerts ---
    val exerciseAlertLowEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_EXERCISE_ALERT_LOW_ENABLED] ?: true }
    val exerciseAlertHighEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_EXERCISE_ALERT_HIGH_ENABLED] ?: true }
    val exerciseAlertUrgentLowEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_EXERCISE_ALERT_URGENT_LOW_ENABLED] ?: true }
    val exerciseAlertUrgentHighEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_EXERCISE_ALERT_URGENT_HIGH_ENABLED] ?: true }
    val exerciseAlertLowSoonEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_EXERCISE_ALERT_LOW_SOON_ENABLED] ?: true }
    val exerciseAlertHighSoonEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_EXERCISE_ALERT_HIGH_SOON_ENABLED] ?: true }
    val exerciseAlertStaleEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_EXERCISE_ALERT_STALE_ENABLED] ?: true }

    val exerciseAlertLow: Flow<Float> = dataStore.prefFlow { it[KEY_EXERCISE_ALERT_LOW] ?: DEFAULT_WORKOUT_ALERT_LOW }
    val exerciseAlertHigh: Flow<Float> = dataStore.prefFlow { it[KEY_EXERCISE_ALERT_HIGH] ?: DEFAULT_WORKOUT_ALERT_HIGH }
    val exerciseAlertUrgentLow: Flow<Float> = dataStore.prefFlow { it[KEY_EXERCISE_ALERT_URGENT_LOW] ?: DEFAULT_WORKOUT_ALERT_URGENT_LOW }
    val exerciseAlertUrgentHigh: Flow<Float> = dataStore.prefFlow {
        it[KEY_EXERCISE_ALERT_URGENT_HIGH] ?: DEFAULT_WORKOUT_ALERT_URGENT_HIGH
    }

    val exerciseAlertProtocol: Flow<AlertProtocol> = dataStore.prefFlow { it.exerciseAlertProtocol() }

    // --- Night alerts ---
    val nightAlertsEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_NIGHT_ALERTS_ENABLED] ?: false }
    val nightWindow: Flow<NightWindow> = dataStore.prefFlow { it.nightWindow() }
    val nightAlertProtocol: Flow<AlertProtocol> = dataStore.prefFlow { it.nightAlertProtocol() }

    // --- Workout safety timeout ---
    val workoutModeMaxHours: Flow<Int> = dataStore.prefFlow { it[KEY_WORKOUT_MODE_MAX_HOURS] ?: DEFAULT_WORKOUT_MODE_MAX_HOURS }
    suspend fun setWorkoutModeMaxHours(hours: Int) { dataStore.edit { it[KEY_WORKOUT_MODE_MAX_HOURS] = hours } }

    // --- Workout runtime state. Sentinel 0L = absent (mapped to null). ---
    val manualWorkoutSinceMs: Flow<Long?> = dataStore.prefFlow {
        it[KEY_MANUAL_WORKOUT_SINCE_MS]?.takeIf { v -> v != 0L }
    }
    val manualWorkoutExpiresMs: Flow<Long?> = dataStore.prefFlow {
        it[KEY_MANUAL_WORKOUT_EXPIRES_MS]?.takeIf { v -> v != 0L }
    }
    val manualOffOverrideUntilMs: Flow<Long?> = dataStore.prefFlow {
        it[KEY_MANUAL_OFF_OVERRIDE_UNTIL_MS]?.takeIf { v -> v != 0L }
    }

    /**
     * Atomic write of the manual-workout pair. Both fields live or die together.
     * Pass `null` for both to clear (auto-off / setManualOff). Pass non-null for both
     * to start a session — the call site (WorkoutModeManager.setManualOn) is the only
     * place that knows the correct expiresAt for the current settings snapshot.
     */
    suspend fun setManualWorkoutSession(sinceMs: Long?, expiresMs: Long?) {
        require((sinceMs == null) == (expiresMs == null)) {
            "manual workout sinceMs and expiresMs must be set/cleared together"
        }
        dataStore.edit {
            it[KEY_MANUAL_WORKOUT_SINCE_MS] = sinceMs ?: 0L
            it[KEY_MANUAL_WORKOUT_EXPIRES_MS] = expiresMs ?: 0L
        }
    }

    suspend fun setManualOffOverrideUntilMs(ms: Long?) {
        dataStore.edit { it[KEY_MANUAL_OFF_OVERRIDE_UNTIL_MS] = ms ?: 0L }
    }

    /**
     * Atomic cleanup: read all three runtime keys + maxHours inside a single edit,
     * decide expiry, and write null where appropriate — all without yielding to a
     * concurrent setManualWorkoutSession write that would otherwise be clobbered.
     *
     * Backward compat: a session persisted by an older app version will have
     * sinceMs but no expiresMs. Treat it as expiring at sinceMs + maxHours so the
     * upgrade path doesn't strand the user in a never-expiring session.
     */
    suspend fun cleanupExpiredWorkoutState(nowMs: Long, msPerHour: Long) {
        dataStore.edit { prefs ->
            val rawSince = prefs[KEY_MANUAL_WORKOUT_SINCE_MS]?.takeIf { it != 0L }
            val rawExpires = prefs[KEY_MANUAL_WORKOUT_EXPIRES_MS]?.takeIf { it != 0L }
            val maxHours = prefs[KEY_WORKOUT_MODE_MAX_HOURS] ?: DEFAULT_WORKOUT_MODE_MAX_HOURS
            val effectiveExpires = rawExpires ?: rawSince?.plus(maxHours * msPerHour)
            if (rawSince != null && effectiveExpires != null && nowMs >= effectiveExpires) {
                prefs[KEY_MANUAL_WORKOUT_SINCE_MS] = 0L
                prefs[KEY_MANUAL_WORKOUT_EXPIRES_MS] = 0L
            }

            val rawOverride = prefs[KEY_MANUAL_OFF_OVERRIDE_UNTIL_MS]?.takeIf { it != 0L }
            if (rawOverride != null && nowMs >= rawOverride) {
                prefs[KEY_MANUAL_OFF_OVERRIDE_UNTIL_MS] = 0L
            }
        }
    }

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

    suspend fun setExerciseAlertLowEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_EXERCISE_ALERT_LOW_ENABLED] = enabled }
    }
    suspend fun setExerciseAlertHighEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_EXERCISE_ALERT_HIGH_ENABLED] = enabled }
    }
    suspend fun setExerciseAlertUrgentLowEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_EXERCISE_ALERT_URGENT_LOW_ENABLED] = enabled }
    }
    suspend fun setExerciseAlertUrgentHighEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_EXERCISE_ALERT_URGENT_HIGH_ENABLED] = enabled }
    }
    suspend fun setExerciseAlertLowSoonEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_EXERCISE_ALERT_LOW_SOON_ENABLED] = enabled }
    }
    suspend fun setExerciseAlertHighSoonEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_EXERCISE_ALERT_HIGH_SOON_ENABLED] = enabled }
    }
    suspend fun setExerciseAlertStaleEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_EXERCISE_ALERT_STALE_ENABLED] = enabled }
    }
    suspend fun setExerciseAlertLow(mgdl: Float) { dataStore.edit { it[KEY_EXERCISE_ALERT_LOW] = mgdl } }
    suspend fun setExerciseAlertHigh(mgdl: Float) { dataStore.edit { it[KEY_EXERCISE_ALERT_HIGH] = mgdl } }
    suspend fun setExerciseAlertUrgentLow(mgdl: Float) { dataStore.edit { it[KEY_EXERCISE_ALERT_URGENT_LOW] = mgdl } }
    suspend fun setExerciseAlertUrgentHigh(mgdl: Float) { dataStore.edit { it[KEY_EXERCISE_ALERT_URGENT_HIGH] = mgdl } }

    // --- Night alert setters ---
    suspend fun setNightAlertsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            if (enabled) prefs.copyDayAlertsToNightOnce()
            prefs[KEY_NIGHT_ALERTS_ENABLED] = enabled
        }
    }

    // Turning night alerts on must not change any alert, so the first night set is a copy of the Day set.
    private fun MutablePreferences.copyDayAlertsToNightOnce() {
        if (KEY_NIGHT_ALERTS_INITIALIZED in this) return
        putNightAlertProtocol(regularAlertProtocol())
        this[KEY_NIGHT_ALERTS_INITIALIZED] = true
    }
    suspend fun setNightWindow(window: NightWindow) {
        dataStore.edit {
            it[KEY_NIGHT_ALERTS_START_MINUTE] = window.startMinute
            it[KEY_NIGHT_ALERTS_END_MINUTE] = window.endMinute
        }
    }
    suspend fun setNightAlertLowEnabled(enabled: Boolean) { dataStore.edit { it[KEY_NIGHT_ALERT_LOW_ENABLED] = enabled } }
    suspend fun setNightAlertHighEnabled(enabled: Boolean) { dataStore.edit { it[KEY_NIGHT_ALERT_HIGH_ENABLED] = enabled } }
    suspend fun setNightAlertUrgentLowEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NIGHT_ALERT_URGENT_LOW_ENABLED] = enabled }
    }
    suspend fun setNightAlertUrgentHighEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NIGHT_ALERT_URGENT_HIGH_ENABLED] = enabled }
    }
    suspend fun setNightAlertLowSoonEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NIGHT_ALERT_LOW_SOON_ENABLED] = enabled }
    }
    suspend fun setNightAlertHighSoonEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NIGHT_ALERT_HIGH_SOON_ENABLED] = enabled }
    }
    suspend fun setNightAlertStaleEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NIGHT_ALERT_STALE_ENABLED] = enabled }
    }
    suspend fun setNightAlertLow(mgdl: Float) { dataStore.edit { it[KEY_NIGHT_ALERT_LOW] = mgdl } }
    suspend fun setNightAlertHigh(mgdl: Float) { dataStore.edit { it[KEY_NIGHT_ALERT_HIGH] = mgdl } }
    suspend fun setNightAlertUrgentLow(mgdl: Float) { dataStore.edit { it[KEY_NIGHT_ALERT_URGENT_LOW] = mgdl } }
    suspend fun setNightAlertUrgentHigh(mgdl: Float) { dataStore.edit { it[KEY_NIGHT_ALERT_URGENT_HIGH] = mgdl } }

    val themeMode: Flow<ThemeMode> = dataStore.prefFlow {
        try { ThemeMode.valueOf(it[KEY_THEME_MODE] ?: "System") } catch (_: Exception) { ThemeMode.System }
    }
    suspend fun setThemeMode(mode: ThemeMode) { dataStore.edit { it[KEY_THEME_MODE] = mode.name } }

    val notifGraphMinutes: Flow<Int> = dataStore.prefFlow { it[KEY_NOTIF_GRAPH_MINUTES] ?: DEFAULT_NOTIF_GRAPH_MINUTES }
    suspend fun setNotifGraphMinutes(minutes: Int) { dataStore.edit { it[KEY_NOTIF_GRAPH_MINUTES] = minutes } }

    val predictionMinutes: Flow<Int> = dataStore.prefFlow { it[KEY_NOTIF_PREDICTION_MINUTES] ?: DEFAULT_PREDICTION_MINUTES }
    suspend fun setPredictionMinutes(minutes: Int) { dataStore.edit { it[KEY_NOTIF_PREDICTION_MINUTES] = minutes } }

    val notifActionType: Flow<NotificationActionType> = dataStore.prefFlow {
        try { NotificationActionType.valueOf(it[KEY_NOTIF_ACTION_TYPE] ?: NotificationActionConfig.DEFAULT.type.name) }
        catch (_: Exception) { NotificationActionConfig.DEFAULT.type }
    }
    suspend fun setNotifActionType(type: NotificationActionType) {
        dataStore.edit { it[KEY_NOTIF_ACTION_TYPE] = type.name }
    }

    val notifSnoozeCategory: Flow<SnoozeCategory> = dataStore.prefFlow {
        try { SnoozeCategory.valueOf(it[KEY_NOTIF_SNOOZE_CATEGORY] ?: NotificationActionConfig.DEFAULT.snoozeCategory.name) }
        catch (_: Exception) { NotificationActionConfig.DEFAULT.snoozeCategory }
    }
    suspend fun setNotifSnoozeCategory(category: SnoozeCategory) {
        dataStore.edit { it[KEY_NOTIF_SNOOZE_CATEGORY] = category.name }
    }

    val notifSnoozeDuration: Flow<SnoozeDuration> = dataStore.prefFlow {
        try { SnoozeDuration.valueOf(it[KEY_NOTIF_SNOOZE_DURATION] ?: NotificationActionConfig.DEFAULT.snoozeDuration.name) }
        catch (_: Exception) { NotificationActionConfig.DEFAULT.snoozeDuration }
    }
    suspend fun setNotifSnoozeDuration(duration: SnoozeDuration) {
        dataStore.edit { it[KEY_NOTIF_SNOOZE_DURATION] = duration.name }
    }

    val glucoseUnit: Flow<GlucoseUnit> = dataStore.prefFlow {
        try { GlucoseUnit.valueOf(it[KEY_GLUCOSE_UNIT] ?: "MMOL") } catch (_: Exception) { GlucoseUnit.MMOL }
    }
    suspend fun setGlucoseUnit(unit: GlucoseUnit) { dataStore.edit { it[KEY_GLUCOSE_UNIT] = unit.name } }

    val bgBroadcastEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_BG_BROADCAST_ENABLED] ?: false }
    suspend fun setBgBroadcastEnabled(enabled: Boolean) { dataStore.edit { it[KEY_BG_BROADCAST_ENABLED] = enabled } }

    val glucoseSource: Flow<GlucoseSource> = dataStore.prefFlow {
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

    val followerPollSeconds: Flow<Int> = dataStore.prefFlow { it[KEY_FOLLOWER_POLL_SECONDS] ?: DEFAULT_FOLLOWER_POLL_SECONDS }
    suspend fun setFollowerPollSeconds(seconds: Int) { dataStore.edit { it[KEY_FOLLOWER_POLL_SECONDS] = seconds } }

    fun getLluEmail(): String = encryptedPrefs.getString(KEY_LLU_EMAIL, "") ?: ""
    fun setLluEmail(email: String) = setLluCredential(KEY_LLU_EMAIL, email)

    fun getLluPassword(): String = encryptedPrefs.getString(KEY_LLU_PASSWORD, "") ?: ""
    fun setLluPassword(password: String) = setLluCredential(KEY_LLU_PASSWORD, password)

    private fun setLluCredential(key: String, value: String) {
        encryptedPrefs.edit { putString(key, value) }
        _lluCredentialsVersion.value++
    }

    val treatmentsSyncEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_TREATMENTS_SYNC_ENABLED] ?: false }
    suspend fun setTreatmentsSyncEnabled(enabled: Boolean) { dataStore.edit { it[KEY_TREATMENTS_SYNC_ENABLED] = enabled } }

    val insulinType: Flow<InsulinType> = dataStore.prefFlow {
        try { InsulinType.valueOf(it[KEY_INSULIN_TYPE] ?: "FIASP") } catch (_: Exception) { InsulinType.FIASP }
    }
    suspend fun setInsulinType(type: InsulinType) { dataStore.edit { it[KEY_INSULIN_TYPE] = type.name } }

    val retentionPolicy: Flow<RetentionPolicy> = dataStore.prefFlow { prefs ->
        val raw = prefs[KEY_RETENTION_POLICY]
        try {
            RetentionPolicy.valueOf(raw ?: "INDEFINITE")
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            // Most likely cause: app downgraded after the user picked a future-version
            // policy. Falling back to INDEFINITE silently *widens* retention (the safe
            // direction for medical data — never auto-delete more than the user asked
            // for) but it overrides the user's stated preference, so we leave a log
            // breadcrumb so a future investigation can see when this happened.
            DebugLog.log("Unknown retention policy '$raw', defaulting to INDEFINITE")
            RetentionPolicy.INDEFINITE
        }
    }
    suspend fun setRetentionPolicy(policy: RetentionPolicy) {
        dataStore.edit { it[KEY_RETENTION_POLICY] = policy.name }
    }

    val customDIA: Flow<Float> = dataStore.prefFlow { it[KEY_CUSTOM_DIA] ?: DEFAULT_CUSTOM_DIA_FLOAT }
    suspend fun setCustomDIA(hours: Float) { dataStore.edit { it[KEY_CUSTOM_DIA] = hours } }

    // Meal time slot boundaries (minutes from midnight)
    val mealBreakfastStart: Flow<Int> = dataStore.prefFlow { it[KEY_MEAL_BREAKFAST_START] ?: 360 }
    val mealBreakfastEnd: Flow<Int> = dataStore.prefFlow { it[KEY_MEAL_BREAKFAST_END] ?: 600 }
    val mealLunchStart: Flow<Int> = dataStore.prefFlow { it[KEY_MEAL_LUNCH_START] ?: 690 }
    val mealLunchEnd: Flow<Int> = dataStore.prefFlow { it[KEY_MEAL_LUNCH_END] ?: 870 }
    val mealDinnerStart: Flow<Int> = dataStore.prefFlow { it[KEY_MEAL_DINNER_START] ?: 1020 }
    val mealDinnerEnd: Flow<Int> = dataStore.prefFlow { it[KEY_MEAL_DINNER_END] ?: 1260 }

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

    val webServerEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_WEB_SERVER_ENABLED] ?: false }
    suspend fun setWebServerEnabled(enabled: Boolean) { dataStore.edit { it[KEY_WEB_SERVER_ENABLED] = enabled } }

    fun getWebServerSecret(): String = encryptedPrefs.getString(KEY_WEB_SERVER_SECRET, "") ?: ""
    fun setWebServerSecret(secret: String) {
        encryptedPrefs.edit { putString(KEY_WEB_SERVER_SECRET, secret) }
    }

    val startOnBoot: Flow<Boolean> = dataStore.prefFlow { it[KEY_START_ON_BOOT] ?: true }
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



    val hcWriteEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_HC_WRITE_ENABLED] ?: false }
    suspend fun setHcWriteEnabled(enabled: Boolean) { dataStore.edit { it[KEY_HC_WRITE_ENABLED] = enabled } }

    val hcLastSync: Flow<Long> = dataStore.prefFlow { it[KEY_HC_LAST_SYNC] ?: 0L }
    suspend fun setHcLastSync(timestamp: Long) { dataStore.edit { it[KEY_HC_LAST_SYNC] = timestamp } }

    suspend fun getHcChangesToken(): String? = dataStore.data.first()[KEY_HC_CHANGES_TOKEN]
    suspend fun setHcChangesToken(token: String?) {
        dataStore.edit {
            if (token != null) it[KEY_HC_CHANGES_TOKEN] = token
            else it.remove(KEY_HC_CHANGES_TOKEN)
        }
    }

    val setupCompleted: Flow<Boolean> = dataStore.prefFlow { it[KEY_SETUP_COMPLETED] ?: false }
    suspend fun setSetupCompleted(completed: Boolean) { dataStore.edit { it[KEY_SETUP_COMPLETED] = completed } }

    val setupStep: Flow<Int> = dataStore.prefFlow { it[KEY_SETUP_STEP] ?: 0 }
    suspend fun setSetupStep(step: Int) { dataStore.edit { it[KEY_SETUP_STEP] = step } }

    val storyViewedMonth: Flow<String> = dataStore.prefFlow { it[KEY_STORY_VIEWED_MONTH] ?: "" }

    /**
     * Marks [yearMonth] (in `YYYY-MM` format) as viewed, but only if it is later
     * than the currently-stored value. The compare runs inside a single atomic
     * `dataStore.edit` block so two concurrent writers can't interleave a
     * read-modify-write that would silently downgrade the marker.
     *
     * Monotonic semantics matter because `MainViewModel.storyReady` and
     * `MonthlyStoryEntry` both compare the marker against `lastMonthKey` —
     * downgrading would make the "Your X Story is ready" snackbar re-fire on
     * every restart even when the user has already viewed it. The unconditional
     * setter is intentionally absent: there must be no way to construct a
     * downgrade short of editing DataStore from outside the app.
     *
     * Lexicographic compare on `YYYY-MM` matches chronological order because
     * the format is fixed-width.
     */
    suspend fun setStoryViewedMonthIfLater(yearMonth: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_STORY_VIEWED_MONTH] ?: ""
            if (current.isEmpty() || yearMonth > current) {
                prefs[KEY_STORY_VIEWED_MONTH] = yearMonth
            }
        }
    }

    val hbA1cUnit: Flow<HbA1cUnit> = dataStore.prefFlow {
        try { HbA1cUnit.valueOf(it[KEY_HBA1C_UNIT] ?: "MMOL_MOL") } catch (_: Exception) { HbA1cUnit.MMOL_MOL }
    }
    suspend fun setHbA1cUnit(unit: HbA1cUnit) { dataStore.edit { it[KEY_HBA1C_UNIT] = unit.name } }

    // Tidepool
    val tidepoolEnabled: Flow<Boolean> = dataStore.prefFlow { it[KEY_TIDEPOOL_ENABLED] ?: false }
    suspend fun setTidepoolEnabled(enabled: Boolean) { dataStore.edit { it[KEY_TIDEPOOL_ENABLED] = enabled } }


    val tidepoolOnlyWhileCharging: Flow<Boolean> = dataStore.prefFlow { it[KEY_TIDEPOOL_ONLY_WHILE_CHARGING] ?: false }
    suspend fun setTidepoolOnlyWhileCharging(enabled: Boolean) { dataStore.edit { it[KEY_TIDEPOOL_ONLY_WHILE_CHARGING] = enabled } }

    val tidepoolOnlyWhileWifi: Flow<Boolean> = dataStore.prefFlow { it[KEY_TIDEPOOL_ONLY_WHILE_WIFI] ?: false }
    suspend fun setTidepoolOnlyWhileWifi(enabled: Boolean) { dataStore.edit { it[KEY_TIDEPOOL_ONLY_WHILE_WIFI] = enabled } }

    val tidepoolUserId: Flow<String> = dataStore.prefFlow { it[KEY_TIDEPOOL_USER_ID] ?: "" }
    suspend fun setTidepoolUserId(id: String) { dataStore.edit { it[KEY_TIDEPOOL_USER_ID] = id } }

    val tidepoolDatasetId: Flow<String> = dataStore.prefFlow { it[KEY_TIDEPOOL_DATASET_ID] ?: "" }
    suspend fun setTidepoolDatasetId(id: String) { dataStore.edit { it[KEY_TIDEPOOL_DATASET_ID] = id } }

    val tidepoolLastUploadEnd: Flow<Long> = dataStore.prefFlow { it[KEY_TIDEPOOL_LAST_UPLOAD_END] ?: 0L }
    suspend fun setTidepoolLastUploadEnd(ts: Long) { dataStore.edit { it[KEY_TIDEPOOL_LAST_UPLOAD_END] = ts } }

    val tidepoolLastUploadTime: Flow<Long> = dataStore.prefFlow { it[KEY_TIDEPOOL_LAST_UPLOAD_TIME] ?: 0L }
    suspend fun setTidepoolLastUploadTime(ts: Long) { dataStore.edit { it[KEY_TIDEPOOL_LAST_UPLOAD_TIME] = ts } }

    val tidepoolLastError: Flow<String> = dataStore.prefFlow { it[KEY_TIDEPOOL_LAST_ERROR] ?: "" }
    suspend fun setTidepoolLastError(error: String) { dataStore.edit { it[KEY_TIDEPOOL_LAST_ERROR] = error } }

    fun getTidepoolRefreshToken(): String = encryptedPrefs.getString(KEY_TIDEPOOL_REFRESH_TOKEN, "") ?: ""
    fun setTidepoolRefreshToken(token: String) {
        encryptedPrefs.edit { putString(KEY_TIDEPOOL_REFRESH_TOKEN, token) }
    }

    val workoutCalendarId: Flow<Long> = dataStore.prefFlow { it[KEY_WORKOUT_CALENDAR_ID] ?: -1L }
    suspend fun setWorkoutCalendarId(id: Long) { dataStore.edit { it[KEY_WORKOUT_CALENDAR_ID] = id } }

    val workoutCalendarName: Flow<String> = dataStore.prefFlow { it[KEY_WORKOUT_CALENDAR_NAME] ?: "" }
    suspend fun setWorkoutCalendarName(name: String) { dataStore.edit { it[KEY_WORKOUT_CALENDAR_NAME] = name } }

    val workoutLookaheadHours: Flow<Int> = dataStore.prefFlow {
        it[KEY_WORKOUT_LOOKAHEAD_HOURS] ?: DEFAULT_WORKOUT_LOOKAHEAD_HOURS
    }
    suspend fun setWorkoutLookaheadHours(hours: Int) { dataStore.edit { it[KEY_WORKOUT_LOOKAHEAD_HOURS] = hours } }

    val workoutTriggerMinutes: Flow<Int> = dataStore.prefFlow {
        it[KEY_WORKOUT_TRIGGER_MINUTES] ?: DEFAULT_WORKOUT_TRIGGER_MINUTES
    }
    suspend fun setWorkoutTriggerMinutes(minutes: Int) { dataStore.edit { it[KEY_WORKOUT_TRIGGER_MINUTES] = minutes } }

    val maxHeartRate: Flow<Int?> = dataStore.prefFlow { it[KEY_MAX_HEART_RATE] }
    suspend fun setMaxHeartRate(hr: Int?) {
        dataStore.edit {
            if (hr != null) it[KEY_MAX_HEART_RATE] = hr
            else it.remove(KEY_MAX_HEART_RATE)
        }
    }

    fun exerciseTargetLow(category: com.psjostrom.strimma.data.health.ExerciseCategory): Flow<Float> =
        dataStore.prefFlow { prefs ->
            prefs[exerciseTargetLowKey(category.name)]
                ?: migrateLegacyTargetLow(prefs, category)
                ?: category.defaultMetabolicProfile.defaultTargetLowMgdl
        }

    fun exerciseTargetHigh(category: com.psjostrom.strimma.data.health.ExerciseCategory): Flow<Float> =
        dataStore.prefFlow { prefs ->
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

    suspend fun exportToJson(): String {
        val prefs = dataStore.data.first()

        val settings = settingsToJson(prefs)

        val secrets = JSONObject().apply {
            put("nightscout_secret", getNightscoutSecret())
            put("web_server_secret", getWebServerSecret())
            put("llu_email", getLluEmail())
            put("llu_password", getLluPassword())
        }

        return JSONObject().apply {
            put("version", SETTINGS_EXPORT_VERSION)
            put("exported_at", java.time.Instant.now().toString())
            put("settings", settings)
            put("secrets", secrets)
            put("widget", widgetSettingsRepository.exportToJson())
        }.toString(2)
    }

    suspend fun importFromJson(json: String) {
        val root = JSONObject(json)
        val settings = root.getJSONObject("settings")
        val importVersion = root.optInt("version", 1)

        dataStore.edit { prefs ->
            applySettingsJson(prefs, settings, importVersion)
            prefs.importNightAlerts(settings) { prefs.copyDayAlertsToNightOnce() }
            if (prefs[KEY_NIGHT_ALERTS_ENABLED] == true) prefs.copyDayAlertsToNightOnce()

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

internal fun JSONObject.putExerciseAlertSettings(prefs: Preferences) {
    put("exercise_alert_low_enabled", prefs[KEY_EXERCISE_ALERT_LOW_ENABLED] ?: true)
    put("exercise_alert_high_enabled", prefs[KEY_EXERCISE_ALERT_HIGH_ENABLED] ?: true)
    put("exercise_alert_urgent_low_enabled", prefs[KEY_EXERCISE_ALERT_URGENT_LOW_ENABLED] ?: true)
    put("exercise_alert_urgent_high_enabled", prefs[KEY_EXERCISE_ALERT_URGENT_HIGH_ENABLED] ?: true)
    put("exercise_alert_low_soon_enabled", prefs[KEY_EXERCISE_ALERT_LOW_SOON_ENABLED] ?: true)
    put("exercise_alert_high_soon_enabled", prefs[KEY_EXERCISE_ALERT_HIGH_SOON_ENABLED] ?: true)
    put("exercise_alert_stale_enabled", prefs[KEY_EXERCISE_ALERT_STALE_ENABLED] ?: true)
    put(
        "exercise_alert_low",
        prefs[KEY_EXERCISE_ALERT_LOW]?.toDouble() ?: SettingsRepository.DEFAULT_WORKOUT_ALERT_LOW.toDouble()
    )
    put(
        "exercise_alert_high",
        prefs[KEY_EXERCISE_ALERT_HIGH]?.toDouble() ?: SettingsRepository.DEFAULT_WORKOUT_ALERT_HIGH.toDouble()
    )
    put(
        "exercise_alert_urgent_low",
        prefs[KEY_EXERCISE_ALERT_URGENT_LOW]?.toDouble() ?: SettingsRepository.DEFAULT_WORKOUT_ALERT_URGENT_LOW.toDouble()
    )
    put(
        "exercise_alert_urgent_high",
        prefs[KEY_EXERCISE_ALERT_URGENT_HIGH]?.toDouble() ?: SettingsRepository.DEFAULT_WORKOUT_ALERT_URGENT_HIGH.toDouble()
    )
}
