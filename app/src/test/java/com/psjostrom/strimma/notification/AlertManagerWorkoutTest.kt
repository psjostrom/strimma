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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    )

    private fun kotlinx.coroutines.test.TestScope.setup(): Rig {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ds = createTestDataStore(this)
        val widgetSettings = com.psjostrom.strimma.widget.WidgetSettingsRepository(context)
        val settings = SettingsRepository(context, widgetSettings, ds)
        val poller = FakeCalendarPoller()
        val clock = MutableClock(baseNowMs)
        val manager = WorkoutModeManager(settings, poller, clock, backgroundScope)
        val alertManager = AlertManager(context, settings, manager)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        alertManager.createChannels()
        return Rig(context, settings, manager, alertManager, notificationManager)
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
        rig.manager.effectiveThresholds.first { it.alertLowMgdl == 108f }
        rig.alertManager.checkReading(reading(99), emptyList(), predictionMinutes = 0)
        assertNotNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_LOW_ID))
    }

    @Test
    fun `BG 90 mode ON fires urgent low alert (workout urgent_low=90)`() = runTest {
        val rig = setup()
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first { it.alertUrgentLowMgdl == 90f }
        rig.alertManager.checkReading(reading(90), emptyList(), predictionMinutes = 0)
        assertNotNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_URGENT_LOW_ID))
    }

    @Test
    fun `BG 234 mode ON does not fire high alert (workout high=252)`() = runTest {
        val rig = setup()
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first { it.alertHighMgdl == 252f }
        rig.alertManager.checkReading(reading(234), emptyList(), predictionMinutes = 0)
        assertNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_HIGH_ID))
        assertNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_URGENT_HIGH_ID))
    }

    @Test
    fun `BG 288 mode ON fires urgent high alert`() = runTest {
        val rig = setup()
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first { it.alertUrgentHighMgdl == 288f }
        rig.alertManager.checkReading(reading(288), emptyList(), predictionMinutes = 0)
        assertNotNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_URGENT_HIGH_ID))
    }

    @Test
    fun `stale reading mode OFF fires stale alert`() = runTest {
        val rig = setup()
        val staleTs = baseNowMs - 11 * 60_000L  // 11 min old
        rig.alertManager.checkStale(staleTs)
        assertNotNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_STALE_ID))
    }

    @Test
    fun `stale reading mode ON suppresses stale alert`() = runTest {
        val rig = setup()
        rig.manager.setManualOn()
        rig.manager.effectiveThresholds.first { it.alertLowMgdl == 108f }  // wait for mode On
        val staleTs = baseNowMs - 11 * 60_000L
        rig.alertManager.checkStale(staleTs)
        assertNull(Shadows.shadowOf(rig.notificationManager).getNotification(AlertManager.ALERT_STALE_ID))
    }
}
