package com.psjostrom.strimma.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.SettingsRepository
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
        const val ALERT_CHANNEL_ID = "strimma_alerts"
        const val ALERT_LOW_ID = 100
        const val ALERT_URGENT_LOW_ID = 101
        const val ALERT_HIGH_ID = 102
        const val ALERT_STALE_ID = 103

        private const val SNOOZE_DURATION_MS = 30 * 60 * 1000L // 30 minutes
    }

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    // Snooze tracking (in-memory — re-alerts on process restart, which is correct)
    private val snoozedUntil = mutableMapOf<Int, Long>()

    fun createChannel() {
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Glucose Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Low / high glucose alerts"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setBypassDnd(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    suspend fun checkReading(reading: GlucoseReading) {
        val now = System.currentTimeMillis()
        val mmol = reading.mmol

        val urgentLowEnabled = settings.alertUrgentLowEnabled.first()
        val urgentLowThreshold = settings.alertUrgentLow.first()
        val lowEnabled = settings.alertLowEnabled.first()
        val lowThreshold = settings.alertLow.first()
        val highEnabled = settings.alertHighEnabled.first()
        val highThreshold = settings.alertHigh.first()

        // Urgent low takes priority over regular low
        if (urgentLowEnabled && mmol <= urgentLowThreshold) {
            if (!isSnoozed(ALERT_URGENT_LOW_ID, now)) {
                fireAlert(ALERT_URGENT_LOW_ID, "Urgent Low", "%.1f mmol/L".format(mmol))
                // Clear regular low if urgent is firing
                notificationManager.cancel(ALERT_LOW_ID)
            }
        } else if (lowEnabled && mmol < lowThreshold) {
            if (!isSnoozed(ALERT_LOW_ID, now)) {
                fireAlert(ALERT_LOW_ID, "Low Glucose", "%.1f mmol/L".format(mmol))
            }
        } else {
            // Back in range — clear low alerts
            notificationManager.cancel(ALERT_LOW_ID)
            notificationManager.cancel(ALERT_URGENT_LOW_ID)
            snoozedUntil.remove(ALERT_LOW_ID)
            snoozedUntil.remove(ALERT_URGENT_LOW_ID)
        }

        if (highEnabled && mmol > highThreshold) {
            if (!isSnoozed(ALERT_HIGH_ID, now)) {
                fireAlert(ALERT_HIGH_ID, "High Glucose", "%.1f mmol/L".format(mmol))
            }
        } else {
            notificationManager.cancel(ALERT_HIGH_ID)
            snoozedUntil.remove(ALERT_HIGH_ID)
        }
    }

    suspend fun checkStale(lastReadingTs: Long?) {
        val staleEnabled = settings.alertStaleEnabled.first()
        if (!staleEnabled) return

        val now = System.currentTimeMillis()
        if (lastReadingTs == null || (now - lastReadingTs) > 10 * 60 * 1000) {
            if (!isSnoozed(ALERT_STALE_ID, now)) {
                fireAlert(ALERT_STALE_ID, "No Data", "No glucose reading for 10+ minutes")
            }
        } else {
            notificationManager.cancel(ALERT_STALE_ID)
            snoozedUntil.remove(ALERT_STALE_ID)
        }
    }

    fun snooze(alertId: Int) {
        snoozedUntil[alertId] = System.currentTimeMillis() + SNOOZE_DURATION_MS
        notificationManager.cancel(alertId)
        DebugLog.log("Alert $alertId snoozed for 30 min")
    }

    private fun isSnoozed(alertId: Int, now: Long): Boolean {
        val until = snoozedUntil[alertId] ?: return false
        if (now >= until) {
            snoozedUntil.remove(alertId)
            return false
        }
        return true
    }

    private fun fireAlert(alertId: Int, title: String, text: String) {
        DebugLog.log("ALERT: $title — $text")

        val contentIntent = PendingIntent.getActivity(
            context, alertId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = PendingIntent.getBroadcast(
            context, alertId + 200,
            Intent(context, AlertSnoozeReceiver::class.java).apply {
                putExtra("alert_id", alertId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
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
