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
 * Tests that the reading timestamp comes from the source CGM notification's
 * `when` field rather than the moment Strimma processes it, with safe fallbacks
 * when `when` is unusable.
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
 * Resolution chain (preferred → fallback):
 *  1. `notification.when` — set by the source app, preserved across rebinds.
 *  2. `sbn.postTime` — set by the system when first posted, also preserved.
 *  3. `System.currentTimeMillis()` — only if both are zero/in the future.
 *
 * Falling back to `postTime` (not `now`) keeps rebind-resilience intact even
 * for source apps that don't set `when` at all.
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

    private fun buildSbn(
        packageName: String,
        notification: Notification,
        postTime: Long = System.currentTimeMillis(),
    ): StatusBarNotification {
        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT
        @Suppress("DEPRECATION")
        return StatusBarNotification(
            packageName, packageName, 1, null, 0, 0, 0,
            notification, android.os.Process.myUserHandle(),
            postTime,
        )
    }

    private fun titleNotification(title: String, whenMs: Long): Notification =
        Notification().apply {
            extras = Bundle().apply { putString(Notification.EXTRA_TITLE, title) }
            `when` = whenMs
        }

    private fun nextStoredTimestamp(): Long {
        val intent: Intent? = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNotNull("Listener should have started the service", intent)
        return intent!!.getLongExtra(GlucoseNotificationListener.EXTRA_TIMESTAMP, -1L)
    }

    @Test
    fun `timestamp comes from notification when, not from currentTimeMillis`() {
        val fiveMinutesAgo = System.currentTimeMillis() - 5 * 60_000L
        val sbn = buildSbn(
            CAMAPS_PKG,
            titleNotification("120", whenMs = fiveMinutesAgo),
        )

        listener.onNotificationPosted(sbn)

        assertEquals(
            "Reading timestamp should be the notification's `when`, not now",
            fiveMinutesAgo, nextStoredTimestamp(),
        )
    }

    @Test
    fun `falls back to postTime when notification when is zero`() {
        // when=0 is the documented "no timestamp" sentinel some apps use. Falling
        // back to sbn.postTime (also preserved across rebinds) keeps the fix's
        // rebind-resilience instead of regressing to "stamp on receive."
        val tenMinutesAgo = System.currentTimeMillis() - 10 * 60_000L
        val sbn = buildSbn(
            CAMAPS_PKG,
            titleNotification("120", whenMs = 0L),
            postTime = tenMinutesAgo,
        )

        listener.onNotificationPosted(sbn)

        assertEquals(
            "when=0 must fall back to postTime, not currentTimeMillis",
            tenMinutesAgo, nextStoredTimestamp(),
        )
    }

    @Test
    fun `falls back to postTime when notification when is in the future`() {
        // A future `when` (clock skew, buggy app) must not be stored verbatim — it
        // would corrupt the bucket dedup and put the row "after now" in queries.
        val twoMinutesAgo = System.currentTimeMillis() - 2 * 60_000L
        val oneHourFromNow = System.currentTimeMillis() + 60 * 60_000L
        val sbn = buildSbn(
            CAMAPS_PKG,
            titleNotification("120", whenMs = oneHourFromNow),
            postTime = twoMinutesAgo,
        )

        listener.onNotificationPosted(sbn)

        assertEquals(
            "future when must fall back to postTime, not currentTimeMillis",
            twoMinutesAgo, nextStoredTimestamp(),
        )
    }

    @Test
    fun `falls back to currentTimeMillis when both when and postTime are invalid`() {
        // Last-resort safety net: if the source app sets when=0 AND the SBN
        // somehow has postTime=0, we have no preserved timestamp to use, so we
        // accept the receive-time as the least-bad option.
        val before = System.currentTimeMillis()
        val sbn = buildSbn(
            CAMAPS_PKG,
            titleNotification("120", whenMs = 0L),
            postTime = 0L,
        )

        listener.onNotificationPosted(sbn)
        val after = System.currentTimeMillis()

        val storedTs = nextStoredTimestamp()
        assertTrue(
            "Expected fallback to now in [$before, $after], got $storedTs",
            storedTs in before..after,
        )
    }

    @Test
    fun `rebind redelivery preserves the original notification when`() {
        // The headline regression this fix protects: Android re-fires
        // onNotificationPosted for every active notification when the listener
        // (re)binds. A second delivery of the SAME SBN must reuse the original
        // `when`, not refresh to now — otherwise the stale-data alert is
        // silently suppressed and the foreground notification shows "0m"
        // against a value many minutes old.
        val originalWhen = System.currentTimeMillis() - 10 * 60_000L
        val sbn = buildSbn(CAMAPS_PKG, titleNotification("120", whenMs = originalWhen))

        listener.onNotificationPosted(sbn)
        val firstTs = nextStoredTimestamp()
        // Simulate Android re-firing for the same SBN on listener rebind.
        listener.onNotificationPosted(sbn)
        val secondTs = nextStoredTimestamp()

        assertEquals("First delivery uses notification.when", originalWhen, firstTs)
        assertEquals(
            "Rebind redelivery must reuse the original `when`, not refresh to now",
            originalWhen, secondTs,
        )
    }

    @Test
    fun `fresh reading uses notification when as timestamp`() {
        // Sanity: in normal operation, `when` is set to roughly now by the source,
        // so the resulting reading is still effectively timestamped "now". This
        // guards against accidental regressions that would shift fresh readings.
        val nowish = System.currentTimeMillis() - 500L
        val sbn = buildSbn(CAMAPS_PKG, titleNotification("120", whenMs = nowish))

        listener.onNotificationPosted(sbn)

        assertEquals(nowish, nextStoredTimestamp())
    }

    private companion object {
        const val CAMAPS_PKG = "com.camdiab.fx_alert.mmoll"
    }
}
