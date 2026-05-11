package com.psjostrom.strimma.receiver

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.psjostrom.strimma.data.GlucoseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * Tests that the reading timestamp comes from the source CGM notification's `when`
 * field rather than the moment Strimma processes it.
 *
 * Why this matters: Android re-fires `onNotificationPosted` for every active
 * notification when a `NotificationListenerService` (re)binds — on boot, after
 * an app install, after the system unbinds the listener under memory pressure,
 * etc. If Strimma stamped each redelivery with `System.currentTimeMillis()`,
 * a stale CamAPS notification (e.g. last fresh post at T0, no new sensor data
 * since) would land in the DB as if it were a brand-new reading at T_rebind.
 * The user would see "0m" against a value that is actually minutes old, and
 * the stale alert would never fire because `lastReadingTs` keeps getting
 * refreshed on every rebind.
 *
 * Using `notification.when` (the time the source app stamped on the
 * notification) keeps the original sensor-reading time intact across rebinds,
 * so `minutesAgo` grows correctly and the bucket dedup in `ReadingPipeline`
 * collapses the rebind-redelivered duplicate.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationTimestampTest {

    private lateinit var listener: GlucoseNotificationListener

    @Before
    fun setup() {
        listener = Robolectric.setupService(GlucoseNotificationListener::class.java)
        listener.getSharedPreferences("strimma_sync", Context.MODE_PRIVATE)
            .edit().putString("glucose_source", GlucoseSource.COMPANION.name).apply()
    }

    private fun buildSbn(packageName: String, notification: Notification): StatusBarNotification {
        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT
        @Suppress("DEPRECATION")
        return StatusBarNotification(
            packageName, packageName, 1, null, 0, 0, 0,
            notification, android.os.Process.myUserHandle(),
            System.currentTimeMillis(),
        )
    }

    private fun titleNotification(title: String, whenMs: Long): Notification =
        Notification().apply {
            extras = Bundle().apply { putString(Notification.EXTRA_TITLE, title) }
            `when` = whenMs
        }

    private fun nextStartedService(): Intent? =
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedService

    @Test
    fun `timestamp comes from notification when, not from currentTimeMillis`() {
        val fiveMinutesAgo = System.currentTimeMillis() - 5 * 60_000L
        val sbn = buildSbn(
            "com.senseonics.eversense365.us",
            titleNotification("120", whenMs = fiveMinutesAgo),
        )

        listener.onNotificationPosted(sbn)

        val intent = nextStartedService()
        assertNotNull(intent)
        val storedTs = intent!!.getLongExtra(GlucoseNotificationListener.EXTRA_TIMESTAMP, 0L)
        assertEquals(
            "Reading timestamp should be the notification's `when`, not now",
            fiveMinutesAgo, storedTs,
        )
    }

    @Test
    fun `falls back to currentTimeMillis when notification when is zero`() {
        val before = System.currentTimeMillis()
        val sbn = buildSbn(
            "com.senseonics.eversense365.us",
            titleNotification("120", whenMs = 0L),
        )

        listener.onNotificationPosted(sbn)
        val after = System.currentTimeMillis()

        val intent = nextStartedService()
        assertNotNull(intent)
        val storedTs = intent!!.getLongExtra(GlucoseNotificationListener.EXTRA_TIMESTAMP, 0L)
        // when=0 is the documented "no timestamp" sentinel; falling back to now keeps
        // the old behaviour for sources that don't bother to set it.
        assertTrue(
            "Expected fallback timestamp in [$before, $after], got $storedTs",
            storedTs in before..after,
        )
    }

    @Test
    fun `falls back to currentTimeMillis when notification when is in the future`() {
        val before = System.currentTimeMillis()
        val oneHourFromNow = before + 60 * 60_000L
        val sbn = buildSbn(
            "com.senseonics.eversense365.us",
            titleNotification("120", whenMs = oneHourFromNow),
        )

        listener.onNotificationPosted(sbn)
        val after = System.currentTimeMillis()

        val intent = nextStartedService()
        assertNotNull(intent)
        val storedTs = intent!!.getLongExtra(GlucoseNotificationListener.EXTRA_TIMESTAMP, 0L)
        // A future `when` (clock skew, buggy app) must not be stored verbatim — it
        // would corrupt the bucket dedup and put the row "after now" in queries.
        assertTrue(
            "Expected fallback timestamp in [$before, $after], got $storedTs",
            storedTs in before..after,
        )
    }

    @Test
    fun `fresh reading uses notification when as timestamp`() {
        // Sanity: in normal operation, `when` is set to roughly now by the source,
        // so the resulting reading is still effectively timestamped "now". This
        // guards against accidental regressions that would shift fresh readings.
        val nowish = System.currentTimeMillis() - 500L
        val sbn = buildSbn(
            "com.senseonics.eversense365.us",
            titleNotification("120", whenMs = nowish),
        )

        listener.onNotificationPosted(sbn)

        val intent = nextStartedService()
        assertNotNull(intent)
        val storedTs = intent!!.getLongExtra(GlucoseNotificationListener.EXTRA_TIMESTAMP, 0L)
        assertEquals(nowish, storedTs)
    }
}
