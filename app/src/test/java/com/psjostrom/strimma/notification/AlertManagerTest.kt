package com.psjostrom.strimma.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
class AlertManagerTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var alertManager: AlertManager
    private lateinit var notificationManager: NotificationManager

    // Default thresholds (mg/dL): urgentLow=54, low=72, high=180, urgentHigh=234

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        val widgetSettings = WidgetSettingsRepository(context)
        settings = SettingsRepository(context, widgetSettings, createTestDataStore())
        alertManager = AlertManager(context, settings)
        alertManager.createChannels()
        notificationManager = context.getSystemService(NotificationManager::class.java)

        // Clear any snooze/pause prefs from previous test
        context.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE)
            .edit().clear().apply()

        // Clear any existing notifications
        notificationManager.cancelAll()

        // Reset all alert-related settings to defaults.
        // DataStore is a process-wide singleton — settings from previous tests persist.
        runBlocking {
            settings.setAlertLowEnabled(true)
            settings.setAlertHighEnabled(true)
            settings.setAlertUrgentLowEnabled(true)
            settings.setAlertUrgentHighEnabled(true)
            settings.setAlertStaleEnabled(true)
            settings.setAlertLowSoonEnabled(true)
            settings.setAlertHighSoonEnabled(true)
        }
    }

    private fun reading(sgv: Int, ts: Long = System.currentTimeMillis()): GlucoseReading =
        GlucoseReading(ts = ts, sgv = sgv, direction = "Flat", delta = 0.0)

    private fun isNotificationActive(id: Int): Boolean =
        notificationManager.activeNotifications.any { it.id == id }

    // -- Low/High threshold alerts --

    @Test
    fun `urgent low fires when BG at or below urgentLowThreshold`() = runTest {
        alertManager.checkReading(reading(54), emptyList(), 0)
        assertTrue(isNotificationActive(AlertManager.ALERT_URGENT_LOW_ID))
    }

    @Test
    fun `low fires when BG between urgentLow and lowThreshold`() = runTest {
        alertManager.checkReading(reading(60), emptyList(), 0)
        assertTrue(isNotificationActive(AlertManager.ALERT_LOW_ID))
    }

    @Test
    fun `high fires when BG above highThreshold`() = runTest {
        alertManager.checkReading(reading(200), emptyList(), 0)
        assertTrue(isNotificationActive(AlertManager.ALERT_HIGH_ID))
    }

    @Test
    fun `urgent high fires when BG at or above urgentHighThreshold`() = runTest {
        alertManager.checkReading(reading(234), emptyList(), 0)
        assertTrue(isNotificationActive(AlertManager.ALERT_URGENT_HIGH_ID))
    }

    @Test
    fun `no alert fires when BG is in range`() = runTest {
        alertManager.checkReading(reading(120), emptyList(), 0)
        assertFalse(isNotificationActive(AlertManager.ALERT_LOW_ID))
        assertFalse(isNotificationActive(AlertManager.ALERT_URGENT_LOW_ID))
        assertFalse(isNotificationActive(AlertManager.ALERT_HIGH_ID))
        assertFalse(isNotificationActive(AlertManager.ALERT_URGENT_HIGH_ID))
    }

    @Test
    fun `urgent low suppresses low notification`() = runTest {
        // First fire a low alert
        alertManager.checkReading(reading(60), emptyList(), 0)
        assertTrue(isNotificationActive(AlertManager.ALERT_LOW_ID))

        // Now go urgent low — low should be cancelled
        alertManager.checkReading(reading(50), emptyList(), 0)
        assertTrue(isNotificationActive(AlertManager.ALERT_URGENT_LOW_ID))
        assertFalse(isNotificationActive(AlertManager.ALERT_LOW_ID))
    }

    @Test
    fun `urgent high suppresses high notification`() = runTest {
        // First fire a high alert
        alertManager.checkReading(reading(200), emptyList(), 0)
        assertTrue(isNotificationActive(AlertManager.ALERT_HIGH_ID))

        // Now go urgent high — high should be cancelled
        alertManager.checkReading(reading(240), emptyList(), 0)
        assertTrue(isNotificationActive(AlertManager.ALERT_URGENT_HIGH_ID))
        assertFalse(isNotificationActive(AlertManager.ALERT_HIGH_ID))
    }

    @Test
    fun `alerts clear when value returns to range`() = runTest {
        alertManager.checkReading(reading(50), emptyList(), 0)
        assertTrue(isNotificationActive(AlertManager.ALERT_URGENT_LOW_ID))

        alertManager.checkReading(reading(120), emptyList(), 0)
        assertFalse(isNotificationActive(AlertManager.ALERT_URGENT_LOW_ID))
        assertFalse(isNotificationActive(AlertManager.ALERT_LOW_ID))
        assertFalse(isNotificationActive(AlertManager.ALERT_HIGH_ID))
        assertFalse(isNotificationActive(AlertManager.ALERT_URGENT_HIGH_ID))
    }

    // -- Enabled/disabled --

    @Test
    fun `disabled low alert does not fire`() = runTest {
        settings.setAlertLowEnabled(false)
        alertManager.checkReading(reading(60), emptyList(), 0)
        assertFalse(isNotificationActive(AlertManager.ALERT_LOW_ID))
    }

    @Test
    fun `disabled urgent low does not fire`() = runTest {
        settings.setAlertUrgentLowEnabled(false)
        alertManager.checkReading(reading(50), emptyList(), 0)
        assertFalse(isNotificationActive(AlertManager.ALERT_URGENT_LOW_ID))
    }

    // -- Stale alerts --

    @Test
    fun `stale fires when no reading within threshold`() = runTest {
        val staleTs = System.currentTimeMillis() - (AlertManager.STALE_THRESHOLD_MINUTES + 1) * 60_000L
        alertManager.checkStale(staleTs)
        assertTrue(isNotificationActive(AlertManager.ALERT_STALE_ID))
    }

    @Test
    fun `stale clears when fresh reading arrives`() = runTest {
        // First trigger stale
        val staleTs = System.currentTimeMillis() - (AlertManager.STALE_THRESHOLD_MINUTES + 1) * 60_000L
        alertManager.checkStale(staleTs)
        assertTrue(isNotificationActive(AlertManager.ALERT_STALE_ID))

        // Fresh reading
        alertManager.checkStale(System.currentTimeMillis())
        assertFalse(isNotificationActive(AlertManager.ALERT_STALE_ID))
    }

    @Test
    fun `stale disabled does not fire`() = runTest {
        settings.setAlertStaleEnabled(false)
        alertManager.checkStale(null)
        assertFalse(isNotificationActive(AlertManager.ALERT_STALE_ID))
    }

    @Test
    fun `stale respects snooze`() = runTest {
        // Snooze stale (uses per-alert snooze, not category pause)
        alertManager.snooze(AlertManager.ALERT_STALE_ID)

        val staleTs = System.currentTimeMillis() - (AlertManager.STALE_THRESHOLD_MINUTES + 1) * 60_000L
        alertManager.checkStale(staleTs)
        assertFalse(isNotificationActive(AlertManager.ALERT_STALE_ID))
    }

    // -- Snooze --

    @Test
    fun `snoozed alert does not fire`() = runTest {
        // Fire a low alert, snooze it, then check again
        alertManager.checkReading(reading(60), emptyList(), 0)
        assertTrue(isNotificationActive(AlertManager.ALERT_LOW_ID))

        alertManager.snooze(AlertManager.ALERT_LOW_ID)
        assertFalse(isNotificationActive(AlertManager.ALERT_LOW_ID))

        // Re-check — should not fire (snoozed)
        alertManager.checkReading(reading(60), emptyList(), 0)
        assertFalse(isNotificationActive(AlertManager.ALERT_LOW_ID))
    }

    @Test
    fun `snooze expires after duration`() = runTest {
        // Snooze low category, then manually expire the snooze
        alertManager.checkReading(reading(60), emptyList(), 0)
        alertManager.snooze(AlertManager.ALERT_LOW_ID)
        assertFalse(isNotificationActive(AlertManager.ALERT_LOW_ID))

        // Manually set the snooze expiry to the past
        val snoozePrefs = context.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE)
        val expiredTime = System.currentTimeMillis() - 1000
        snoozePrefs.edit().putLong(AlertCategory.LOW.prefsKey, expiredTime).apply()

        // Re-check — should fire again
        alertManager.checkReading(reading(60), emptyList(), 0)
        assertTrue(isNotificationActive(AlertManager.ALERT_LOW_ID))
    }

    // -- Predictive alerts --

    @Test
    fun `low soon fires when prediction crosses low threshold`() = runTest {
        val now = System.currentTimeMillis()
        // Create readings showing a falling trend from 90 to 80 over 10 minutes
        // (slope approx -1 mg/dL/min, should cross 72 in ~8 min from anchor)
        val readings = listOf(
            reading(90, now - 10 * 60_000L),
            reading(87, now - 8 * 60_000L),
            reading(84, now - 6 * 60_000L),
            reading(82, now - 4 * 60_000L),
            reading(80, now - 2 * 60_000L),
            reading(78, now)
        )
        alertManager.checkReading(readings.last(), readings, 15)
        assertTrue(isNotificationActive(AlertManager.ALERT_LOW_SOON_ID))
    }

    @Test
    fun `high soon fires when prediction crosses high threshold`() = runTest {
        val now = System.currentTimeMillis()
        // Create readings showing a rising trend approaching 180
        val readings = listOf(
            reading(160, now - 10 * 60_000L),
            reading(163, now - 8 * 60_000L),
            reading(166, now - 6 * 60_000L),
            reading(168, now - 4 * 60_000L),
            reading(171, now - 2 * 60_000L),
            reading(174, now)
        )
        alertManager.checkReading(readings.last(), readings, 15)
        assertTrue(isNotificationActive(AlertManager.ALERT_HIGH_SOON_ID))
    }

    @Test
    fun `low soon does not fire when already low`() = runTest {
        val now = System.currentTimeMillis()
        // Already below low threshold — predictive should not fire
        val readings = listOf(
            reading(68, now - 10 * 60_000L),
            reading(66, now - 8 * 60_000L),
            reading(64, now - 6 * 60_000L),
            reading(62, now - 4 * 60_000L),
            reading(60, now - 2 * 60_000L),
            reading(58, now)
        )
        alertManager.checkReading(readings.last(), readings, 15)
        // Low alert fires, but low soon should not
        assertTrue(isNotificationActive(AlertManager.ALERT_LOW_ID))
        assertFalse(isNotificationActive(AlertManager.ALERT_LOW_SOON_ID))
    }

    @Test
    fun `high soon does not fire when already high`() = runTest {
        val now = System.currentTimeMillis()
        // Already above high threshold — predictive should not fire
        val readings = listOf(
            reading(190, now - 10 * 60_000L),
            reading(193, now - 8 * 60_000L),
            reading(196, now - 6 * 60_000L),
            reading(199, now - 4 * 60_000L),
            reading(202, now - 2 * 60_000L),
            reading(205, now)
        )
        alertManager.checkReading(readings.last(), readings, 15)
        assertTrue(isNotificationActive(AlertManager.ALERT_HIGH_ID))
        assertFalse(isNotificationActive(AlertManager.ALERT_HIGH_SOON_ID))
    }

    @Test
    fun `predictive alerts respect pause`() = runTest {
        // Pause LOW category at SOON level
        alertManager.pauseAlertCategory(AlertCategory.LOW, 3_600_000L, AlertManager.ALERT_LEVEL_SOON)

        val now = System.currentTimeMillis()
        val readings = listOf(
            reading(90, now - 10 * 60_000L),
            reading(87, now - 8 * 60_000L),
            reading(84, now - 6 * 60_000L),
            reading(82, now - 4 * 60_000L),
            reading(80, now - 2 * 60_000L),
            reading(78, now)
        )
        alertManager.checkReading(readings.last(), readings, 15)
        assertFalse(isNotificationActive(AlertManager.ALERT_LOW_SOON_ID))
    }

    @Test
    fun `pauseAllAlerts sets identical expiry timestamps for both categories`() = runTest {
        // The Pause All shortcut must produce equal expiries so MainScreen can collapse
        // the two pause pills into a single "All alerts paused" pill via exact equality.
        alertManager.pauseAllAlerts(3_600_000L)

        val low = alertManager.pauseLowExpiryMs.value
        val high = alertManager.pauseHighExpiryMs.value
        assertTrue("LOW expiry should be set", low != null)
        assertTrue("HIGH expiry should be set", high != null)
        org.junit.Assert.assertEquals(low, high)
    }

    @Test
    fun `pauseAllAlerts called twice still produces equal expiries`() = runTest {
        // Pin idempotency so a future refactor that splits the timestamp computation
        // (e.g. "first LOW, then HIGH") would break this test before reaching production.
        alertManager.pauseAllAlerts(3_600_000L)
        alertManager.pauseAllAlerts(1_800_000L)

        org.junit.Assert.assertEquals(
            alertManager.pauseLowExpiryMs.value,
            alertManager.pauseHighExpiryMs.value
        )
    }

    @Test
    fun `cancelAllAlerts clears both categories`() = runTest {
        alertManager.pauseAllAlerts(3_600_000L)
        alertManager.cancelAllAlerts()

        org.junit.Assert.assertNull(alertManager.pauseLowExpiryMs.value)
        org.junit.Assert.assertNull(alertManager.pauseHighExpiryMs.value)
    }

    // -- Push failure --

    @Test
    fun `push failure alert fires when handlePushFailure called with true`() {
        alertManager.handlePushFailure(true)
        assertTrue(isNotificationActive(AlertManager.ALERT_PUSH_FAIL_ID))
    }

    @Test
    fun `push failure alert clears when handlePushFailure called with false`() {
        alertManager.handlePushFailure(true)
        assertTrue(isNotificationActive(AlertManager.ALERT_PUSH_FAIL_ID))

        alertManager.handlePushFailure(false)
        assertFalse(isNotificationActive(AlertManager.ALERT_PUSH_FAIL_ID))
    }

    // -- Re-fire suppression (FLAG_ONLY_ALERT_ONCE) --
    // Stale and push-fail can re-fire every reading cycle while the underlying
    // condition persists. Each notify() with the same ID re-plays sound/vibration
    // unless FLAG_ONLY_ALERT_ONCE is set. Without this flag the user gets alarmed
    // every minute through the night when readings stop arriving.

    @Test
    fun `stale notification sets FLAG_ONLY_ALERT_ONCE so repeats do not re-sound`() = runTest {
        val staleTs = System.currentTimeMillis() - (AlertManager.STALE_THRESHOLD_MINUTES + 1) * 60_000L
        alertManager.checkStale(staleTs)

        val notif = notificationManager.activeNotifications.first { it.id == AlertManager.ALERT_STALE_ID }
        assertTrue(
            "stale notification must set FLAG_ONLY_ALERT_ONCE",
            (notif.notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE) != 0
        )
    }

    @Test
    fun `push failure notification sets FLAG_ONLY_ALERT_ONCE so repeats do not re-sound`() {
        alertManager.handlePushFailure(true)

        val notif = notificationManager.activeNotifications.first { it.id == AlertManager.ALERT_PUSH_FAIL_ID }
        assertTrue(
            "push fail notification must set FLAG_ONLY_ALERT_ONCE",
            (notif.notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE) != 0
        )
    }

    @Test
    fun `low alert does NOT set FLAG_ONLY_ALERT_ONCE — dangerous BG must keep alarming`() = runTest {
        alertManager.checkReading(reading(60), emptyList(), 0)

        val notif = notificationManager.activeNotifications.first { it.id == AlertManager.ALERT_LOW_ID }
        assertTrue(
            "low alert must keep re-alerting (FLAG_ONLY_ALERT_ONCE must be unset)",
            (notif.notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE) == 0
        )
    }
}
