package com.psjostrom.strimma.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AlertSnoozeReceiver : BroadcastReceiver() {

    @Inject lateinit var alertManager: AlertManager

    override fun onReceive(context: Context, intent: Intent) {
        val alertId = intent.getIntExtra("alert_id", -1)
        if (alertId != -1) {
            alertManager.snooze(alertId)
        }
    }
}
