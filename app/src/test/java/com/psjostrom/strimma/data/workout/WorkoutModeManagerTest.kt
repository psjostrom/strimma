package com.psjostrom.strimma.data.workout

import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.calendar.CalendarPoller
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import com.psjostrom.strimma.data.health.ExerciseCategory
import com.psjostrom.strimma.data.calendar.MetabolicProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WorkoutModeManagerTest {

    private val msPerHour = 3600_000L
    private val baseNowMs = 1_700_000_000_000L  // arbitrary fixed epoch ms

    /**
     * Builds a manager with isolated DataStore + injectable clock + fake calendar.
     * Uses [backgroundScope] for the manager's internal collectors so runTest
     * auto-cancels them at test end (avoids UncompletedCoroutinesError from the
     * SharingStarted.Eagerly + infinite ticker pattern).
     */
    private fun TestScope.setup(): TestRig {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dataStore = createTestDataStore(this)
        val widgetSettings = com.psjostrom.strimma.widget.WidgetSettingsRepository(context)
        val settings = SettingsRepository(context, widgetSettings, dataStore)
        val clock = MutableClock(baseNowMs)
        val nextEventFlow = MutableStateFlow<WorkoutEvent?>(null)
        val fakePoller = FakeCalendarPoller(nextEventFlow)
        val manager = WorkoutModeManager(settings, fakePoller, clock, backgroundScope)
        return TestRig(manager, settings, nextEventFlow, clock)
    }

    private data class TestRig(
        val manager: WorkoutModeManager,
        val settings: SettingsRepository,
        val nextEventFlow: MutableStateFlow<WorkoutEvent?>,
        val clock: MutableClock
    )

    private fun event(startMs: Long, endMs: Long): WorkoutEvent = WorkoutEvent(
        title = "Test event",
        startTime = startMs,
        endTime = endMs,
        category = ExerciseCategory.RUNNING,
        metabolicProfile = MetabolicProfile.AEROBIC,
        calendarId = 1L
    )

    @Test
    fun `initial state is Off`() = runTest {
        val rig = setup()
        assertEquals(WorkoutMode.Off, rig.manager.state.first())
    }

    @Test
    fun `setManualOn produces On(MANUAL) with expiresAtMs = now plus maxHours`() = runTest {
        val rig = setup()
        rig.manager.setManualOn()
        val state = rig.manager.state.first()
        assertTrue(state is WorkoutMode.On)
        val on = state as WorkoutMode.On
        assertEquals(WorkoutMode.On.Source.MANUAL, on.source)
        assertEquals(baseNowMs, on.sinceMs)
        assertEquals(baseNowMs + 3 * msPerHour, on.expiresAtMs)
    }

    @Test
    fun `manual ON expires after maxHours and clears manualSinceMs`() = runTest {
        val rig = setup()
        rig.manager.setManualOn()
        rig.clock.nowMs = baseNowMs + 3 * msPerHour + 1
        // ticker fires every 30s; advance enough to trigger one tick
        advanceTimeBy(35_000L)
        assertEquals(WorkoutMode.Off, rig.manager.state.first())
        assertEquals(null, rig.settings.manualWorkoutSinceMs.first())
    }

    @Test
    fun `calendar event currently active triggers On(CALENDAR)`() = runTest {
        val rig = setup()
        val ev = event(startMs = baseNowMs - 1000, endMs = baseNowMs + 60 * msPerHour)
        rig.nextEventFlow.value = ev
        advanceTimeBy(35_000L)
        val state = rig.manager.state.first()
        assertTrue(state is WorkoutMode.On)
        val on = state as WorkoutMode.On
        assertEquals(WorkoutMode.On.Source.CALENDAR, on.source)
        assertEquals(ev.startTime, on.sinceMs)
        assertEquals(ev.endTime, on.expiresAtMs)
    }

    @Test
    fun `future calendar event does not trigger`() = runTest {
        val rig = setup()
        rig.nextEventFlow.value = event(startMs = baseNowMs + msPerHour, endMs = baseNowMs + 2 * msPerHour)
        advanceTimeBy(35_000L)
        assertEquals(WorkoutMode.Off, rig.manager.state.first())
    }

    @Test
    fun `manual OFF during active calendar sets overrideUntilMs to event end`() = runTest {
        val rig = setup()
        val ev = event(startMs = baseNowMs - 1000, endMs = baseNowMs + msPerHour)
        rig.nextEventFlow.value = ev
        // Wait for state to transition to On(CALENDAR) before reading it inside setManualOff
        rig.manager.state.first { it is WorkoutMode.On }
        rig.manager.setManualOff()
        // Wait for the override to propagate through combine() back to Off
        rig.manager.state.first { it is WorkoutMode.Off }
        assertEquals(ev.endTime, rig.settings.manualOffOverrideUntilMs.first())
    }

    @Test
    fun `manual ON wins over active calendar event`() = runTest {
        val rig = setup()
        val ev = event(startMs = baseNowMs - 1000, endMs = baseNowMs + msPerHour)
        rig.nextEventFlow.value = ev
        rig.manager.setManualOn()
        val state = rig.manager.state.first()
        assertEquals(WorkoutMode.On.Source.MANUAL, (state as WorkoutMode.On).source)
    }

    @Test
    fun `manual ON expiring while calendar still active transitions seamlessly to CALENDAR`() = runTest {
        val rig = setup()
        val ev = event(startMs = baseNowMs - 1000, endMs = baseNowMs + 4 * msPerHour)
        rig.nextEventFlow.value = ev
        rig.manager.setManualOn()  // expires at baseNowMs + 3h
        rig.clock.nowMs = baseNowMs + 3 * msPerHour + 1
        advanceTimeBy(35_000L)
        val state = rig.manager.state.first()
        assertTrue(state is WorkoutMode.On)
        assertEquals(WorkoutMode.On.Source.CALENDAR, (state as WorkoutMode.On).source)
    }

    @Test
    fun `effectiveThresholds in Off mode uses settings bg+alert values`() = runTest {
        val rig = setup()
        val t = rig.manager.effectiveThresholds.first()
        // Defaults from SettingsRepository.kt:218-223
        assertEquals(72f, t.displayLowMgdl)
        assertEquals(180f, t.displayHighMgdl)
        assertEquals(72f, t.alertLowMgdl)
        assertEquals(180f, t.alertHighMgdl)
        assertEquals(54f, t.alertUrgentLowMgdl)
        assertEquals(234f, t.alertUrgentHighMgdl)
    }

    @Test
    fun `effectiveThresholds in On mode uses workout values for both display and alerts`() = runTest {
        val rig = setup()
        rig.manager.setManualOn()
        // Wait for the combine() to re-emit with workout values (default low=108)
        val t = rig.manager.effectiveThresholds.first { it.alertLowMgdl == 108f }
        assertEquals(108f, t.displayLowMgdl)
        assertEquals(252f, t.displayHighMgdl)
        assertEquals(108f, t.alertLowMgdl)
        assertEquals(252f, t.alertHighMgdl)
        assertEquals(90f, t.alertUrgentLowMgdl)
        assertEquals(288f, t.alertUrgentHighMgdl)
    }
}

/** Mutable clock for tests so we can advance time without virtual-time tricks. */
class MutableClock(var nowMs: Long) : Clock {
    override fun nowMs(): Long = nowMs
}

/** Hand-written test double mirroring CalendarPoller's nextEvent flow. */
class FakeCalendarPoller(
    val nextEventFlow: MutableStateFlow<WorkoutEvent?>
) : CalendarPollerSource {
    override val nextEvent get() = nextEventFlow
}
