package com.psjostrom.strimma.notification

import android.app.NotificationChannel
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
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.graph.CrossingType
import com.psjostrom.strimma.graph.PredictionComputer
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertManager @Inject constructor(
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
        const val CHANNEL_LOW_SOON = "strimma_alert_low_soon"
        const val CHANNEL_HIGH_SOON = "strimma_alert_high_soon"

        // Legacy channel — delete if it exists from previous version
        private const val LEGACY_CHANNEL = "strimma_alerts"

        const val ALERT_URGENT_LOW_ID = 101
        const val ALERT_LOW_ID = 100
        const val ALERT_HIGH_ID = 102
        const val ALERT_URGENT_HIGH_ID = 104
        const val ALERT_STALE_ID = 103
        const val ALERT_LOW_SOON_ID = 105
        const val ALERT_HIGH_SOON_ID = 106

        private const val SNOOZE_DURATION_MS = 30 * 60 * 1000L

        private const val MIN_CROSSING_MINUTES = 4

        // Vibration patterns (ms: [delay, vibrate, pause, vibrate, ...])
        private const val VIBRATE_LONG = 800L
        private const val VIBRATE_MEDIUM = 500L
        private const val VIBRATE_SHORT = 300L
        private const val VIBRATE_BRIEF = 200L

        // Notification IDs
        private const val SNOOZE_INTENT_ID_OFFSET = 200

        // Stale data threshold
        private const val STALE_THRESHOLD_MINUTES = 10
        private const val MINUTES_TO_MS = 60 * 1000L

        val ALL_CHANNELS = listOf(
            CHANNEL_URGENT_LOW, CHANNEL_LOW, CHANNEL_HIGH, CHANNEL_URGENT_HIGH,
            CHANNEL_STALE, CHANNEL_LOW_SOON, CHANNEL_HIGH_SOON
        )
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

    fun createChannels() {
        // Remove legacy single channel
        notificationManager.deleteNotificationChannel(LEGACY_CHANNEL)

        createChannel(
            CHANNEL_URGENT_LOW, "Urgent Low Alert",
            "Glucose critically low",
            NotificationManager.IMPORTANCE_HIGH,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            alarmAudioAttrs, bypassDnd = true,
            vibration = longArrayOf(0, VIBRATE_LONG, VIBRATE_BRIEF, VIBRATE_LONG, VIBRATE_BRIEF, VIBRATE_LONG)
        )
        createChannel(
            CHANNEL_LOW, "Low Alert",
            "Glucose below low threshold",
            NotificationManager.IMPORTANCE_HIGH,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            notifAudioAttrs, bypassDnd = false,
            vibration = longArrayOf(0, VIBRATE_MEDIUM, VIBRATE_BRIEF, VIBRATE_MEDIUM)
        )
        createChannel(
            CHANNEL_HIGH, "High Alert",
            "Glucose above high threshold",
            NotificationManager.IMPORTANCE_HIGH,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            notifAudioAttrs, bypassDnd = false,
            vibration = longArrayOf(0, VIBRATE_SHORT, VIBRATE_BRIEF, VIBRATE_SHORT)
        )
        createChannel(
            CHANNEL_URGENT_HIGH, "Urgent High Alert",
            "Glucose critically high",
            NotificationManager.IMPORTANCE_HIGH,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            alarmAudioAttrs, bypassDnd = true,
            vibration = longArrayOf(0, VIBRATE_LONG, VIBRATE_BRIEF, VIBRATE_LONG, VIBRATE_BRIEF, VIBRATE_LONG)
        )
        createChannel(
            CHANNEL_STALE, "Stale Data Alert",
            "No glucose reading for 10+ minutes",
            NotificationManager.IMPORTANCE_DEFAULT,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            notifAudioAttrs, bypassDnd = false,
            vibration = longArrayOf(0, VIBRATE_BRIEF, VIBRATE_BRIEF, VIBRATE_BRIEF)
        )
        createChannel(
            CHANNEL_LOW_SOON, "Low Soon Alert",
            "Glucose predicted to go low",
            NotificationManager.IMPORTANCE_DEFAULT,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            notifAudioAttrs, bypassDnd = false,
            vibration = longArrayOf(0, VIBRATE_BRIEF, VIBRATE_BRIEF, VIBRATE_BRIEF)
        )
        createChannel(
            CHANNEL_HIGH_SOON, "High Soon Alert",
            "Glucose predicted to go high",
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
        // Only create if channel doesn't exist yet — once created, the user owns the settings
        if (notificationManager.getNotificationChannel(id) != null) return
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
        val now = System.currentTimeMillis()
        val mgdl = reading.sgv.toDouble()
        val unit = settings.glucoseUnit.first()

        val urgentLowEnabled = settings.alertUrgentLowEnabled.first()
        val urgentLowThreshold = settings.alertUrgentLow.first()
        val lowEnabled = settings.alertLowEnabled.first()
        val lowThreshold = settings.alertLow.first()
        val highEnabled = settings.alertHighEnabled.first()
        val highThreshold = settings.alertHigh.first()
        val urgentHighEnabled = settings.alertUrgentHighEnabled.first()
        val urgentHighThreshold = settings.alertUrgentHigh.first()

        var alreadyLow = false
        var alreadyHigh = false

        // --- Lows (urgent takes priority) ---
        if (urgentLowEnabled && mgdl <= urgentLowThreshold) {
            alreadyLow = true
            if (!isSnoozed(ALERT_URGENT_LOW_ID, now)) {
                fireAlert(ALERT_URGENT_LOW_ID, CHANNEL_URGENT_LOW, "Urgent Low", unit.formatWithUnit(mgdl))
                notificationManager.cancel(ALERT_LOW_ID)
            }
        } else if (lowEnabled && mgdl < lowThreshold) {
            alreadyLow = true
            if (!isSnoozed(ALERT_LOW_ID, now)) {
                fireAlert(ALERT_LOW_ID, CHANNEL_LOW, "Low Glucose", unit.formatWithUnit(mgdl))
            }
            notificationManager.cancel(ALERT_URGENT_LOW_ID)
            clearSnooze(ALERT_URGENT_LOW_ID)
        } else {
            notificationManager.cancel(ALERT_LOW_ID)
            notificationManager.cancel(ALERT_URGENT_LOW_ID)
        }

        // --- Highs (urgent takes priority) ---
        if (urgentHighEnabled && mgdl >= urgentHighThreshold) {
            alreadyHigh = true
            if (!isSnoozed(ALERT_URGENT_HIGH_ID, now)) {
                fireAlert(ALERT_URGENT_HIGH_ID, CHANNEL_URGENT_HIGH, "Urgent High", unit.formatWithUnit(mgdl))
                notificationManager.cancel(ALERT_HIGH_ID)
            }
        } else if (highEnabled && mgdl > highThreshold) {
            alreadyHigh = true
            if (!isSnoozed(ALERT_HIGH_ID, now)) {
                fireAlert(ALERT_HIGH_ID, CHANNEL_HIGH, "High Glucose", unit.formatWithUnit(mgdl))
            }
            notificationManager.cancel(ALERT_URGENT_HIGH_ID)
            clearSnooze(ALERT_URGENT_HIGH_ID)
        } else {
            notificationManager.cancel(ALERT_HIGH_ID)
            notificationManager.cancel(ALERT_URGENT_HIGH_ID)
        }

        // --- Predictive alerts (only when currently in range) ---
        checkPredictive(recentReadings, predictionMinutes, lowThreshold.toDouble(),
            highThreshold.toDouble(), alreadyLow, alreadyHigh, unit, now)
    }

    @Suppress("CyclomaticComplexMethod", "LongParameterList") // Two symmetric low/high blocks
    private suspend fun checkPredictive(
        recentReadings: List<GlucoseReading>,
        predictionMinutes: Int,
        bgLow: Double,
        bgHigh: Double,
        alreadyLow: Boolean,
        alreadyHigh: Boolean,
        unit: GlucoseUnit,
        now: Long
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
        if (lowSoonEnabled && !alreadyLow && crossing?.type == CrossingType.LOW
            && crossing.minutesUntil >= MIN_CROSSING_MINUTES) {
            if (!isSnoozed(ALERT_LOW_SOON_ID, now)) {
                fireAlert(ALERT_LOW_SOON_ID, CHANNEL_LOW_SOON,
                    "Low in ${crossing.minutesUntil} min",
                    "Predicted ${unit.formatWithUnit(crossing.mgdlAtCrossing)}")
            }
        } else {
            notificationManager.cancel(ALERT_LOW_SOON_ID)
            if (alreadyLow) clearSnooze(ALERT_LOW_SOON_ID)
        }

        // High soon
        if (highSoonEnabled && !alreadyHigh && crossing?.type == CrossingType.HIGH
            && crossing.minutesUntil >= MIN_CROSSING_MINUTES) {
            if (!isSnoozed(ALERT_HIGH_SOON_ID, now)) {
                fireAlert(ALERT_HIGH_SOON_ID, CHANNEL_HIGH_SOON,
                    "High in ${crossing.minutesUntil} min",
                    "Predicted ${unit.formatWithUnit(crossing.mgdlAtCrossing)}")
            }
        } else {
            notificationManager.cancel(ALERT_HIGH_SOON_ID)
            if (alreadyHigh) clearSnooze(ALERT_HIGH_SOON_ID)
        }
    }

    suspend fun checkStale(lastReadingTs: Long?) {
        val staleEnabled = settings.alertStaleEnabled.first()
        if (!staleEnabled) return

        val now = System.currentTimeMillis()
        if (lastReadingTs == null || (now - lastReadingTs) > STALE_THRESHOLD_MINUTES * MINUTES_TO_MS) {
            if (!isSnoozed(ALERT_STALE_ID, now)) {
                fireAlert(ALERT_STALE_ID, CHANNEL_STALE, "No Data", "No glucose reading for 10+ minutes")
            }
        } else {
            notificationManager.cancel(ALERT_STALE_ID)
        }
    }

    fun snooze(alertId: Int) {
        snoozePrefs.edit().putLong(alertId.toString(), System.currentTimeMillis() + SNOOZE_DURATION_MS).apply()
        notificationManager.cancel(alertId)
        DebugLog.log("Alert $alertId snoozed for 30 min")
    }

    private fun clearSnooze(alertId: Int) {
        snoozePrefs.edit().remove(alertId.toString()).apply()
    }

    private fun isSnoozed(alertId: Int, now: Long): Boolean {
        val until = snoozePrefs.getLong(alertId.toString(), 0L)
        if (until == 0L) return false
        if (now >= until) {
            snoozePrefs.edit().remove(alertId.toString()).apply()
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
            .addAction(0, "Snooze 30 min", snoozeIntent)
            .build()

        notificationManager.notify(alertId, notification)
    }
}
