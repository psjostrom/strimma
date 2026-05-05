package com.psjostrom.strimma.notification

import android.app.NotificationChannel
import androidx.core.content.edit
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.workout.EffectiveThresholds
import com.psjostrom.strimma.data.workout.WorkoutMode
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import com.psjostrom.strimma.graph.CrossingType
import com.psjostrom.strimma.graph.Prediction
import com.psjostrom.strimma.graph.PredictionComputer
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Alert categories the user can pause. The notification IDs MUST match the
 * companion-object ALERT_*_ID constants in [AlertManager]. Encoding them on the enum
 * lets pause/cancel logic iterate categories without hand-rolled `when` blocks that
 * silently bypass new categories.
 */
enum class AlertCategory(
    val prefsKey: String,
    val levelKey: String,
    val urgentId: Int,
    val regularId: Int,
    val soonId: Int
) {
    LOW("pause_low", "pause_low_level", urgentId = 101, regularId = 100, soonId = 105),
    HIGH("pause_high", "pause_high_level", urgentId = 104, regularId = 102, soonId = 106)
}

@Suppress("TooManyFunctions") // Alert channels + management methods
@Singleton
class AlertManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val workoutModeManager: WorkoutModeManager,
) {
    companion object {
        // Each alert type has its own channel so the user can set a different sound per alarm
        const val CHANNEL_URGENT_LOW = "strimma_alert_urgent_low"
        const val CHANNEL_LOW = "strimma_alert_low"
        const val CHANNEL_HIGH = "strimma_alert_high"
        const val CHANNEL_URGENT_HIGH = "strimma_alert_urgent_high"
        const val CHANNEL_STALE = "strimma_alert_stale"
        const val CHANNEL_PUSH_FAIL = "strimma_alert_push_fail"
        const val CHANNEL_LOW_SOON = "strimma_alert_low_soon"
        const val CHANNEL_HIGH_SOON = "strimma_alert_high_soon"

        // Legacy channel — delete if it exists from previous version
        private const val LEGACY_CHANNEL = "strimma_alerts"

        // Category-related notification IDs — single source of truth is AlertCategory.
        // The companion exposes them as named properties for backward compatibility with
        // call sites and tests. Adding a new category just means adding it to the enum.
        val ALERT_URGENT_LOW_ID = AlertCategory.LOW.urgentId
        val ALERT_LOW_ID = AlertCategory.LOW.regularId
        val ALERT_LOW_SOON_ID = AlertCategory.LOW.soonId
        val ALERT_URGENT_HIGH_ID = AlertCategory.HIGH.urgentId
        val ALERT_HIGH_ID = AlertCategory.HIGH.regularId
        val ALERT_HIGH_SOON_ID = AlertCategory.HIGH.soonId

        // Non-category alerts keep their own const ids
        const val ALERT_STALE_ID = 103
        const val ALERT_PUSH_FAIL_ID = 107

        private const val SNOOZE_DURATION_MS = 30 * 60 * 1000L

        // Severity levels — snooze suppresses alerts at or below the snoozed level
        const val ALERT_LEVEL_SOON = 0     // predictive ("low in X min")
        const val ALERT_LEVEL_REGULAR = 1  // threshold crossed
        const val ALERT_LEVEL_URGENT = 2   // critical threshold crossed

        private const val MIN_CROSSING_MINUTES = 4

        // Vibration patterns (ms: [delay, vibrate, pause, vibrate, ...])
        private const val VIBRATE_LONG = 800L
        private const val VIBRATE_MEDIUM = 500L
        private const val VIBRATE_SHORT = 300L
        private const val VIBRATE_BRIEF = 200L

        // Notification IDs
        private const val SNOOZE_INTENT_ID_OFFSET = 200

        // Stale data threshold
        const val STALE_THRESHOLD_MINUTES = 10

        /**
         * Stale-sensor alerts are suppressed at the START of a workout because the
         * sensor often loses contact briefly when the user starts exercising (sweat,
         * arm motion). After this much elapsed time in a session, alerts re-arm even
         * while workout mode is still on — for a multi-hour event like a marathon
         * the user MUST hear about a sensor that died 30 min in, not "after the
         * workout ends in 5 hours". 30 min was picked as the longest plausible
         * "settle-in" window for a sensor.
         */
        const val STALE_SUPPRESSION_DURING_WORKOUT_MS = 30L * 60 * 1000

        fun isStale(lastReadingTs: Long?): Boolean {
            return lastReadingTs == null ||
                (System.currentTimeMillis() - lastReadingTs) > STALE_THRESHOLD_MINUTES * MS_PER_MINUTE
        }


        val ALL_CHANNELS = listOf(
            CHANNEL_URGENT_LOW, CHANNEL_LOW, CHANNEL_HIGH, CHANNEL_URGENT_HIGH,
            CHANNEL_STALE, CHANNEL_PUSH_FAIL, CHANNEL_LOW_SOON, CHANNEL_HIGH_SOON
        )

        // --- Category-level pause (static methods for testability) ---

        fun pauseCategoryAt(
            prefs: android.content.SharedPreferences,
            category: AlertCategory,
            expiryMs: Long,
            level: Int = ALERT_LEVEL_URGENT
        ) {
            prefs.edit {
                putLong(category.prefsKey, expiryMs)
                putInt(category.levelKey, level)
            }
            DebugLog.log("Category ${category.name} paused at level $level until $expiryMs")
        }

        fun cancelPause(prefs: android.content.SharedPreferences, category: AlertCategory) {
            prefs.edit {
                remove(category.prefsKey)
                remove(category.levelKey)
            }
        }

        fun isCategoryPaused(prefs: android.content.SharedPreferences, category: AlertCategory): Boolean {
            val expiryMs = prefs.getLong(category.prefsKey, 0L)
            if (expiryMs == 0L) return false

            val now = System.currentTimeMillis()
            if (now >= expiryMs) {
                prefs.edit {
                    remove(category.prefsKey)
                    remove(category.levelKey)
                }
                return false
            }
            return true
        }

        fun isCategoryPausedAtLevel(
            prefs: android.content.SharedPreferences,
            category: AlertCategory,
            alertLevel: Int
        ): Boolean {
            val expiryMs = prefs.getLong(category.prefsKey, 0L)
            if (expiryMs == 0L) return false

            val now = System.currentTimeMillis()
            if (now >= expiryMs) {
                prefs.edit {
                    remove(category.prefsKey)
                    remove(category.levelKey)
                }
                return false
            }
            val pausedLevel = prefs.getInt(category.levelKey, ALERT_LEVEL_URGENT)
            return alertLevel <= pausedLevel
        }

        fun pauseExpiryMs(prefs: android.content.SharedPreferences, category: AlertCategory): Long? {
            val expiryMs = prefs.getLong(category.prefsKey, 0L)
            if (expiryMs == 0L) return null

            val now = System.currentTimeMillis()
            if (now >= expiryMs) {
                prefs.edit {
                    remove(category.prefsKey)
                    remove(category.levelKey)
                }
                return null
            }
            return expiryMs
        }
    }

    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val snoozePrefs = context.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE)

    private val alarmAudioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val notifAudioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    @Suppress("LongMethod") // Sequential channel registrations — splitting would hurt readability
    fun createChannels() {
        // Remove legacy single channel
        notificationManager.deleteNotificationChannel(LEGACY_CHANNEL)

        createChannel(
            CHANNEL_URGENT_LOW, context.getString(R.string.alert_channel_urgent_low),
            context.getString(R.string.alert_channel_urgent_low_desc),
            NotificationManager.IMPORTANCE_HIGH,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            alarmAudioAttrs, bypassDnd = true,
            vibration = longArrayOf(0, VIBRATE_LONG, VIBRATE_BRIEF, VIBRATE_LONG, VIBRATE_BRIEF, VIBRATE_LONG)
        )
        createChannel(
            CHANNEL_LOW, context.getString(R.string.alert_channel_low),
            context.getString(R.string.alert_channel_low_desc),
            NotificationManager.IMPORTANCE_HIGH,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            notifAudioAttrs, bypassDnd = false,
            vibration = longArrayOf(0, VIBRATE_MEDIUM, VIBRATE_BRIEF, VIBRATE_MEDIUM)
        )
        createChannel(
            CHANNEL_HIGH, context.getString(R.string.alert_channel_high),
            context.getString(R.string.alert_channel_high_desc),
            NotificationManager.IMPORTANCE_HIGH,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            notifAudioAttrs, bypassDnd = false,
            vibration = longArrayOf(0, VIBRATE_SHORT, VIBRATE_BRIEF, VIBRATE_SHORT)
        )
        createChannel(
            CHANNEL_URGENT_HIGH, context.getString(R.string.alert_channel_urgent_high),
            context.getString(R.string.alert_channel_urgent_high_desc),
            NotificationManager.IMPORTANCE_HIGH,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            alarmAudioAttrs, bypassDnd = true,
            vibration = longArrayOf(0, VIBRATE_LONG, VIBRATE_BRIEF, VIBRATE_LONG, VIBRATE_BRIEF, VIBRATE_LONG)
        )
        createChannel(
            CHANNEL_STALE, context.getString(R.string.alert_channel_stale),
            context.getString(R.string.alert_channel_stale_desc),
            NotificationManager.IMPORTANCE_DEFAULT,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            notifAudioAttrs, bypassDnd = false,
            vibration = longArrayOf(0, VIBRATE_BRIEF, VIBRATE_BRIEF, VIBRATE_BRIEF)
        )
        createChannel(
            CHANNEL_PUSH_FAIL, context.getString(R.string.alert_channel_push_fail),
            context.getString(R.string.alert_channel_push_fail_desc),
            NotificationManager.IMPORTANCE_HIGH,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            notifAudioAttrs, bypassDnd = false,
            vibration = longArrayOf(0, VIBRATE_MEDIUM, VIBRATE_BRIEF, VIBRATE_MEDIUM)
        )
        createChannel(
            CHANNEL_LOW_SOON, context.getString(R.string.alert_channel_low_soon),
            context.getString(R.string.alert_channel_low_soon_desc),
            NotificationManager.IMPORTANCE_DEFAULT,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            notifAudioAttrs, bypassDnd = false,
            vibration = longArrayOf(0, VIBRATE_BRIEF, VIBRATE_BRIEF, VIBRATE_BRIEF)
        )
        createChannel(
            CHANNEL_HIGH_SOON, context.getString(R.string.alert_channel_high_soon),
            context.getString(R.string.alert_channel_high_soon_desc),
            NotificationManager.IMPORTANCE_DEFAULT,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            notifAudioAttrs, bypassDnd = false,
            vibration = longArrayOf(0, VIBRATE_BRIEF, VIBRATE_BRIEF, VIBRATE_BRIEF)
        )
    }

    @Suppress("LongParameterList") // Wraps NotificationChannel which needs all of these
    private fun createChannel(
        id: String, name: String, desc: String,
        importance: Int, sound: android.net.Uri,
        audioAttrs: AudioAttributes, bypassDnd: Boolean,
        vibration: LongArray
    ) {
        val channel = NotificationChannel(id, name, importance).apply {
            description = desc
            enableVibration(true)
            vibrationPattern = vibration
            setSound(sound, audioAttrs)
            setBypassDnd(bypassDnd)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun openChannelSettings(channelId: String) {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    suspend fun checkReading(
        reading: GlucoseReading,
        recentReadings: List<GlucoseReading>,
        predictionMinutes: Int,
        prediction: Prediction? = null
    ) {
        val mgdl = reading.sgv.toDouble()
        val unit = settings.glucoseUnit.first()

        // Single suspending read of the threshold set used for this entire reading
        // cycle. Suspends until the manager has loaded real values (skips the
        // placeholder seed) so a cold-start race can never silently use stale
        // defaults for a customized user.
        val effective = workoutModeManager.currentEffectiveThresholds()
        val workoutOn = workoutModeManager.state.value is WorkoutMode.On
        val lowThreshold = effective.alertLowMgdl
        val highThreshold = effective.alertHighMgdl

        val alreadyLow = checkLowAlerts(mgdl, unit, effective, workoutOn)
        val alreadyHigh = checkHighAlerts(mgdl, unit, effective, workoutOn)

        checkPredictive(recentReadings, predictionMinutes, lowThreshold.toDouble(),
            highThreshold.toDouble(), alreadyLow, alreadyHigh, unit, prediction)
    }

    private suspend fun checkLowAlerts(
        mgdl: Double,
        unit: GlucoseUnit,
        effective: EffectiveThresholds,
        workoutOn: Boolean
    ): Boolean {
        val urgentLowEnabled = settings.alertUrgentLowEnabled.first()
        val urgentLowThreshold = effective.alertUrgentLowMgdl
        val lowEnabled = settings.alertLowEnabled.first()
        val lowThreshold = effective.alertLowMgdl

        if (urgentLowEnabled && mgdl <= urgentLowThreshold) {
            if (!isCategoryPausedAtLevel(snoozePrefs, AlertCategory.LOW, ALERT_LEVEL_URGENT)) {
                val title = workoutPrefixedTitle(R.string.alert_urgent_low_title, workoutOn)
                fireAlert(ALERT_URGENT_LOW_ID, CHANNEL_URGENT_LOW, title, unit.formatWithUnit(mgdl))
            } else {
                notificationManager.cancel(ALERT_URGENT_LOW_ID)
            }
            notificationManager.cancel(ALERT_LOW_ID)
            return true
        }

        if (lowEnabled && mgdl < lowThreshold) {
            if (!isCategoryPausedAtLevel(snoozePrefs, AlertCategory.LOW, ALERT_LEVEL_REGULAR)) {
                val title = workoutPrefixedTitle(R.string.alert_low_title, workoutOn)
                fireAlert(ALERT_LOW_ID, CHANNEL_LOW, title, unit.formatWithUnit(mgdl))
            } else {
                notificationManager.cancel(ALERT_LOW_ID)
            }
            notificationManager.cancel(ALERT_URGENT_LOW_ID)
            return true
        }

        notificationManager.cancel(ALERT_LOW_ID)
        notificationManager.cancel(ALERT_URGENT_LOW_ID)
        return false
    }

    private suspend fun checkHighAlerts(
        mgdl: Double,
        unit: GlucoseUnit,
        effective: EffectiveThresholds,
        workoutOn: Boolean
    ): Boolean {
        val urgentHighEnabled = settings.alertUrgentHighEnabled.first()
        val urgentHighThreshold = effective.alertUrgentHighMgdl
        val highEnabled = settings.alertHighEnabled.first()
        val highThreshold = effective.alertHighMgdl

        if (urgentHighEnabled && mgdl >= urgentHighThreshold) {
            if (!isCategoryPausedAtLevel(snoozePrefs, AlertCategory.HIGH, ALERT_LEVEL_URGENT)) {
                val title = workoutPrefixedTitle(R.string.alert_urgent_high_title, workoutOn)
                fireAlert(ALERT_URGENT_HIGH_ID, CHANNEL_URGENT_HIGH, title, unit.formatWithUnit(mgdl))
            } else {
                notificationManager.cancel(ALERT_URGENT_HIGH_ID)
            }
            notificationManager.cancel(ALERT_HIGH_ID)
            return true
        }

        if (highEnabled && mgdl > highThreshold) {
            if (!isCategoryPausedAtLevel(snoozePrefs, AlertCategory.HIGH, ALERT_LEVEL_REGULAR)) {
                val title = workoutPrefixedTitle(R.string.alert_high_title, workoutOn)
                fireAlert(ALERT_HIGH_ID, CHANNEL_HIGH, title, unit.formatWithUnit(mgdl))
            } else {
                notificationManager.cancel(ALERT_HIGH_ID)
            }
            notificationManager.cancel(ALERT_URGENT_HIGH_ID)
            return true
        }

        notificationManager.cancel(ALERT_HIGH_ID)
        notificationManager.cancel(ALERT_URGENT_HIGH_ID)
        return false
    }

    /**
     * Workout mode replaces alert thresholds with a more conservative set, so a
     * reading of e.g. 95 mg/dL fires URGENT LOW under workout mode (urgent_low=90)
     * even though the same reading is in-range during normal life. Prefixing the
     * title disambiguates the severity for the user — without this they may stop
     * trusting the URGENT label.
     */
    private fun workoutPrefixedTitle(@androidx.annotation.StringRes baseTitleRes: Int, workoutOn: Boolean): String {
        val base = context.getString(baseTitleRes)
        return if (workoutOn) context.getString(R.string.alert_workout_prefix, base) else base
    }

    @Suppress("CyclomaticComplexMethod", "LongParameterList") // Symmetric low/high blocks
    private suspend fun checkPredictive(
        recentReadings: List<GlucoseReading>,
        predictionMinutes: Int,
        bgLow: Double,
        bgHigh: Double,
        alreadyLow: Boolean,
        alreadyHigh: Boolean,
        unit: GlucoseUnit,
        precomputed: Prediction? = null
    ) {
        val lowSoonEnabled = settings.alertLowSoonEnabled.first()
        val highSoonEnabled = settings.alertHighSoonEnabled.first()

        if (predictionMinutes == 0 || (!lowSoonEnabled && !highSoonEnabled)) {
            notificationManager.cancel(ALERT_LOW_SOON_ID)
            notificationManager.cancel(ALERT_HIGH_SOON_ID)
            return
        }

        val prediction = precomputed ?: PredictionComputer.compute(recentReadings, predictionMinutes, bgLow, bgHigh)
        val crossing = prediction?.crossing

        // Low soon
        val shouldFireLowSoon = lowSoonEnabled && !alreadyLow
            && !isCategoryPausedAtLevel(snoozePrefs, AlertCategory.LOW, ALERT_LEVEL_SOON)
            && crossing?.type == CrossingType.LOW && crossing.minutesUntil >= MIN_CROSSING_MINUTES
        if (shouldFireLowSoon) {
            fireAlert(ALERT_LOW_SOON_ID, CHANNEL_LOW_SOON,
                context.getString(R.string.alert_low_in, crossing!!.minutesUntil),
                context.getString(R.string.alert_predicted, unit.formatWithUnit(crossing.mgdlAtCrossing)))
        } else {
            notificationManager.cancel(ALERT_LOW_SOON_ID)
        }

        // High soon
        val shouldFireHighSoon = highSoonEnabled && !alreadyHigh
            && !isCategoryPausedAtLevel(snoozePrefs, AlertCategory.HIGH, ALERT_LEVEL_SOON)
            && crossing?.type == CrossingType.HIGH && crossing.minutesUntil >= MIN_CROSSING_MINUTES
        if (shouldFireHighSoon) {
            fireAlert(ALERT_HIGH_SOON_ID, CHANNEL_HIGH_SOON,
                context.getString(R.string.alert_high_in, crossing!!.minutesUntil),
                context.getString(R.string.alert_predicted, unit.formatWithUnit(crossing.mgdlAtCrossing)))
        } else {
            notificationManager.cancel(ALERT_HIGH_SOON_ID)
        }
    }

    suspend fun checkStale(lastReadingTs: Long?) {
        val staleEnabled = settings.alertStaleEnabled.first()
        if (!staleEnabled) return

        // Suspending elapsed read goes through the manager so the Clock injection
        // is consistent with state.sinceMs (computing elapsed against System time
        // here would mismatch the test Clock, and worse, mismatch any future
        // production Clock change e.g. for trusted-time sources).
        val sessionElapsedMs = workoutModeManager.currentSessionElapsedMs()
        if (sessionElapsedMs != null) {
            if (sessionElapsedMs < STALE_SUPPRESSION_DURING_WORKOUT_MS) {
                // Within the bounded suppression window. Cancel any visible stale alert
                // so a notification that was firing when the user enabled workout mode
                // clears immediately (without this, the early return leaves a stuck
                // ringing alert until the user manually dismisses it).
                notificationManager.cancel(ALERT_STALE_ID)
                return
            }
            // Suppression window has elapsed (e.g. >30 min into a long workout). Fall
            // through to normal stale handling — for a marathoner whose sensor died
            // 30 min in, we want them alerted, not silently un-protected for hours.
        }

        val now = System.currentTimeMillis()
        if (isStale(lastReadingTs)) {
            if (!isSnoozed(ALERT_STALE_ID, now)) {
                val title = context.getString(R.string.alert_stale_title)
                val body = context.getString(R.string.alert_stale_body)
                // alertOnce: stale persists across reading cycles — without this the
                // user gets re-alarmed every minute the phone is awake.
                fireAlert(ALERT_STALE_ID, CHANNEL_STALE, title, body, alertOnce = true)
            }
        } else {
            notificationManager.cancel(ALERT_STALE_ID)
        }
    }

    fun handlePushFailure(firing: Boolean) {
        if (firing) {
            if (!isSnoozed(ALERT_PUSH_FAIL_ID, System.currentTimeMillis())) {
                fireAlert(
                    ALERT_PUSH_FAIL_ID, CHANNEL_PUSH_FAIL,
                    context.getString(R.string.alert_push_fail_title),
                    context.getString(R.string.alert_push_fail_body),
                    alertOnce = true
                )
            }
        } else {
            notificationManager.cancel(ALERT_PUSH_FAIL_ID)
        }
    }

    fun snooze(alertId: Int) {
        val categoryAndLevel = alertCategoryAndLevel(alertId)
        if (categoryAndLevel != null) {
            val (category, level) = categoryAndLevel
            pauseAlertCategory(category, SNOOZE_DURATION_MS, level)
        } else {
            snoozePrefs.edit { putLong(alertId.toString(), System.currentTimeMillis() + SNOOZE_DURATION_MS) }
        }
        notificationManager.cancel(alertId)
        DebugLog.log("Alert $alertId snoozed for 30 min")
    }

    // Reverse lookup of (alertId -> category, level) derived from AlertCategory.entries
    // so that a new category gets reverse mapping for free, no hand-rolled when needed.
    private val alertIdToCategoryLevel: Map<Int, Pair<AlertCategory, Int>> =
        AlertCategory.entries.flatMap { cat ->
            listOf(
                cat.urgentId to (cat to ALERT_LEVEL_URGENT),
                cat.regularId to (cat to ALERT_LEVEL_REGULAR),
                cat.soonId to (cat to ALERT_LEVEL_SOON),
            )
        }.toMap()

    private fun alertCategoryAndLevel(alertId: Int): Pair<AlertCategory, Int>? =
        alertIdToCategoryLevel[alertId]

    // One MutableStateFlow per category, keyed by the enum so iteration over
    // AlertCategory.entries can never silently bypass a category.
    private val pauseExpiryFlows: Map<AlertCategory, MutableStateFlow<Long?>> =
        AlertCategory.entries.associateWith { MutableStateFlow(alertPauseExpiryMs(it)) }
    val pauseLowExpiryMs: StateFlow<Long?> = pauseExpiryFlows.getValue(AlertCategory.LOW)
    val pauseHighExpiryMs: StateFlow<Long?> = pauseExpiryFlows.getValue(AlertCategory.HIGH)

    fun pauseAlertCategory(category: AlertCategory, durationMs: Long, level: Int = ALERT_LEVEL_URGENT) {
        pauseAlertCategoryAt(category, System.currentTimeMillis() + durationMs, level)
    }

    fun pauseAllAlerts(durationMs: Long, level: Int = ALERT_LEVEL_URGENT) {
        val expiryMs = System.currentTimeMillis() + durationMs
        AlertCategory.entries.forEach { pauseAlertCategoryAt(it, expiryMs, level) }
    }

    fun cancelAlertPause(category: AlertCategory) {
        cancelPause(snoozePrefs, category)
        pauseExpiryFlows.getValue(category).value = null
    }

    fun cancelAllAlerts() {
        AlertCategory.entries.forEach(::cancelAlertPause)
    }

    private fun pauseAlertCategoryAt(category: AlertCategory, expiryMs: Long, level: Int) {
        pauseCategoryAt(snoozePrefs, category, expiryMs, level)
        pauseExpiryFlows.getValue(category).value = expiryMs
        if (level >= ALERT_LEVEL_URGENT) notificationManager.cancel(category.urgentId)
        if (level >= ALERT_LEVEL_REGULAR) notificationManager.cancel(category.regularId)
        if (level >= ALERT_LEVEL_SOON) notificationManager.cancel(category.soonId)
    }

    fun isAlertCategoryPaused(category: AlertCategory): Boolean =
        isCategoryPaused(snoozePrefs, category)

    fun alertPauseExpiryMs(category: AlertCategory): Long? =
        pauseExpiryMs(snoozePrefs, category)

    private fun isSnoozed(alertId: Int, now: Long): Boolean {
        val until = snoozePrefs.getLong(alertId.toString(), 0L)
        if (until == 0L) return false
        if (now >= until) {
            snoozePrefs.edit { remove(alertId.toString()) }
            return false
        }
        return true
    }

    private fun fireAlert(
        alertId: Int,
        channelId: String,
        title: String,
        text: String,
        alertOnce: Boolean = false
    ) {
        DebugLog.log("ALERT: $title — $text")

        val contentIntent = PendingIntent.getActivity(
            context, alertId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = PendingIntent.getBroadcast(
            context, alertId + SNOOZE_INTENT_ID_OFFSET,
            Intent(context, AlertSnoozeReceiver::class.java).apply {
                putExtra("alert_id", alertId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(alertOnce)
            .addAction(0, context.getString(R.string.alert_snooze), snoozeIntent)
            .build()

        notificationManager.notify(alertId, notification)
    }
}
