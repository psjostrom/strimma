package com.psjostrom.strimma.receiver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val PKG = "com.camdiab.fx_alert.mmoll"
private const val WINDOW_MS = 10L * 60 * 1000

/**
 * Pure-logic tests for the stuck-value detector. Robolectric is used only for a real
 * SharedPreferences (the only Android boundary). Wall-clock and elapsed-clock are injected
 * via lambdas so window expiry, wall-clock jumps, and persistence rehydration can be tested
 * deterministically without sleeping or touching the system clock.
 */
@RunWith(RobolectricTestRunner::class)
class StuckValueDetectorTest {

    private fun prefs() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("test_stuck", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    private class FakeClock(initialNow: Long, initialElapsed: Long) {
        var nowMs: Long = initialNow
        var elapsedMs: Long = initialElapsed
        fun advance(ms: Long) { nowMs += ms; elapsedMs += ms }
    }

    private fun detector(prefs: android.content.SharedPreferences, clock: FakeClock) =
        StuckValueDetector(prefs, now = { clock.nowMs }, elapsed = { clock.elapsedMs })

    @Test
    fun `isStuck returns false before any state is recorded`() {
        val clock = FakeClock(initialNow = 1000, initialElapsed = 1000)
        val d = detector(prefs(), clock)
        assertFalse(d.isStuck(PKG, sgv = 106, windowMs = WINDOW_MS))
    }

    @Test
    fun `isStuck returns false when only an accept has been recorded (no glitch yet)`() {
        val clock = FakeClock(initialNow = 1000, initialElapsed = 1000)
        val d = detector(prefs(), clock)
        d.recordAccept(PKG, sgv = 106)
        assertFalse(d.isStuck(PKG, sgv = 106, windowMs = WINDOW_MS))
    }

    @Test
    fun `same value after recordNoValue is detected as stuck`() {
        val clock = FakeClock(initialNow = 1000, initialElapsed = 1000)
        val d = detector(prefs(), clock)
        d.recordAccept(PKG, sgv = 106)
        d.recordNoValue(PKG)
        assertTrue(d.isStuck(PKG, sgv = 106, windowMs = WINDOW_MS))
    }

    @Test
    fun `different value after recordNoValue is not stuck`() {
        val clock = FakeClock(initialNow = 1000, initialElapsed = 1000)
        val d = detector(prefs(), clock)
        d.recordAccept(PKG, sgv = 106)
        d.recordNoValue(PKG)
        assertFalse(d.isStuck(PKG, sgv = 111, windowMs = WINDOW_MS))
    }

    @Test
    fun `recordAccept clears suspicion - subsequent same-value plateau is admitted`() {
        // The bug PR #231's first revision had: a different value passing through
        // didn't clear the no-value timestamp, so a later legitimate same-value plateau
        // was still rejected as stuck. recordAccept must wipe the suspicion entirely.
        val clock = FakeClock(initialNow = 1000, initialElapsed = 1000)
        val d = detector(prefs(), clock)
        d.recordAccept(PKG, sgv = 106)
        d.recordNoValue(PKG)
        // Sensor recovers with a different value:
        d.recordAccept(PKG, sgv = 111)
        // Within the original suspicion window, a same-value plateau on the recovered value
        // must be admitted — the prior glitch should no longer apply.
        clock.advance(2 * 60 * 1000)
        assertFalse(
            "after a different-value accept, the next same-value reading must NOT be stuck",
            d.isStuck(PKG, sgv = 111, windowMs = WINDOW_MS)
        )
    }

    @Test
    fun `suspicion expires after the window elapses`() {
        val clock = FakeClock(initialNow = 1000, initialElapsed = 1000)
        val d = detector(prefs(), clock)
        d.recordAccept(PKG, sgv = 106)
        d.recordNoValue(PKG)
        assertTrue(d.isStuck(PKG, sgv = 106, windowMs = WINDOW_MS))
        clock.advance(WINDOW_MS + 1)
        assertFalse("after the window expires, suspicion must clear", d.isStuck(PKG, sgv = 106, windowMs = WINDOW_MS))
    }

    @Test
    fun `wall-clock backwards jump does not extend suspicion (uses elapsed time)`() {
        // A backward NTP correction or manual time change must not turn a 1-min-old glitch
        // into an indefinite suspicion window. The detector keys timing on elapsed time,
        // not wall-clock, so this is immune.
        val clock = FakeClock(initialNow = 10_000_000, initialElapsed = 1000)
        val d = detector(prefs(), clock)
        d.recordAccept(PKG, sgv = 106)
        d.recordNoValue(PKG)
        // Wall clock jumps backwards 2 hours; elapsed time advances 1 minute.
        clock.nowMs -= 2 * 60 * 60 * 1000L
        clock.elapsedMs += 60 * 1000L
        assertTrue("1 min after the glitch (elapsed), still in suspicion", d.isStuck(PKG, sgv = 106, windowMs = WINDOW_MS))
        // Elapsed advances past the window — suspicion must expire regardless of wall clock.
        clock.elapsedMs += WINDOW_MS
        assertFalse("after window elapses, suspicion clears even with backwards wall clock",
            d.isStuck(PKG, sgv = 106, windowMs = WINDOW_MS))
    }

    @Test
    fun `recordNoValue extends suspicion - each --- restarts the window`() {
        val clock = FakeClock(initialNow = 1000, initialElapsed = 1000)
        val d = detector(prefs(), clock)
        d.recordAccept(PKG, sgv = 106)
        d.recordNoValue(PKG)
        clock.advance(8 * 60 * 1000)  // 8 min in
        assertTrue(d.isStuck(PKG, sgv = 106, windowMs = WINDOW_MS))
        d.recordNoValue(PKG)  // another --- arrives
        clock.advance(5 * 60 * 1000)  // 13 min after first ---, but only 5 min after second
        assertTrue("second --- restarts the window", d.isStuck(PKG, sgv = 106, windowMs = WINDOW_MS))
    }

    @Test
    fun `state survives detector recreation when persisted entry is recent`() {
        val sharedPrefs = prefs()
        val clock1 = FakeClock(initialNow = 10_000_000, initialElapsed = 5_000_000)
        val d1 = detector(sharedPrefs, clock1)
        d1.recordAccept(PKG, sgv = 106)
        d1.recordNoValue(PKG)

        // Listener rebound: new detector instance, fresh elapsed clock (Android reboot would
        // also reset elapsedRealtime), wall clock has advanced 1 min during the rebind gap.
        val clock2 = FakeClock(initialNow = clock1.nowMs + 60_000, initialElapsed = 0)
        val d2 = detector(sharedPrefs, clock2)
        assertTrue(
            "after rebind, persisted no-value within window must still suspect stuck reposts",
            d2.isStuck(PKG, sgv = 106, windowMs = WINDOW_MS)
        )
    }

    @Test
    fun `persisted entry older than 30 minutes is discarded on load`() {
        val sharedPrefs = prefs()
        val clock1 = FakeClock(initialNow = 10_000_000, initialElapsed = 5_000_000)
        val d1 = detector(sharedPrefs, clock1)
        d1.recordAccept(PKG, sgv = 106)
        d1.recordNoValue(PKG)

        // Wall clock advanced 31 min before the listener rebound — too stale to trust.
        val clock2 = FakeClock(initialNow = clock1.nowMs + 31 * 60 * 1000L, initialElapsed = 0)
        val d2 = detector(sharedPrefs, clock2)
        assertFalse(
            "persisted suspicion older than the persistence cap must be discarded",
            d2.isStuck(PKG, sgv = 106, windowMs = WINDOW_MS)
        )
    }

    @Test
    fun `persisted lastSgv survives recreation independent of suspicion`() {
        // Even after suspicion expires, the lastSgv is remembered so a future glitch can
        // identify a same-value repost without needing the listener to first re-observe a
        // forwarded value.
        val sharedPrefs = prefs()
        val clock1 = FakeClock(initialNow = 10_000_000, initialElapsed = 5_000_000)
        val d1 = detector(sharedPrefs, clock1)
        d1.recordAccept(PKG, sgv = 106)

        val clock2 = FakeClock(initialNow = clock1.nowMs + 60_000, initialElapsed = 0)
        val d2 = detector(sharedPrefs, clock2)
        // No suspicion yet — first call should be false:
        assertFalse(d2.isStuck(PKG, sgv = 106, windowMs = WINDOW_MS))
        // Now a --- arrives in the new instance — same-value repost is detected without
        // needing to re-record the accept first.
        d2.recordNoValue(PKG)
        assertTrue(d2.isStuck(PKG, sgv = 106, windowMs = WINDOW_MS))
    }

    @Test
    fun `state is per-package - one package's suspicion does not affect another`() {
        val pkgA = "com.camdiab.fx_alert.mmoll"
        val pkgB = "com.dexcom.g7"
        val clock = FakeClock(initialNow = 1000, initialElapsed = 1000)
        val d = detector(prefs(), clock)
        d.recordAccept(pkgA, sgv = 106)
        d.recordNoValue(pkgA)
        assertTrue(d.isStuck(pkgA, sgv = 106, windowMs = WINDOW_MS))
        assertFalse("packageB has no state, must not be stuck", d.isStuck(pkgB, sgv = 106, windowMs = WINDOW_MS))
    }
}
