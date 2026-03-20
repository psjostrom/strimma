package com.psjostrom.strimma.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives xDrip-compatible BG broadcasts from other CGM apps.
 *
 * Listens for `com.eveningoutpost.dexdrip.BgEstimate` intents sent by xDrip+,
 * Juggluco, AAPS, GlucoDataHandler, or any app using the xDrip broadcast format.
 * Extracts sgv (mg/dL) and forwards to StrimmaService.
 *
 * Ignores broadcasts from Strimma itself (SensorId = "Strimma") to prevent loops
 * when BG broadcast (send) is also enabled.
 *
 * Must be registered dynamically from StrimmaService with RECEIVER_EXPORTED.
 */
class XdripBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.eveningoutpost.dexdrip.BgEstimate"
        private const val MIN_VALID_MGDL = 18.0
        private const val MAX_VALID_MGDL = 900.0
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val sensorId = intent.getStringExtra("com.eveningoutpost.dexdrip.Extras.SensorId") ?: ""
        if (sensorId == "Strimma") return

        val sgv = intent.getDoubleExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 0.0)
        val timestamp = intent.getLongExtra("com.eveningoutpost.dexdrip.Extras.Time", 0L)

        if (sgv <= 0.0 || timestamp <= 0L) return
        if (sgv < MIN_VALID_MGDL || sgv > MAX_VALID_MGDL) return

        DebugLog.log(message = "xDrip broadcast: ${sgv.toInt()} mg/dL")

        val serviceIntent = Intent(context, com.psjostrom.strimma.service.StrimmaService::class.java).apply {
            action = GlucoseNotificationListener.ACTION_GLUCOSE_RECEIVED
            putExtra(GlucoseNotificationListener.EXTRA_MGDL, sgv)
            putExtra(GlucoseNotificationListener.EXTRA_TIMESTAMP, timestamp)
        }
        context.startForegroundService(serviceIntent)
    }
}
