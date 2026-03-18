package com.psjostrom.strimma.receiver

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Reads glucose values from CamAPS FX notifications.
 *
 * CamAPS FX posts an ongoing notification with the current glucose value.
 * This service intercepts those notifications, parses the glucose number,
 * and forwards it to StrimmaService for processing.
 *
 * Requires "Notification access" permission granted in Android system settings.
 */
class GlucoseNotificationListener : NotificationListenerService() {

    companion object {
        private val CAMAPS_PACKAGES = setOf(
            "com.camdiab.fx_alert.mmoll",
            "com.camdiab.fx_alert.mgdl",
            "com.camdiab.fx_alert.hx.mmoll",
            "com.camdiab.fx_alert.hx.mgdl",
            "com.camdiab.fx_alert.mmoll.ca",
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

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!CAMAPS_PACKAGES.contains(sbn.packageName)) return
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
        // Try RemoteViews first (CamAPS FX uses custom notification layout)
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

        // Fallback: try notification extras title
        notification.extras?.getString(Notification.EXTRA_TITLE)?.let { title ->
            DebugLog.log(message = "Title: $title")
            val parsed = tryParseGlucose(title)
            if (parsed != null) return parsed
        }

        // Fallback: try notification extras text
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
