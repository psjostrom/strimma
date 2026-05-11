package com.psjostrom.strimma.receiver

import android.app.Notification
import android.content.Context
import android.widget.RemoteViews
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * removed the call site) and the cross-source coverage (the filter must apply
 * regardless of whether the value comes from contentView, extras, or ticker).
 *
 * Reuses Strimma's own `notification_collapsed` layout to get a real
 * RemoteViews with TextViews — Robolectric inflates it via the listener's
 * context just like Android does in production.
 */
@RunWith(RobolectricTestRunner::class)
class StaleStatusListenerIntegrationTest {

    private fun setupListener(): GlucoseNotificationListener {
        val listener = Robolectric.setupService(GlucoseNotificationListener::class.java)
        listener.getSharedPreferences("strimma_sync", Context.MODE_PRIVATE)
            .edit().putString("glucose_source", GlucoseSource.COMPANION.name).apply()
        return listener
    }

    private fun camapsContentViewNotification(status: String, value: String): Notification {
        // The exact layout doesn't matter — only the resulting flat TextView
        // list does. Strimma's own `notification_collapsed` exposes three
        // TextViews; we put `status` in one and `value` in another so the
        // collected list mirrors the CamAPS shape: [..., status, value, ...].
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
        }
    }

    private fun camapsExtrasOnlyNotification(title: String, text: String): Notification {
        // Defensive: simulate the case where contentView is absent (or its
        // RemoteViews failed to inflate) and CamAPS has populated EXTRA_TITLE/
        // EXTRA_TEXT instead. The filter must reject when ANY source carries
        // the stale-status word.
        @Suppress("DEPRECATION")
        return Notification().apply {
            extras = Bundle().apply {
                putString(Notification.EXTRA_TITLE, title)
                putString(Notification.EXTRA_TEXT, text)
            }
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
    fun `Attempting notification via contentView does not start the reading service`() {
        val listener = setupListener()

        listener.onNotificationPosted(
            sbnFor(camapsContentViewNotification(status = "Attempting", value = "5,9")),
        )

        assertNull(
            "Stale-status filter must drop the notification before the reading intent fires",
            nextStartedService(),
        )
    }

    @Test
    fun `On notification with same value still starts the reading service`() {
        val listener = setupListener()

        listener.onNotificationPosted(
            sbnFor(camapsContentViewNotification(status = "On", value = "5,9")),
        )

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

    @Test
    fun `Attempting status in extras also rejected (no contentView)`() {
        // Regression guard: previously the filter only ran on the contentView
        // path. If contentView is null (or rv.apply() throws), the listener
        // falls through to extras parsing — and an "Attempting" status word
        // there must still be caught.
        val listener = setupListener()

        listener.onNotificationPosted(
            sbnFor(camapsExtrasOnlyNotification(title = "Attempting", text = "5,9")),
        )

        assertNull(
            "Stale-status filter must apply across all extraction sources, not just contentView",
            nextStartedService(),
        )
    }
}
