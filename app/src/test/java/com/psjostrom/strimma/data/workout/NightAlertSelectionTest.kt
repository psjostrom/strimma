package com.psjostrom.strimma.data.workout

import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.testutil.workout.FakeCalendarPoller
import com.psjostrom.strimma.testutil.workout.MutableClock
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

private const val NIGHT_LOW_MGDL = 90f
private const val TIMEOUT_MS = 5_000L

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NightAlertSelectionTest {

    private class Rig(val manager: WorkoutModeManager, val settings: SettingsRepository, val clock: MutableClock)

    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 6, 10, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    // Settings are applied before the manager exists, so its first real emission is already final.
    private suspend fun TestScope.rig(nowMs: Long, nightEnabled: Boolean = false): Rig {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore(this))
        if (nightEnabled) {
            settings.setNightAlertsEnabled(true)
            settings.setNightAlertLow(NIGHT_LOW_MGDL)
        }
        val clock = MutableClock(nowMs).apply { zone = ZoneId.of("UTC") }
        return Rig(WorkoutModeManager(settings, FakeCalendarPoller(), clock, backgroundScope), settings, clock)
    }

    private suspend fun Rig.selected(expected: AlertSet): EffectiveThresholds =
        withTimeout(TIMEOUT_MS) {
            manager.effectiveThresholds.first {
                it !== WorkoutModeManager.PLACEHOLDER_THRESHOLDS && it.activeSet == expected
            }
        }

    @Test
    fun `night disabled at 02 00 selects day`() = runTest {
        val rig = rig(at(2, 0))
        assertEquals(AlertSet.DAY, rig.selected(AlertSet.DAY).activeSet)
    }

    @Test
    fun `night enabled at 02 00 selects night`() = runTest {
        val rig = rig(at(2, 0), nightEnabled = true)
        val t = rig.selected(AlertSet.NIGHT)
        assertEquals(rig.settings.nightAlertProtocol.first(), t.alertProtocol)
        assertEquals(NIGHT_LOW_MGDL, t.alertProtocol.lowMgdl)
    }

    @Test
    fun `night enabled at 14 00 selects day`() = runTest {
        val rig = rig(at(14, 0), nightEnabled = true)
        assertEquals(AlertSet.DAY, rig.selected(AlertSet.DAY).activeSet)
    }

    @Test
    fun `night enabled at 23 00 selects night`() = runTest {
        val rig = rig(at(23, 0), nightEnabled = true)
        assertEquals(AlertSet.NIGHT, rig.selected(AlertSet.NIGHT).activeSet)
    }

    @Test
    fun `night enabled at 07 00 selects day`() = runTest {
        val rig = rig(at(7, 0), nightEnabled = true)
        assertEquals(AlertSet.DAY, rig.selected(AlertSet.DAY).activeSet)
    }

    @Test
    fun `workout mode at 02 00 selects exercise`() = runTest {
        val rig = rig(at(2, 0), nightEnabled = true)
        rig.manager.setManualOn()
        assertEquals(AlertSet.EXERCISE, rig.selected(AlertSet.EXERCISE).activeSet)
    }

    @Test
    fun `night keeps the day graph band`() = runTest {
        val rig = rig(at(2, 0), nightEnabled = true)
        val t = rig.selected(AlertSet.NIGHT)
        assertEquals(rig.settings.bgLow.first(), t.displayLowMgdl)
        assertEquals(rig.settings.bgHigh.first(), t.displayHighMgdl)
    }
}
