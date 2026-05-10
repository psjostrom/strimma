package com.psjostrom.strimma.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.psjostrom.strimma.data.notification.NotificationActionConfig
import com.psjostrom.strimma.data.notification.SnoozeCategory
import com.psjostrom.strimma.data.notification.SnoozeDuration
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Notification-action receiver for the foreground notification's "Snooze" button.
 * Reads the user's configured category + duration from intent extras and applies
 * an alert pause via [AlertManager].
 *
 * Distinct from [AlertSnoozeReceiver] which snoozes a single firing alert by id.
 */
@AndroidEntryPoint
class NotificationSnoozeActionReceiver : BroadcastReceiver() {

    @Inject lateinit var alertManager: AlertManager

    override fun onReceive(context: Context, intent: Intent) {
        handleSnoozeAction(intent, alertManager)
    }

    companion object {
        const val EXTRA_CATEGORY = "snooze_category"
        const val EXTRA_DURATION = "snooze_duration"

        /**
         * Pure logic, separated from the receiver lifecycle so tests can exercise it
         * without going through Hilt's @AndroidEntryPoint bytecode transformation —
         * which would otherwise re-inject `alertManager` on every onReceive call,
         * overwriting any test-supplied instance.
         */
        fun handleSnoozeAction(intent: Intent, alertManager: AlertManager) {
            val category = intent.getStringExtra(EXTRA_CATEGORY)
                ?.let { runCatching { SnoozeCategory.valueOf(it) }.getOrNull() }
                ?: NotificationActionConfig.DEFAULT.snoozeCategory
            val duration = intent.getStringExtra(EXTRA_DURATION)
                ?.let { runCatching { SnoozeDuration.valueOf(it) }.getOrNull() }
                ?: NotificationActionConfig.DEFAULT.snoozeDuration

            when (category) {
                SnoozeCategory.ALL -> alertManager.pauseAllAlerts(duration.durationMs)
                SnoozeCategory.HIGH -> alertManager.pauseAlertCategory(AlertCategory.HIGH, duration.durationMs)
                SnoozeCategory.LOW -> alertManager.pauseAlertCategory(AlertCategory.LOW, duration.durationMs)
            }
        }
    }
}
