package com.psjostrom.strimma.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicBoolean

class WorkoutAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.psjostrom.strimma.WORKOUT_ALARM"
        const val EXTRA_EVENT_START_TIME = "event_start_time"

        val notificationTriggerFired = AtomicBoolean(false)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION) {
            notificationTriggerFired.set(true)
            try {
                DebugLog.log("Workout alarm fired — notification trigger active")
            } catch (_: Exception) { /* flag is set regardless */ }
        }
    }
}
