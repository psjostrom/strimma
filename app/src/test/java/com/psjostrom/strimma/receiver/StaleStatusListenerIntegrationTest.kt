package com.psjostrom.strimma.receiver

import android.app.Notification
import android.content.Context
import android.widget.RemoteViews
import android.service.notification.StatusBarNotification
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * End-to-end coverage that the stale-status filter actually wires through
 * `GlucoseNotificationListener.extractGlucose` — i.e. that an "Attempting"
 * notification reaches the parser path and is rejected before the reading
 * intent fires. The pure function is tested in `StaleStatusFilterTest`; this
 * test guards the wiring (which is what would silently break if someone
 * removed the call site).
 *
 * Reuses Strimma's own `notification_collapsed` layout to get a real
 * RemoteViews with TextViews — Robolectric inflates it via the listener's
 * context just like Android does in production.
 */
@RunWith(RobolectricTestRunner::class)
class StaleStatusListenerIntegrationTest {

    private lateinit var listener: GlucoseNotificationListener
    private lateinit var context: Context

    @Before
    fun setup() {
        listener = Robolectric.setupService(GlucoseNotificationListener::class.java)
        listener.getSharedPreferences("strimma_sync", Context.MODE_PRIVATE)
            .edit().putString("glucose_source", GlucoseSource.COMPANION.name).apply()
        context = RuntimeEnvironment.getApplication()
    }

    private fun camapsNotification(status: String, value: String): Notification {
        // The exact layout doesn't matter — only the resulting flat TextView
        // list does. Strimma's own `notification_collapsed` exposes three
        // TextViews; we put `status` in one and `value` in another so the
        // collected list mirrors the CamAPS shape: [..., status, value, ...].
        val rv = RemoteViews(context.packageName, R.layout.notification_collapsed)
        rv.setTextViewText(R.id.tv_bg, value)
        rv.setTextViewText(R.id.tv_arrow, "→")
        rv.setTextViewText(R.id.tv_delta, status)
        @Suppress("DEPRECATION")
        return Notification().apply {
            contentView = rv
            bigContentView = rv
            flags = Notification.FLAG_ONGOING_EVENT
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
    fun `Attempting notification does not start the reading service`() {
        listener.onNotificationPosted(sbnFor(camapsNotification(status = "Attempting", value = "5,9")))

        assertNull(
            "Stale-status filter must drop the notification before the reading intent fires",
            nextStartedService(),
        )
    }

    @Test
    fun `On notification with same value still starts the reading service`() {
        listener.onNotificationPosted(sbnFor(camapsNotification(status = "On", value = "5,9")))

        val intent = nextStartedService()
        assertNotNull(
            "Healthy 'On' notification must pass through the filter and reach the service",
            intent,
        )
        assertEquals(
            GlucoseNotificationListener.ACTION_GLUCOSE_RECEIVED,
            intent!!.action,
        )
    }
}
