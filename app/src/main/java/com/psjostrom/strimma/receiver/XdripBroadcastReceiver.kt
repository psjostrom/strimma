package com.psjostrom.strimma.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.psjostrom.strimma.data.GlucoseReading

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

        /**
         * Source tag stamped on the [GlucoseNotificationListener.EXTRA_SOURCE] extra so
         * downstream code (debug logs, [com.psjostrom.strimma.data.SensorIntervals] lookup)
         * can identify a reading as having arrived via the xDrip broadcast path. Falls
         * through to the 1-min default in [com.psjostrom.strimma.data.SensorIntervals]
         * because xDrip-style broadcasts originate from sensor-agnostic middleware.
         */
        const val SOURCE_TAG = "xdrip-broadcast"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val sensorId = intent.getStringExtra("com.eveningoutpost.dexdrip.Extras.SensorId") ?: ""
        if (sensorId == "Strimma") return

        val sgv = intent.getDoubleExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 0.0)
        val timestamp = intent.getLongExtra("com.eveningoutpost.dexdrip.Extras.Time", 0L)

        if (sgv <= 0.0 || timestamp <= 0L) {
            DebugLog.log(message = "xDrip broadcast: invalid data (sgv=$sgv, ts=$timestamp)")
            return
        }
        if (!GlucoseReading.isValidSgv(sgv)) {
            DebugLog.log(message = "xDrip broadcast: sgv ${sgv.toInt()} outside valid range")
            return
        }

        DebugLog.log(message = "xDrip broadcast: ${sgv.toInt()} mg/dL ts=$timestamp")

        val serviceIntent = Intent(context, com.psjostrom.strimma.service.StrimmaService::class.java).apply {
            action = GlucoseNotificationListener.ACTION_GLUCOSE_RECEIVED
            putExtra(GlucoseNotificationListener.EXTRA_MGDL, sgv)
            putExtra(GlucoseNotificationListener.EXTRA_TIMESTAMP, timestamp)
            putExtra(GlucoseNotificationListener.EXTRA_SOURCE, SOURCE_TAG)
        }
        context.startForegroundService(serviceIntent)
    }
}
