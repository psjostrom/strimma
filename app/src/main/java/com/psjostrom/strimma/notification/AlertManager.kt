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
import com.psjostrom.strimma.graph.CrossingType
import com.psjostrom.strimma.graph.PredictionComputer
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

enum class AlertCategory(val prefsKey: String, val levelKey: String) {
    LOW("pause_low", "pause_low_level"),
    HIGH("pause_high", "pause_high_level")
}

@Suppress("TooManyFunctions") // Alert channels + management methods
@Singleton
open class AlertManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository
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

        const val ALERT_URGENT_LOW_ID = 101
        const val ALERT_LOW_ID = 100
        const val ALERT_HIGH_ID = 102
        const val ALERT_URGENT_HIGH_ID = 104
        const val ALERT_STALE_ID = 103
        const val ALERT_PUSH_FAIL_ID = 107
        const val ALERT_LOW_SOON_ID = 105
        const val ALERT_HIGH_SOON_ID = 106

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

        fun isStale(lastReadingTs: Long?): Boolean {
            return lastReadingTs == null ||
                (System.currentTimeMillis() - lastReadingTs) > STALE_THRESHOLD_MINUTES * MS_PER_MINUTE
        }


        val ALL_CHANNELS = listOf(
            CHANNEL_URGENT_LOW, CHANNEL_LOW, CHANNEL_HIGH, CHANNEL_URGENT_HIGH,
            CHANNEL_STALE, CHANNEL_PUSH_FAIL, CHANNEL_LOW_SOON, CHANNEL_HIGH_SOON
        )

        // --- Category-level pause (static methods for testability) ---

        fun pauseCategory(
            prefs: android.content.SharedPreferences,
            category: AlertCategory,
            durationMs: Long,
            level: Int = ALERT_LEVEL_URGENT
        ) {
            val expiryMs = System.currentTimeMillis() + durationMs
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

    suspend fun checkReading(reading: GlucoseReading, recentReadings: List<GlucoseReading>, predictionMinutes: Int) {
        val mgdl = reading.sgv.toDouble()
        val unit = settings.glucoseUnit.first()

        val lowThreshold = settings.alertLow.first()
        val highThreshold = settings.alertHigh.first()

        val alreadyLow = checkLowAlerts(mgdl, unit)
        val alreadyHigh = checkHighAlerts(mgdl, unit)

        checkPredictive(recentReadings, predictionMinutes, lowThreshold.toDouble(),
            highThreshold.toDouble(), alreadyLow, alreadyHigh, unit)
    }

    private suspend fun checkLowAlerts(mgdl: Double, unit: GlucoseUnit): Boolean {
        val urgentLowEnabled = settings.alertUrgentLowEnabled.first()
        val urgentLowThreshold = settings.alertUrgentLow.first()
        val lowEnabled = settings.alertLowEnabled.first()
        val lowThreshold = settings.alertLow.first()

        if (urgentLowEnabled && mgdl <= urgentLowThreshold) {
            if (!isCategoryPausedAtLevel(snoozePrefs, AlertCategory.LOW, ALERT_LEVEL_URGENT)) {
                val title = context.getString(R.string.alert_urgent_low_title)
                fireAlert(ALERT_URGENT_LOW_ID, CHANNEL_URGENT_LOW, title, unit.formatWithUnit(mgdl))
            } else {
                notificationManager.cancel(ALERT_URGENT_LOW_ID)
            }
            notificationManager.cancel(ALERT_LOW_ID)
            return true
        }

        if (lowEnabled && mgdl < lowThreshold) {
            if (!isCategoryPausedAtLevel(snoozePrefs, AlertCategory.LOW, ALERT_LEVEL_REGULAR)) {
                fireAlert(ALERT_LOW_ID, CHANNEL_LOW, context.getString(R.string.alert_low_title), unit.formatWithUnit(mgdl))
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

    private suspend fun checkHighAlerts(mgdl: Double, unit: GlucoseUnit): Boolean {
        val urgentHighEnabled = settings.alertUrgentHighEnabled.first()
        val urgentHighThreshold = settings.alertUrgentHigh.first()
        val highEnabled = settings.alertHighEnabled.first()
        val highThreshold = settings.alertHigh.first()

        if (urgentHighEnabled && mgdl >= urgentHighThreshold) {
            if (!isCategoryPausedAtLevel(snoozePrefs, AlertCategory.HIGH, ALERT_LEVEL_URGENT)) {
                val title = context.getString(R.string.alert_urgent_high_title)
                fireAlert(ALERT_URGENT_HIGH_ID, CHANNEL_URGENT_HIGH, title, unit.formatWithUnit(mgdl))
            } else {
                notificationManager.cancel(ALERT_URGENT_HIGH_ID)
            }
            notificationManager.cancel(ALERT_HIGH_ID)
            return true
        }

        if (highEnabled && mgdl > highThreshold) {
            if (!isCategoryPausedAtLevel(snoozePrefs, AlertCategory.HIGH, ALERT_LEVEL_REGULAR)) {
                fireAlert(ALERT_HIGH_ID, CHANNEL_HIGH, context.getString(R.string.alert_high_title), unit.formatWithUnit(mgdl))
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

    @Suppress("CyclomaticComplexMethod", "LongParameterList") // Symmetric low/high blocks
    private suspend fun checkPredictive(
        recentReadings: List<GlucoseReading>,
        predictionMinutes: Int,
        bgLow: Double,
        bgHigh: Double,
        alreadyLow: Boolean,
        alreadyHigh: Boolean,
        unit: GlucoseUnit
    ) {
        val lowSoonEnabled = settings.alertLowSoonEnabled.first()
        val highSoonEnabled = settings.alertHighSoonEnabled.first()

        if (predictionMinutes == 0 || (!lowSoonEnabled && !highSoonEnabled)) {
            notificationManager.cancel(ALERT_LOW_SOON_ID)
            notificationManager.cancel(ALERT_HIGH_SOON_ID)
            return
        }

        val prediction = PredictionComputer.compute(recentReadings, predictionMinutes, bgLow, bgHigh)
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

        val now = System.currentTimeMillis()
        if (isStale(lastReadingTs)) {
            if (!isSnoozed(ALERT_STALE_ID, now)) {
                val title = context.getString(R.string.alert_stale_title)
                val body = context.getString(R.string.alert_stale_body)
                fireAlert(ALERT_STALE_ID, CHANNEL_STALE, title, body)
            }
        } else {
            notificationManager.cancel(ALERT_STALE_ID)
        }
    }

    open fun handlePushFailure(firing: Boolean) {
        if (firing) {
            if (!isSnoozed(ALERT_PUSH_FAIL_ID, System.currentTimeMillis())) {
                fireAlert(
                    ALERT_PUSH_FAIL_ID, CHANNEL_PUSH_FAIL,
                    context.getString(R.string.alert_push_fail_title),
                    context.getString(R.string.alert_push_fail_body)
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

    private fun alertCategoryAndLevel(alertId: Int): Pair<AlertCategory, Int>? = when (alertId) {
        ALERT_LOW_SOON_ID -> AlertCategory.LOW to ALERT_LEVEL_SOON
        ALERT_LOW_ID -> AlertCategory.LOW to ALERT_LEVEL_REGULAR
        ALERT_URGENT_LOW_ID -> AlertCategory.LOW to ALERT_LEVEL_URGENT
        ALERT_HIGH_SOON_ID -> AlertCategory.HIGH to ALERT_LEVEL_SOON
        ALERT_HIGH_ID -> AlertCategory.HIGH to ALERT_LEVEL_REGULAR
        ALERT_URGENT_HIGH_ID -> AlertCategory.HIGH to ALERT_LEVEL_URGENT
        else -> null
    }

    private val _pauseLowExpiryMs = MutableStateFlow<Long?>(alertPauseExpiryMs(AlertCategory.LOW))
    private val _pauseHighExpiryMs = MutableStateFlow<Long?>(alertPauseExpiryMs(AlertCategory.HIGH))
    val pauseLowExpiryMs: StateFlow<Long?> = _pauseLowExpiryMs
    val pauseHighExpiryMs: StateFlow<Long?> = _pauseHighExpiryMs

    fun pauseAlertCategory(category: AlertCategory, durationMs: Long, level: Int = ALERT_LEVEL_URGENT) {
        pauseCategory(snoozePrefs, category, durationMs, level)
        val expiryMs = System.currentTimeMillis() + durationMs
        when (category) {
            AlertCategory.LOW -> {
                _pauseLowExpiryMs.value = expiryMs
                if (level >= ALERT_LEVEL_URGENT) notificationManager.cancel(ALERT_URGENT_LOW_ID)
                if (level >= ALERT_LEVEL_REGULAR) notificationManager.cancel(ALERT_LOW_ID)
                if (level >= ALERT_LEVEL_SOON) notificationManager.cancel(ALERT_LOW_SOON_ID)
            }
            AlertCategory.HIGH -> {
                _pauseHighExpiryMs.value = expiryMs
                if (level >= ALERT_LEVEL_URGENT) notificationManager.cancel(ALERT_URGENT_HIGH_ID)
                if (level >= ALERT_LEVEL_REGULAR) notificationManager.cancel(ALERT_HIGH_ID)
                if (level >= ALERT_LEVEL_SOON) notificationManager.cancel(ALERT_HIGH_SOON_ID)
            }
        }
    }

    fun cancelAlertPause(category: AlertCategory) {
        cancelPause(snoozePrefs, category)
        when (category) {
            AlertCategory.LOW -> _pauseLowExpiryMs.value = null
            AlertCategory.HIGH -> _pauseHighExpiryMs.value = null
        }
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

    private fun fireAlert(alertId: Int, channelId: String, title: String, text: String) {
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
            .addAction(0, context.getString(R.string.alert_snooze), snoozeIntent)
            .build()

        notificationManager.notify(alertId, notification)
    }
}
