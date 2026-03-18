package com.psjostrom.strimma.receiver

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.psjostrom.strimma.data.GlucoseSource

/**
 * Companion mode: reads glucose values from CGM app notifications.
 *
 * Accepts ongoing notifications from known CGM apps (CamAPS FX, Dexcom G6/G7/ONE,
 * Juggluco, LibreLink, Libre 3, xDrip+, Diabox, Medtronic, Eversense, Aidex, etc.).
 * Parses the glucose number and forwards it to StrimmaService for processing.
 *
 * Disabled when the data source is set to xDrip Broadcast (handled by
 * XdripBroadcastReceiver instead).
 *
 * Requires "Notification access" permission granted in Android system settings.
 */
class GlucoseNotificationListener : NotificationListenerService() {

    companion object {
        // Package list based on xDrip+'s UiBasedCollector.coOptedPackages
        private val CGM_PACKAGES = setOf(
            // CamAPS FX
            "com.camdiab.fx_alert.mmoll",
            "com.camdiab.fx_alert.mgdl",
            "com.camdiab.fx_alert.hx.mmoll",
            "com.camdiab.fx_alert.hx.mgdl",
            "com.camdiab.fx_alert.mmoll.ca",
            // Dexcom
            "com.dexcom.g6",
            "com.dexcom.g6.region1.mmol",
            "com.dexcom.g6.region2.mgdl",
            "com.dexcom.g6.region3.mgdl",
            "com.dexcom.g6.region4.mmol",
            "com.dexcom.g6.region5.mmol",
            "com.dexcom.g6.region6.mgdl",
            "com.dexcom.g6.region7.mmol",
            "com.dexcom.g6.region8.mmol",
            "com.dexcom.g6.region9.mgdl",
            "com.dexcom.g6.region10.mgdl",
            "com.dexcom.g6.region11.mmol",
            "com.dexcom.g7",
            "com.dexcom.dexcomone",
            "com.dexcom.d1plus",
            "com.dexcom.stelo",
            // Libre
            "com.freestylelibre3.app",
            "com.freestylelibre3.app.de",
            "com.freestylelibre.app",
            "com.freestylelibre.app.de",
            // Juggluco
            "tk.glucodata",
            // xDrip+
            "com.eveningoutpost.dexdrip",
            // Diabox
            "com.outshineiot.diabox",
            // Medtronic
            "com.medtronic.diabetes.guardian",
            "com.medtronic.diabetes.guardianconnect",
            "com.medtronic.diabetes.guardianconnect.us",
            "com.medtronic.diabetes.minimedmobile.eu",
            "com.medtronic.diabetes.minimedmobile.us",
            "com.medtronic.diabetes.simplera.eu",
            // Eversense
            "com.senseonics.androidapp",
            "com.senseonics.gen12androidapp",
            "com.senseonics.eversense365.us",
            // Aidex
            "com.microtech.aidexx.mgdl",
            "com.microtech.aidexx.linxneo.mmoll",
            "com.microtech.aidexx.equil.mmoll",
            "com.microtech.aidexx.diaexport.mmoll",
            "com.microtech.aidexx.smart.mmoll",
            "com.microtech.aidexx",
            // Other
            "com.ottai.seas",
            "com.ottai.tag",
            "com.sinocare.cgm.ce",
            "com.sinocare.ican.health.ce",
            "com.sinocare.ican.health.ru",
            "com.suswel.ai",
            "com.glucotech.app.android",
        )

        const val ACTION_GLUCOSE_RECEIVED = "com.psjostrom.strimma.GLUCOSE_RECEIVED"
        const val EXTRA_MMOL = "mmol"
        const val EXTRA_TIMESTAMP = "timestamp"

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val pkg = context.packageName
            return flat.split(":").any { name ->
                ComponentName.unflattenFromString(name)?.packageName == pkg
            }
        }

        fun openSettings(context: Context) {
            context.startActivity(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun getSelectedSource(): GlucoseSource {
        val name = getSharedPreferences("strimma_sync", Context.MODE_PRIVATE)
            .getString("glucose_source", null) ?: return GlucoseSource.COMPANION
        return try { GlucoseSource.valueOf(name) } catch (_: Exception) { GlucoseSource.COMPANION }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (getSelectedSource() != GlucoseSource.COMPANION) return
        if (sbn.packageName !in CGM_PACKAGES) return
        if (!sbn.isOngoing) return

        DebugLog.log(message = "Notification from: ${sbn.packageName}")

        val notification = sbn.notification ?: return
        val mmol = extractGlucose(notification)

        if (mmol != null && mmol > 0.0 && mmol < 50.0) {
            DebugLog.log(message = "Parsed: $mmol mmol/L")
            val intent = Intent(this, com.psjostrom.strimma.service.StrimmaService::class.java).apply {
                action = ACTION_GLUCOSE_RECEIVED
                putExtra(EXTRA_MMOL, mmol)
                putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
            }
            startForegroundService(intent)
        } else {
            DebugLog.log(message = "Could not parse glucose from notification")
        }
    }

    private fun extractGlucose(notification: Notification): Double? {
        notification.contentView?.let { rv ->
            try {
                val applied = rv.apply(this, null)
                val root = applied.rootView as? ViewGroup ?: return@let
                val texts = mutableListOf<String>()
                collectTextViews(texts, root)
                DebugLog.log(message = "TextViews: $texts")
                for (text in texts) {
                    val parsed = tryParseGlucose(text)
                    if (parsed != null) return parsed
                }
            } catch (e: Exception) {
                DebugLog.log(message = "RemoteViews error: ${e.message}")
            }
        }

        notification.extras?.getString(Notification.EXTRA_TITLE)?.let { title ->
            DebugLog.log(message = "Title: $title")
            val parsed = tryParseGlucose(title)
            if (parsed != null) return parsed
        }

        notification.extras?.getString(Notification.EXTRA_TEXT)?.let { text ->
            DebugLog.log(message = "Text: $text")
            val parsed = tryParseGlucose(text)
            if (parsed != null) return parsed
        }

        return null
    }

    private fun collectTextViews(output: MutableList<String>, parent: ViewGroup) {
        for (i in 0 until parent.childCount) {
            val view = parent.getChildAt(i)
            if (view.visibility != View.VISIBLE) continue
            when (view) {
                is TextView -> {
                    val text = view.text?.toString() ?: ""
                    if (text.isNotBlank()) output.add(text)
                }
                is ViewGroup -> collectTextViews(output, view)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}
