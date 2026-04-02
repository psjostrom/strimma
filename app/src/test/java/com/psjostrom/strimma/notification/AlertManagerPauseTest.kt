package com.psjostrom.strimma.notification

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlertManagerPauseTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("test_pause_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @Test
    fun `pauseCategory stores expiry timestamp`() {
        val durationMs = 60 * 60 * 1000L // 1 hour
        val beforePause = System.currentTimeMillis()

        AlertManager.pauseCategory(prefs, AlertCategory.LOW, durationMs)

        val afterPause = System.currentTimeMillis()
        val stored = prefs.getLong("pause_low", 0L)

        assertTrue("Stored expiry should be in the future", stored > beforePause)
        assertTrue("Stored expiry should be approximately now + duration",
            stored >= beforePause + durationMs && stored <= afterPause + durationMs)
    }

    @Test
    fun `isCategoryPaused returns true when pause is active`() {
        val durationMs = 60 * 60 * 1000L // 1 hour
        AlertManager.pauseCategory(prefs, AlertCategory.LOW, durationMs)

        assertTrue(AlertManager.isCategoryPaused(prefs, AlertCategory.LOW))
    }

    @Test
    fun `isCategoryPaused returns false when no pause set`() {
        assertFalse(AlertManager.isCategoryPaused(prefs, AlertCategory.LOW))
    }

    @Test
    fun `isCategoryPaused returns false and clears expired pause`() {
        // Set a pause that expired 1 hour ago
        val expiredTime = System.currentTimeMillis() - (60 * 60 * 1000L)
        prefs.edit().putLong("pause_low", expiredTime).apply()

        assertFalse("Should return false for expired pause",
            AlertManager.isCategoryPaused(prefs, AlertCategory.LOW))

        assertEquals("Expired pause should be cleared from prefs",
            0L, prefs.getLong("pause_low", 0L))
    }

    @Test
    fun `cancelPause removes the key`() {
        AlertManager.pauseCategory(prefs, AlertCategory.LOW, 60 * 60 * 1000L)
        assertTrue("Pause should be active before cancel",
            AlertManager.isCategoryPaused(prefs, AlertCategory.LOW))

        AlertManager.cancelPause(prefs, AlertCategory.LOW)

        assertFalse("Pause should be inactive after cancel",
            AlertManager.isCategoryPaused(prefs, AlertCategory.LOW))
        assertEquals("Key should be removed from prefs",
            0L, prefs.getLong("pause_low", 0L))
    }

    @Test
    fun `pauseExpiryMs returns expiry when active`() {
        val durationMs = 60 * 60 * 1000L // 1 hour
        val beforePause = System.currentTimeMillis()

        AlertManager.pauseCategory(prefs, AlertCategory.LOW, durationMs)

        val afterPause = System.currentTimeMillis()
        val expiry = AlertManager.pauseExpiryMs(prefs, AlertCategory.LOW)

        assertTrue("Should return non-null expiry", expiry != null)
        assertTrue("Expiry should be in the future", expiry!! > beforePause)
        assertTrue("Expiry should be approximately now + duration",
            expiry >= beforePause + durationMs && expiry <= afterPause + durationMs)
    }

    @Test
    fun `pauseExpiryMs returns null when not paused`() {
        assertNull(AlertManager.pauseExpiryMs(prefs, AlertCategory.LOW))
    }

    @Test
    fun `pauseExpiryMs returns null and clears expired pause`() {
        // Set a pause that expired 1 hour ago
        val expiredTime = System.currentTimeMillis() - (60 * 60 * 1000L)
        prefs.edit().putLong("pause_low", expiredTime).apply()

        assertNull("Should return null for expired pause",
            AlertManager.pauseExpiryMs(prefs, AlertCategory.LOW))

        assertEquals("Expired pause should be cleared from prefs",
            0L, prefs.getLong("pause_low", 0L))
    }

    @Test
    fun `low and high pauses are independent`() {
        AlertManager.pauseCategory(prefs, AlertCategory.LOW, 60 * 60 * 1000L)

        assertTrue("Low should be paused", AlertManager.isCategoryPaused(prefs, AlertCategory.LOW))
        assertFalse("High should not be paused", AlertManager.isCategoryPaused(prefs, AlertCategory.HIGH))

        AlertManager.pauseCategory(prefs, AlertCategory.HIGH, 30 * 60 * 1000L)

        assertTrue("Low should still be paused", AlertManager.isCategoryPaused(prefs, AlertCategory.LOW))
        assertTrue("High should now be paused", AlertManager.isCategoryPaused(prefs, AlertCategory.HIGH))

        AlertManager.cancelPause(prefs, AlertCategory.LOW)

        assertFalse("Low should no longer be paused", AlertManager.isCategoryPaused(prefs, AlertCategory.LOW))
        assertTrue("High should still be paused", AlertManager.isCategoryPaused(prefs, AlertCategory.HIGH))
    }

    // --- Severity-aware pause tests ---

    @Test
    fun `pauseCategory stores level in prefs`() {
        AlertManager.pauseCategory(prefs, AlertCategory.LOW, 3_600_000L, AlertManager.ALERT_LEVEL_SOON)

        assertEquals(AlertManager.ALERT_LEVEL_SOON, prefs.getInt("pause_low_level", -1))
    }

    @Test
    fun `pauseCategory defaults to URGENT level`() {
        AlertManager.pauseCategory(prefs, AlertCategory.LOW, 3_600_000L)

        assertEquals(AlertManager.ALERT_LEVEL_URGENT, prefs.getInt("pause_low_level", -1))
    }

    @Test
    fun `isCategoryPausedAtLevel with SOON level blocks only SOON alerts`() {
        AlertManager.pauseCategory(prefs, AlertCategory.LOW, 3_600_000L, AlertManager.ALERT_LEVEL_SOON)

        assertTrue(AlertManager.isCategoryPausedAtLevel(prefs, AlertCategory.LOW, AlertManager.ALERT_LEVEL_SOON))
        assertFalse(AlertManager.isCategoryPausedAtLevel(prefs, AlertCategory.LOW, AlertManager.ALERT_LEVEL_REGULAR))
        assertFalse(AlertManager.isCategoryPausedAtLevel(prefs, AlertCategory.LOW, AlertManager.ALERT_LEVEL_URGENT))
    }

    @Test
    fun `isCategoryPausedAtLevel with REGULAR level blocks SOON and REGULAR`() {
        AlertManager.pauseCategory(prefs, AlertCategory.LOW, 3_600_000L, AlertManager.ALERT_LEVEL_REGULAR)

        assertTrue(AlertManager.isCategoryPausedAtLevel(prefs, AlertCategory.LOW, AlertManager.ALERT_LEVEL_SOON))
        assertTrue(AlertManager.isCategoryPausedAtLevel(prefs, AlertCategory.LOW, AlertManager.ALERT_LEVEL_REGULAR))
        assertFalse(AlertManager.isCategoryPausedAtLevel(prefs, AlertCategory.LOW, AlertManager.ALERT_LEVEL_URGENT))
    }

    @Test
    fun `isCategoryPausedAtLevel with URGENT level blocks all alerts`() {
        AlertManager.pauseCategory(prefs, AlertCategory.LOW, 3_600_000L, AlertManager.ALERT_LEVEL_URGENT)

        assertTrue(AlertManager.isCategoryPausedAtLevel(prefs, AlertCategory.LOW, AlertManager.ALERT_LEVEL_SOON))
        assertTrue(AlertManager.isCategoryPausedAtLevel(prefs, AlertCategory.LOW, AlertManager.ALERT_LEVEL_REGULAR))
        assertTrue(AlertManager.isCategoryPausedAtLevel(prefs, AlertCategory.LOW, AlertManager.ALERT_LEVEL_URGENT))
    }

    @Test
    fun `isCategoryPausedAtLevel returns false when not paused`() {
        assertFalse(AlertManager.isCategoryPausedAtLevel(prefs, AlertCategory.LOW, AlertManager.ALERT_LEVEL_SOON))
    }

    @Test
    fun `isCategoryPausedAtLevel clears expired pause and returns false`() {
        val expiredTime = System.currentTimeMillis() - 1000
        prefs.edit().putLong("pause_low", expiredTime).putInt("pause_low_level", AlertManager.ALERT_LEVEL_URGENT).apply()

        assertFalse(AlertManager.isCategoryPausedAtLevel(prefs, AlertCategory.LOW, AlertManager.ALERT_LEVEL_SOON))
        assertEquals(0L, prefs.getLong("pause_low", 0L))
        assertEquals(-1, prefs.getInt("pause_low_level", -1))
    }

    @Test
    fun `cancelPause clears level key`() {
        AlertManager.pauseCategory(prefs, AlertCategory.LOW, 3_600_000L, AlertManager.ALERT_LEVEL_REGULAR)

        AlertManager.cancelPause(prefs, AlertCategory.LOW)

        assertEquals(-1, prefs.getInt("pause_low_level", -1))
        assertFalse(AlertManager.isCategoryPausedAtLevel(prefs, AlertCategory.LOW, AlertManager.ALERT_LEVEL_SOON))
    }

    // --- Behavioral tests: verify gating pattern used by checkReading ---

    @Test
    fun `paused LOW gates all low alert checks`() {
        AlertManager.pauseCategory(prefs, AlertCategory.LOW, 3_600_000L)

        val lowPaused = AlertManager.isCategoryPaused(prefs, AlertCategory.LOW)
        val highPaused = AlertManager.isCategoryPaused(prefs, AlertCategory.HIGH)

        assertTrue("LOW should be paused", lowPaused)
        assertFalse("HIGH should not be paused", highPaused)

        // Simulate checkReading gating: when lowPaused, no low/urgent-low alerts fire
        val mgdl = 40.0 // dangerously low — would normally trigger urgent low
        val urgentLowThreshold = 54f
        val wouldFireUrgentLow = !lowPaused && mgdl <= urgentLowThreshold
        assertFalse("Urgent low should NOT fire when LOW is paused", wouldFireUrgentLow)

        // HIGH alerts should still fire normally
        val mgdlHigh = 300.0
        val urgentHighThreshold = 250f
        val wouldFireUrgentHigh = !highPaused && mgdlHigh >= urgentHighThreshold
        assertTrue("Urgent high SHOULD fire when only LOW is paused", wouldFireUrgentHigh)
    }

    @Test
    fun `paused HIGH gates all high alert checks`() {
        AlertManager.pauseCategory(prefs, AlertCategory.HIGH, 3_600_000L)

        val highPaused = AlertManager.isCategoryPaused(prefs, AlertCategory.HIGH)

        assertTrue("HIGH should be paused", highPaused)

        val mgdl = 300.0
        val urgentHighThreshold = 250f
        val wouldFireUrgentHigh = !highPaused && mgdl >= urgentHighThreshold
        assertFalse("Urgent high should NOT fire when HIGH is paused", wouldFireUrgentHigh)
    }

    @Test
    fun `expired pause does not gate alerts`() {
        // Set a pause that expired 1 second ago
        prefs.edit().putLong("pause_low", System.currentTimeMillis() - 1000).apply()

        val lowPaused = AlertManager.isCategoryPaused(prefs, AlertCategory.LOW)

        assertFalse("Expired pause should not gate", lowPaused)

        val mgdl = 50.0
        val urgentLowThreshold = 54f
        val wouldFireUrgentLow = !lowPaused && mgdl <= urgentLowThreshold
        assertTrue("Urgent low SHOULD fire when pause has expired", wouldFireUrgentLow)
    }

    @Test
    fun `pause then cancel allows alerts again`() {
        AlertManager.pauseCategory(prefs, AlertCategory.LOW, 3_600_000L)
        assertTrue("Should be paused", AlertManager.isCategoryPaused(prefs, AlertCategory.LOW))

        AlertManager.cancelPause(prefs, AlertCategory.LOW)
        val lowPaused = AlertManager.isCategoryPaused(prefs, AlertCategory.LOW)

        assertFalse("Should not be paused after cancel", lowPaused)

        val mgdl = 50.0
        val urgentLowThreshold = 54f
        val wouldFireUrgentLow = !lowPaused && mgdl <= urgentLowThreshold
        assertTrue("Urgent low SHOULD fire after pause cancelled", wouldFireUrgentLow)
    }
}
