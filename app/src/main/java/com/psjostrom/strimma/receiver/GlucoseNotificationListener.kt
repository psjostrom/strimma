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
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseSource

/**
 * Companion mode: reads glucose values from CGM app notifications.
 *
 * Accepts notifications from known CGM apps (CamAPS FX, Dexcom G6/G7/ONE,
 * Juggluco, LibreLink, Libre 3, xDrip+, Diabox, Medtronic, Eversense, Aidex, etc.).
 * Most CGM apps use ongoing notifications; some (Eversense, Dexcom ONE, Medtronic)
 * use regular notifications and are listed in [ONGOING_NOT_REQUIRED].
 *
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

        // Packages that post BG values in non-ongoing notifications.
        // These bypass the isOngoing filter. Based on xDrip+'s coOptedPackagesAll.
        private val ONGOING_NOT_REQUIRED = setOf(
            "com.dexcom.dexcomone",
            "com.dexcom.d1plus",
            "com.dexcom.stelo",
            "com.medtronic.diabetes.guardian",
            "com.medtronic.diabetes.guardianconnect",
            "com.medtronic.diabetes.guardianconnect.us",
            "com.medtronic.diabetes.minimedmobile.eu",
            "com.medtronic.diabetes.minimedmobile.us",
            "com.medtronic.diabetes.simplera.eu",
            "com.senseonics.androidapp",
            "com.senseonics.gen12androidapp",
            "com.senseonics.eversense365.us",
        )

        const val ACTION_GLUCOSE_RECEIVED = "com.psjostrom.strimma.GLUCOSE_RECEIVED"
        const val EXTRA_MGDL = "mgdl"
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
            val detailIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+: deep-link directly to Strimma's notification listener toggle
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                    putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, GlucoseNotificationListener::class.java).flattenToString()
                    )
                }
            } else null

            val intent = if (detailIntent != null && detailIntent.resolveActivity(context.packageManager) != null) {
                detailIntent
            } else {
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Tracks packages for which a rejection has already been logged, to avoid spam. */
    private val loggedRejections = mutableSetOf<String>()

    private fun getSelectedSource(): GlucoseSource {
        val name = getSharedPreferences("strimma_sync", Context.MODE_PRIVATE)
            .getString("glucose_source", null) ?: return GlucoseSource.COMPANION
        return try { GlucoseSource.valueOf(name) } catch (_: Exception) { GlucoseSource.COMPANION }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val source = getSelectedSource()
        val isCgmPackage = sbn.packageName in CGM_PACKAGES

        if (source != GlucoseSource.COMPANION) {
            if (isCgmPackage && loggedRejections.add(sbn.packageName)) {
                DebugLog.log(message = "${sbn.packageName}: ignored (source is ${source.name})")
            }
            return
        }

        if (!isCgmPackage) return

        if (!sbn.isOngoing && sbn.packageName !in ONGOING_NOT_REQUIRED) {
            if (loggedRejections.add(sbn.packageName)) {
                DebugLog.log(message = "${sbn.packageName}: not ongoing, skipped")
            }
            return
        }

        DebugLog.log(message = "Notification from: ${sbn.packageName}")

        val notification = sbn.notification ?: return
        val mgdl = extractGlucose(notification)

        if (mgdl != null && GlucoseReading.isValidSgv(mgdl)) {
            DebugLog.log(message = "Parsed: ${mgdl.toInt()} mg/dL")
            val intent = Intent(this, com.psjostrom.strimma.service.StrimmaService::class.java).apply {
                action = ACTION_GLUCOSE_RECEIVED
                putExtra(EXTRA_MGDL, mgdl)
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
            } catch (
                @Suppress("TooGenericExceptionCaught") // RemoteViews from external apps can throw any exception
                e: Exception
            ) {
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

        // tickerText fallback — Eversense puts the raw BG value here
        notification.tickerText?.toString()?.let { ticker ->
            DebugLog.log(message = "Ticker: $ticker")
            val parsed = tryParseGlucose(ticker)
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

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // no-op: only interested in posted notifications
    }
}
