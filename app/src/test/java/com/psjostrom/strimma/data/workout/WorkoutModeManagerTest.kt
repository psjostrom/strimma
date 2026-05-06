package com.psjostrom.strimma.data.workout

import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import com.psjostrom.strimma.data.health.ExerciseCategory
import com.psjostrom.strimma.data.calendar.MetabolicProfile
import com.psjostrom.strimma.testutil.workout.FakeCalendarPoller
import com.psjostrom.strimma.testutil.workout.MutableClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        val on = rig.manager.state.first { it is WorkoutMode.On } as WorkoutMode.On
        assertEquals(WorkoutMode.On.Source.MANUAL, on.source)
        assertEquals(baseNowMs, on.sinceMs)
        assertEquals(baseNowMs + 3 * msPerHour, on.expiresAtMs)
    }

    @Test
    fun `setManualOn snapshots expiresAt so a later maxHours change does not shorten the session`() = runTest {
        val rig = setup()
        rig.manager.setManualOn()  // captures expiresAt = baseNowMs + 3h
        // User drags max-hours slider to 1 mid-session.
        rig.settings.setWorkoutModeMaxHours(1)
        // Without snapshotting, computeState would derive expiresAt = sinceMs + 1h
        // and the session would end immediately. With snapshotting, the original
        // 3-hour deadline is preserved.
        rig.manager.state.first { it is WorkoutMode.On }
        val on = rig.manager.currentState() as WorkoutMode.On
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
        val on = rig.manager.state.first { it is WorkoutMode.On && it.source == WorkoutMode.On.Source.MANUAL }
        assertEquals(WorkoutMode.On.Source.MANUAL, (on as WorkoutMode.On).source)
    }

    @Test
    fun `manual OFF during MANUAL session that overlaps an active calendar honors user intent`() = runTest {
        // Regression for the bounce-back bug: user manually toggled ON during a
        // calendar event window (so source=MANUAL because R1 wins over R5).
        // Tapping OFF must NOT silently revert to On(CALENDAR) just because the
        // calendar event is still active — the user said off, mode goes off.
        val rig = setup()
        val ev = event(startMs = baseNowMs - 1000, endMs = baseNowMs + msPerHour)
        rig.nextEventFlow.value = ev
        rig.manager.setManualOn()
        rig.manager.state.first { it is WorkoutMode.On && it.source == WorkoutMode.On.Source.MANUAL }
        rig.manager.setManualOff()
        rig.manager.state.first { it is WorkoutMode.Off }
        // The override should be set to the calendar event's end so we stay Off
        // for the remainder of the event window.
        assertEquals(ev.endTime, rig.settings.manualOffOverrideUntilMs.first())
    }

    @Test
    fun `manual ON expiring while calendar still active transitions seamlessly to CALENDAR`() = runTest {
        val rig = setup()
        val ev = event(startMs = baseNowMs - 1000, endMs = baseNowMs + 4 * msPerHour)
        rig.nextEventFlow.value = ev
        rig.manager.setManualOn()  // expires at baseNowMs + 3h
        rig.clock.nowMs = baseNowMs + 3 * msPerHour + 1
        advanceTimeBy(35_000L)
        val state = rig.manager.state.first { it is WorkoutMode.On && it.source == WorkoutMode.On.Source.CALENDAR }
        assertEquals(WorkoutMode.On.Source.CALENDAR, (state as WorkoutMode.On).source)
    }

    @Test
    fun `cleanupExpired clears both manualSinceMs and overrideUntilMs when both expired`() = runTest {
        val rig = setup()
        // Plant both keys directly with already-expired timestamps (in the past
        // relative to the clock's baseNowMs).
        rig.settings.setManualWorkoutSession(sinceMs = baseNowMs - 4 * msPerHour, expiresMs = baseNowMs - msPerHour)
        rig.settings.setManualOffOverrideUntilMs(baseNowMs - msPerHour)
        // Trigger one ticker → cleanupExpired runs.
        advanceTimeBy(35_000L)
        assertEquals(null, rig.settings.manualWorkoutSinceMs.first())
        assertEquals(null, rig.settings.manualWorkoutExpiresMs.first())
        assertEquals(null, rig.settings.manualOffOverrideUntilMs.first())
    }

    @Test
    fun `nextEvent transition to null clears a stale override`() = runTest {
        // User is in On(CALENDAR), taps off → override = event.endTime.
        // User then DELETES the event from their calendar → nextEvent goes null.
        // The override timestamp must NOT linger to suppress a future event in
        // the same window.
        val rig = setup()
        val ev = event(startMs = baseNowMs - 1000, endMs = baseNowMs + 2 * msPerHour)
        rig.nextEventFlow.value = ev
        rig.manager.state.first { it is WorkoutMode.On }
        rig.manager.setManualOff()
        rig.manager.state.first { it is WorkoutMode.Off }
        assertEquals(ev.endTime, rig.settings.manualOffOverrideUntilMs.first())
        // Calendar event deleted.
        rig.nextEventFlow.value = null
        // Override observer in init should clear it.
        rig.settings.manualOffOverrideUntilMs.first { it == null }
        assertEquals(null, rig.settings.manualOffOverrideUntilMs.first())
    }

    @Test
    fun `toggle reads persisted state and is robust to the eagerly-seeded state-value`() = runTest {
        // Simulates the cold-start race: persist a manual session in DataStore,
        // then call toggle BEFORE the eager combine has had time to propagate.
        // toggle() must read DataStore (not state.value=Off) and correctly route
        // to setManualOff. Without this fix, the foreground-notification "End
        // workout" action tap on a freshly-respawned process would silently
        // restart the workout instead of ending it.
        val rig = setup()
        rig.settings.setManualWorkoutSession(sinceMs = baseNowMs, expiresMs = baseNowMs + 3 * msPerHour)
        rig.manager.toggle()
        // After toggle the persisted session must be cleared.
        assertEquals(null, rig.settings.manualWorkoutSinceMs.first())
        assertEquals(null, rig.settings.manualWorkoutExpiresMs.first())
    }

    @Test
    fun `effectiveThresholds initial value is the placeholder, currentEffectiveThresholds suspends until real data`() = runTest {
        val rig = setup()
        // Until the upstream combine emits, effectiveThresholds.value is the
        // placeholder sentinel — distinguishable from any legitimate user setting.
        assertSame(WorkoutModeManager.PLACEHOLDER_THRESHOLDS, rig.manager.effectiveThresholds.value)
        // currentEffectiveThresholds() suspends past the placeholder and returns
        // real values seeded from SettingsRepository defaults.
        val t = rig.manager.currentEffectiveThresholds()
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

    @Test
    fun `currentSessionElapsedMs reports null when off, time-since-sinceMs when on`() = runTest {
        val rig = setup()
        assertNull(rig.manager.currentSessionElapsedMs())
        rig.manager.setManualOn()
        rig.clock.nowMs = baseNowMs + 5 * 60_000L  // 5 min later
        val elapsed = rig.manager.currentSessionElapsedMs()
        assertEquals(5 * 60_000L, elapsed)
    }
}
