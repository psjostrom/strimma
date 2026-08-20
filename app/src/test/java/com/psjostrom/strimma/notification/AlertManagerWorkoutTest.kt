package com.psjostrom.strimma.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import com.psjostrom.strimma.testutil.workout.FakeCalendarPoller
import com.psjostrom.strimma.testutil.workout.MutableClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AlertManagerWorkoutTest {

    private val baseNowMs = 1_700_000_000_000L

    private data class Rig(
        val context: Context,
        val settings: SettingsRepository,
        val manager: WorkoutModeManager,
        val alertManager: AlertManager,
        val notificationManager: NotificationManager,
        val clock: MutableClock,
    )

    private fun kotlinx.coroutines.test.TestScope.setup(): Rig {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ds = createTestDataStore(this)
        val widgetSettings = com.psjostrom.strimma.widget.WidgetSettingsRepository(context)
        val settings = SettingsRepository(context, widgetSettings, ds)
        val poller = FakeCalendarPoller()
        val clock = MutableClock(baseNowMs)
        val manager = WorkoutModeManager(settings, poller, clock, backgroundScope)
        val alertManager = AlertManager(context, settings, manager, backgroundScope)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        alertManager.createChannels()
        notificationManager.cancelAll()
        return Rig(context, settings, manager, alertManager, notificationManager, clock)
    }

    private fun reading(mgdl: Int, tsMs: Long = 1_700_000_000_000L): GlucoseReading =
        GlucoseReading(ts = tsMs, sgv = mgdl, direction = "Flat", delta = 0.0, pushed = 0)

    @Test
    fun `BG 99 mode OFF does not fire low alert`() = runTest {
        val rig = setup()
        rig.alertManager.checkReading(reading(99), emptyList(), predictionMinutes = 0)
        assertNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_LOW_ID))
    }

    @Test
    fun `BG 99 mode ON fires low alert (workout low=108)`() = runTest {
        val rig = setup()
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first { it.alertProtocol.lowMgdl == 108f }
        rig.alertManager.checkReading(reading(99), emptyList(), predictionMinutes = 0)
        assertNotNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_LOW_ID))
    }

    @Test
    fun `exercise low toggle and threshold are independent from regular low`() = runTest {
        val rig = setup()
        rig.settings.setAlertLowEnabled(false)
        rig.settings.setExerciseAlertLowEnabled(true)
        rig.settings.setExerciseAlertLow(120f)
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first {
            it.workoutModeOn && it.alertProtocol.lowMgdl == 120f && it.alertProtocol.lowEnabled
        }

        rig.alertManager.checkReading(reading(110), emptyList(), predictionMinutes = 0)

        assertNotNull(
            Shadows.shadowOf(rig.notificationManager)
                .getNotification(AlertManager.ALERT_LOW_ID)
        )
    }

    @Test
    fun `exercise high toggle and threshold are independent from regular high`() = runTest {
        val rig = setup()
        rig.settings.setAlertHighEnabled(false)
        rig.settings.setExerciseAlertHighEnabled(true)
        rig.settings.setExerciseAlertHigh(200f)
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first {
            it.workoutModeOn && it.alertProtocol.highMgdl == 200f && it.alertProtocol.highEnabled
        }

        rig.alertManager.checkReading(reading(210), emptyList(), predictionMinutes = 0)

        assertNotNull(
            Shadows.shadowOf(rig.notificationManager)
                .getNotification(AlertManager.ALERT_HIGH_ID)
        )
    }

    @Test
    fun `BG 90 mode ON fires urgent low alert (workout urgent_low=90)`() = runTest {
        val rig = setup()
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first { it.alertProtocol.urgentLowMgdl == 90f }
        rig.alertManager.checkReading(reading(90), emptyList(), predictionMinutes = 0)
        assertNotNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_URGENT_LOW_ID))
    }

    @Test
    fun `exercise urgent low toggle and threshold are independent from regular urgent low`() = runTest {
        val rig = setup()
        rig.settings.setAlertUrgentLowEnabled(false)
        rig.settings.setExerciseAlertUrgentLowEnabled(true)
        rig.settings.setExerciseAlertUrgentLow(100f)
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first {
            it.workoutModeOn && it.alertProtocol.urgentLowMgdl == 100f && it.alertProtocol.urgentLowEnabled
        }

        rig.alertManager.checkReading(reading(95), emptyList(), predictionMinutes = 0)

        assertNotNull(
            Shadows.shadowOf(rig.notificationManager)
                .getNotification(AlertManager.ALERT_URGENT_LOW_ID)
        )
    }

    @Test
    fun `BG 234 mode ON does not fire high alert (workout high=252)`() = runTest {
        val rig = setup()
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first { it.alertProtocol.highMgdl == 252f }
        rig.alertManager.checkReading(reading(234), emptyList(), predictionMinutes = 0)
        assertNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_HIGH_ID))
        assertNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_URGENT_HIGH_ID))
    }

    @Test
    fun `BG 288 mode ON fires urgent high alert`() = runTest {
        val rig = setup()
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first { it.alertProtocol.urgentHighMgdl == 288f }
        rig.alertManager.checkReading(reading(288), emptyList(), predictionMinutes = 0)
        assertNotNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_URGENT_HIGH_ID))
    }

    @Test
    fun `exercise urgent high toggle and threshold are independent from regular urgent high`() = runTest {
        val rig = setup()
        rig.settings.setAlertUrgentHighEnabled(false)
        rig.settings.setExerciseAlertUrgentHighEnabled(true)
        rig.settings.setExerciseAlertUrgentHigh(240f)
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first {
            it.workoutModeOn && it.alertProtocol.urgentHighMgdl == 240f && it.alertProtocol.urgentHighEnabled
        }

        rig.alertManager.checkReading(reading(245), emptyList(), predictionMinutes = 0)

        assertNotNull(
            Shadows.shadowOf(rig.notificationManager)
                .getNotification(AlertManager.ALERT_URGENT_HIGH_ID)
        )
    }

    @Test
    fun `exercise low soon toggle and threshold are independent from regular low soon`() = runTest {
        val rig = setup()
        rig.settings.setAlertLowSoonEnabled(false)
        rig.settings.setExerciseAlertLowSoonEnabled(true)
        rig.settings.setExerciseAlertLow(100f)
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first {
            it.workoutModeOn && it.alertProtocol.lowMgdl == 100f && it.alertProtocol.lowSoonEnabled
        }

        val now = System.currentTimeMillis()
        val readings = listOf(
            reading(130, now - 10 * 60_000L),
            reading(126, now - 8 * 60_000L),
            reading(122, now - 6 * 60_000L),
            reading(118, now - 4 * 60_000L),
            reading(114, now - 2 * 60_000L),
            reading(110, now),
        )
        rig.alertManager.checkReading(readings.last(), readings, predictionMinutes = 15)

        assertNotNull(
            Shadows.shadowOf(rig.notificationManager)
                .getNotification(AlertManager.ALERT_LOW_SOON_ID)
        )
    }

    @Test
    fun `exercise high soon toggle and threshold are independent from regular high soon`() = runTest {
        val rig = setup()
        rig.settings.setAlertHighSoonEnabled(false)
        rig.settings.setExerciseAlertHighSoonEnabled(true)
        rig.settings.setExerciseAlertHigh(220f)
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first {
            it.workoutModeOn && it.alertProtocol.highMgdl == 220f && it.alertProtocol.highSoonEnabled
        }

        val now = System.currentTimeMillis()
        val readings = listOf(
            reading(190, now - 10 * 60_000L),
            reading(194, now - 8 * 60_000L),
            reading(198, now - 6 * 60_000L),
            reading(202, now - 4 * 60_000L),
            reading(206, now - 2 * 60_000L),
            reading(210, now),
        )
        rig.alertManager.checkReading(readings.last(), readings, predictionMinutes = 15)

        assertNotNull(
            Shadows.shadowOf(rig.notificationManager)
                .getNotification(AlertManager.ALERT_HIGH_SOON_ID)
        )
    }

    @Test
    fun `stale reading mode OFF fires stale alert`() = runTest {
        val rig = setup()
        val staleTs = baseNowMs - 11 * 60_000L  // 11 min old
        rig.alertManager.checkStale(staleTs)
        assertNotNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_STALE_ID))
    }

    @Test
    fun `exercise stale alert is suppressed for first 30 minutes then fires`() = runTest {
        val rig = setup()
        rig.settings.setAlertStaleEnabled(false)
        rig.settings.setExerciseAlertStaleEnabled(true)
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first { it.workoutModeOn && it.alertProtocol.staleEnabled }
        val staleTs = System.currentTimeMillis() - 11 * 60_000L

        rig.alertManager.checkStale(staleTs)

        assertNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_STALE_ID))

        rig.clock.nowMs = baseNowMs + 31 * 60_000L
        rig.alertManager.checkStale(staleTs)

        assertNotNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_STALE_ID))
    }

    @Test
    fun `disabled exercise stale alert clears visible notification`() = runTest {
        val rig = setup()
        rig.settings.setAlertStaleEnabled(true)
        rig.settings.setExerciseAlertStaleEnabled(true)
        rig.manager.effectiveThresholds.first { !it.workoutModeOn && it.alertProtocol.staleEnabled }
        val staleTs = System.currentTimeMillis() - 11 * 60_000L

        rig.alertManager.checkStale(staleTs)
        assertNotNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_STALE_ID))

        rig.settings.setExerciseAlertStaleEnabled(false)
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first { it.workoutModeOn && !it.alertProtocol.staleEnabled }
        rig.alertManager.checkStale(staleTs)

        assertNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_STALE_ID))
    }

    @Test
    fun `exercise alert uses shared cooldown and regular notification ID`() = runTest {
        val rig = setup()
        rig.settings.setAlertCooldownMinutes(15)
        rig.settings.setExerciseAlertLow(120f)
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first { it.workoutModeOn && it.alertProtocol.lowMgdl == 120f }

        rig.alertManager.checkReading(reading(110), emptyList(), predictionMinutes = 0)
        assertTrue(
            Shadows.shadowOf(rig.notificationManager)
                .getNotification(AlertManager.ALERT_LOW_ID) != null
        )
        val notificationAfterFirst =
            Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_LOW_ID)

        rig.alertManager.checkReading(reading(110), emptyList(), predictionMinutes = 0)

        assertEquals(
            notificationAfterFirst,
            Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_LOW_ID)
        )
    }
}
