package com.psjostrom.strimma.receiver

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.widget.RemoteViews
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseSource
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * Regression test for PR #222's incorrect "Attempting" mode-word filter.
 *
 * Per the CamAPS FX user manual (modes of operation, page 16), "Attempting" is the
 * AID-loop's "trying to enter Auto mode" state and fires for any of six conditions
 * including loss of pump comms, pump-suspended delivery, Bluetooth off, and sensor
 * data unavailable. When sensor data is unavailable, the value field shows "---" — a
 * numeric value being shown means the CGM is currently delivering. The original PR
 * conflated the mode word with sensor unavailability and rejected every numeric value
 * accompanying it, so Strimma blanked out live BG during pump-only events.
 *
 * The fix: trust the parser. A parseable numeric value is a real reading regardless
 * of mode word; "---" naturally returns null from the parser and is silently dropped.
 */
@RunWith(RobolectricTestRunner::class)
class AttemptingNotificationTest {

    private fun setupListener(): GlucoseNotificationListener {
        val listener = Robolectric.setupService(GlucoseNotificationListener::class.java)
        listener.getSharedPreferences("strimma_sync", Context.MODE_PRIVATE)
            .edit().putString("glucose_source", GlucoseSource.COMPANION.name).apply()
        return listener
    }

    private fun camapsNotification(status: String, value: String): Notification {
        val context = RuntimeEnvironment.getApplication()
        val rv = RemoteViews(context.packageName, R.layout.notification_collapsed)
        rv.setTextViewText(R.id.tv_bg, value)
        rv.setTextViewText(R.id.tv_arrow, "→")
        rv.setTextViewText(R.id.tv_delta, status)
        @Suppress("DEPRECATION")
        return Notification().apply {
            contentView = rv
            bigContentView = rv
            flags = Notification.FLAG_ONGOING_EVENT
            extras = Bundle()
        }
    }

    private fun sbnFor(notification: Notification): StatusBarNotification {
        @Suppress("DEPRECATION")
        return StatusBarNotification(
            "com.camdiab.fx_alert.mmoll", "com.camdiab.fx_alert.mmoll", 1, null, 0, 0, 0,
            notification, android.os.Process.myUserHandle(),
            System.currentTimeMillis(),
        )
    }

    private fun nextStartedService() =
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedService

    @Test
    fun `Attempting notification with a numeric value is forwarded`() {
        // The bug PR #222 introduced: this notification (real CGM data, AID loop in a
        // non-Auto state for a non-CGM reason) was rejected for the entire duration of
        // the pump-loop issue. The screen showed "Waiting for glucose data..." while
        // CamAPS itself was visibly displaying the live value.
        val listener = setupListener()
        listener.onNotificationPosted(sbnFor(camapsNotification(status = "Attempting", value = "14,3")))
        assertNotNull(
            "Attempting + numeric value must forward — value field is the current CGM reading",
            nextStartedService()
        )
    }

    @Test
    fun `Attempting notification across continuously changing values all forward`() {
        // Sustained pump-loop issue with healthy CGM stream — every reading must pass
        // through. Captured directly from the screenshot evidence: CGM trending up while
        // CamAPS is in Attempting due to a separate pump-side condition.
        val listener = setupListener()
        listOf("10,6", "10,7", "10,8", "10,9", "11,0", "11,3", "11,6", "11,8", "11,9").forEach { value ->
            listener.onNotificationPosted(sbnFor(camapsNotification(status = "Attempting", value = value)))
            assertNotNull("$value during Attempting must forward", nextStartedService())
        }
    }

    @Test
    fun `notification with --- as value is naturally dropped by the parser`() {
        // Per the manual, "---" is CamAPS's symbol for "sensor glucose data unavailable."
        // The pure parser already rejects it (no regex match); the listener forwards
        // nothing because no value extracted. No special filter required.
        val listener = setupListener()
        listener.onNotificationPosted(sbnFor(camapsNotification(status = "Attempting", value = "---")))
        assertNull("--- has no parseable value, must not start the service", nextStartedService())
    }
}
