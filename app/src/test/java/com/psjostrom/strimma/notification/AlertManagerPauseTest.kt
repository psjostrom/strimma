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
}
