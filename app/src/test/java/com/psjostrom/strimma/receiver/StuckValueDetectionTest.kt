package com.psjostrom.strimma.receiver

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.widget.RemoteViews
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
 * Wiring tests for the stuck-value heuristic at the listener level. Direct logic coverage
 * for the detector itself lives in `StuckValueDetectorTest`; this file verifies that the
 * listener arms and consults the detector correctly across all parse paths (contentView,
 * extras, ticker) and that legitimate scenarios are not over-blocked.
 *
 * Regression target: when a source app (notably CamAPS) loses its sensor it alternates
 * "---" with the last-known BG value, each repost stamped with a fresh `notification.when`.
 * Bucket dedup in the pipeline doesn't catch consecutive same-SGV stores in different 1-min
 * buckets, so the graph would fill with a flat plateau and the no-data alert would be
 * suppressed (the listener keeps "receiving" readings).
 *
 * The detection signal differentiates two cases the prior mode-word filter (PR #222)
 * conflated: (a) pump-only "Attempting" with a fresh, changing CGM stream — readings
 * that the mode-word filter wrongly rejected — and (b) full sensor disconnect with a
 * stuck value re-posted: in (b) the parse-null "---" notifications interleave the value
 * reposts; in (a) they don't.
 */
@RunWith(RobolectricTestRunner::class)
class StuckValueDetectionTest {

    private fun setupListener(): GlucoseNotificationListener {
        val listener = Robolectric.setupService(GlucoseNotificationListener::class.java)
        listener.getSharedPreferences("strimma_sync", Context.MODE_PRIVATE)
            .edit().putString("glucose_source", GlucoseSource.COMPANION.name).apply()
        // Detector persists per-package state to its own prefs file. Clear between tests
        // so each test starts from a known empty state.
        listener.getSharedPreferences("strimma_stuck", Context.MODE_PRIVATE)
            .edit().clear().commit()
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

    private fun sbnFor(notification: Notification, postTime: Long = System.currentTimeMillis()): StatusBarNotification {
        @Suppress("DEPRECATION")
        return StatusBarNotification(
            "com.camdiab.fx_alert.mmoll", "com.camdiab.fx_alert.mmoll", 1, null, 0, 0, 0,
            notification, android.os.Process.myUserHandle(),
            postTime,
        )
    }

    private fun nextStartedService() =
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedService

    @Test
    fun `first notification with stuck value still forwards (no prior forwarded value)`() {
        // Boot scenario: the listener has no memory of the prior reading, so even if the
        // sensor is currently disconnected we accept the first CamAPS post. The downstream
        // pipeline will attribute a fresh ts and store the value once. The heuristic only
        // kicks in for the 2nd, 3rd, ... reposts of the same value after a "---".
        val listener = setupListener()

        listener.onNotificationPosted(sbnFor(camapsNotification(status = "Attempting", value = "5,9")))

        assertNotNull("first 5,9 must pass through", nextStartedService())
    }

    @Test
    fun `pump-only Attempting with continuously changing values all pass through`() {
        // Regression for the false-rejection case: pump-loop is "Attempting" but the CGM
        // is fine. Values change every notification. None of these should be dropped —
        // there is no "---" to mark the stream as suspect, and even if there were, the
        // value differs from the last forwarded one.
        val listener = setupListener()
        val values = listOf("10,6", "10,7", "10,8", "10,9", "11,0", "11,3", "11,6", "11,8", "11,9")

        for (value in values) {
            listener.onNotificationPosted(sbnFor(camapsNotification(status = "Attempting", value = value)))
            assertNotNull("$value must pass through", nextStartedService())
        }
    }

    @Test
    fun `May-11 alternation pattern - reposts of same value after --- are dropped`() {
        // From strimma-2026-05-11.log lines 3786..3808: --- / 5,9 / 5,9 / --- / 5,9 with
        // each post having a fresh notification.when. Bucket dedup in the pipeline can't
        // catch this because each repost lands in its own minute bucket. The first 5,9
        // is admitted (we don't know it's stuck yet); subsequent same-value reposts that
        // follow a "---" must be rejected.
        val listener = setupListener()

        // Initial fresh reading establishes the "last forwarded" state.
        listener.onNotificationPosted(sbnFor(camapsNotification(status = "On", value = "5,9")))
        assertNotNull("initial 5,9 must pass through", nextStartedService())

        // Sensor drops → "---" notification. Parser returns null (no value), heuristic
        // marks the package as glitched.
        listener.onNotificationPosted(sbnFor(camapsNotification(status = "Attempting", value = "---")))
        assertNull("--- notification must not start the service", nextStartedService())

        // Subsequent same-value reposts must now be rejected.
        listener.onNotificationPosted(sbnFor(camapsNotification(status = "Attempting", value = "5,9")))
        assertNull("repost of 5,9 after --- must be dropped", nextStartedService())

        listener.onNotificationPosted(sbnFor(camapsNotification(status = "Attempting", value = "5,9")))
        assertNull("second repost of 5,9 after --- must be dropped", nextStartedService())

        listener.onNotificationPosted(sbnFor(camapsNotification(status = "Attempting", value = "---")))
        assertNull("interleaved --- must not start the service", nextStartedService())

        listener.onNotificationPosted(sbnFor(camapsNotification(status = "Attempting", value = "5,9")))
        assertNull("third repost of 5,9 after --- must be dropped", nextStartedService())
    }

    @Test
    fun `recovery to a different value clears suspicion - subsequent same-value plateau is admitted`() {
        // Sensor recovers and starts producing fresh readings again. The first DIFFERENT
        // value arriving after the glitch must pass through, AND the heuristic must reset
        // so a subsequent legitimate plateau on the recovered value isn't falsely rejected.
        // This is the medical-safety case: a real BG plateau within 10 min of any prior
        // sensor blip must NOT be dropped — that would suppress data and trigger a false
        // stale-data alert while the sensor is healthy.
        val listener = setupListener()

        listener.onNotificationPosted(sbnFor(camapsNotification(status = "On", value = "5,9")))
        assertNotNull(nextStartedService())

        listener.onNotificationPosted(sbnFor(camapsNotification(status = "Attempting", value = "---")))
        assertNull(nextStartedService())

        // Same value repost → dropped (sensor still glitching).
        listener.onNotificationPosted(sbnFor(camapsNotification(status = "Attempting", value = "5,9")))
        assertNull(nextStartedService())

        // Sensor recovers with a fresh different value.
        listener.onNotificationPosted(sbnFor(camapsNotification(status = "On", value = "6,2")))
        val recovered = nextStartedService()
        assertNotNull("recovered fresh value must pass through", recovered)
        assertEquals(GlucoseNotificationListener.ACTION_GLUCOSE_RECEIVED, recovered!!.action)

        // Now the real test of "clears suspicion": a legitimate plateau on the recovered
        // value within the suspicion window must be admitted, not dropped as stuck.
        listener.onNotificationPosted(sbnFor(camapsNotification(status = "On", value = "6,2")))
        assertNotNull(
            "plateau on recovered value must NOT be dropped - the prior --- is no longer relevant",
            nextStartedService()
        )

        listener.onNotificationPosted(sbnFor(camapsNotification(status = "On", value = "6,2")))
        assertNotNull("further plateau readings must continue to pass", nextStartedService())
    }

    @Test
    fun `--- arriving via extras-only notification still arms the detector`() {
        // The contentView path is the common case but not the only one — for some apps and
        // some notification states, RemoteViews.apply() throws (the listener catches that
        // and falls through to extras/ticker). The detector must arm whichever source
        // delivers the "---" so a subsequent stuck-value repost via contentView is caught.
        val listener = setupListener()

        // Establish baseline forwarded SGV via the normal contentView path.
        listener.onNotificationPosted(sbnFor(camapsNotification(status = "On", value = "5,9")))
        assertNotNull(nextStartedService())

        // Sensor drop posted with no contentView — only EXTRA_TITLE carries "---".
        @Suppress("DEPRECATION")
        val noValueExtrasOnly = Notification().apply {
            extras = Bundle().apply { putString(Notification.EXTRA_TITLE, "---") }
            flags = Notification.FLAG_ONGOING_EVENT
        }
        listener.onNotificationPosted(sbnFor(noValueExtrasOnly))
        assertNull("--- via extras must not start the service", nextStartedService())

        // Now a same-value repost via contentView — the extras-only --- must have armed
        // the detector so this is dropped.
        listener.onNotificationPosted(sbnFor(camapsNotification(status = "Attempting", value = "5,9")))
        assertNull(
            "repost after extras-only --- must be dropped (detector arms on any source)",
            nextStartedService()
        )
    }

    @Test
    fun `stable BG with no --- interruption is never blocked`() {
        // A genuinely flat plateau (overnight, post-correction, etc.) produces many
        // consecutive identical readings. Without a "---" in the stream, the heuristic
        // must not fire — these are real readings.
        val listener = setupListener()

        repeat(6) {
            listener.onNotificationPosted(sbnFor(camapsNotification(status = "On", value = "5,9")))
            assertNotNull("stable 5,9 read $it must pass through", nextStartedService())
        }
    }

    @Test
    fun `interleaved --- with changing values still admits the changing values`() {
        // Brief sensor blips interleaved with fresh, changing CGM data: the heuristic
        // only suppresses when value matches lastForwarded, so changing values must
        // continue to flow through even while a recent --- is on record.
        val listener = setupListener()

        listener.onNotificationPosted(sbnFor(camapsNotification(status = "On", value = "8,1")))
        assertNotNull(nextStartedService())

        listener.onNotificationPosted(sbnFor(camapsNotification(status = "On", value = "---")))
        assertNull(nextStartedService())

        listener.onNotificationPosted(sbnFor(camapsNotification(status = "On", value = "8,3")))
        assertNotNull("8,3 differs from last forwarded 8,1, must pass through", nextStartedService())

        listener.onNotificationPosted(sbnFor(camapsNotification(status = "On", value = "8,5")))
        assertNotNull("8,5 differs from last forwarded 8,3, must pass through", nextStartedService())
    }
}
