package com.psjostrom.strimma.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.psjostrom.strimma.data.notification.SnoozeDuration
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AlertSnoozeReceiver : BroadcastReceiver() {

    @Inject lateinit var alertManager: AlertManager

    override fun onReceive(context: Context, intent: Intent) {
        handleSnooze(intent, alertManager)
    }

    companion object {
        const val EXTRA_DURATION = "snooze_duration"

        /**
         * Pure logic, separated from the receiver lifecycle so tests can exercise it
         * without going through Hilt's @AndroidEntryPoint bytecode transformation.
         */
        fun handleSnooze(intent: Intent, alertManager: AlertManager) {
            val alertId = intent.getIntExtra("alert_id", -1)
            if (alertId == -1) return
            val durationMs = intent.getStringExtra(EXTRA_DURATION)
                ?.let { runCatching { SnoozeDuration.valueOf(it) }.getOrNull() }
                ?.durationMs
                ?: SnoozeDuration.M30.durationMs
            alertManager.snooze(alertId, durationMs)
        }
    }
}
