package com.psjostrom.strimma.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import com.psjostrom.strimma.data.workout.CalendarPollerSource
import com.psjostrom.strimma.data.workout.MutableClock
import com.psjostrom.strimma.data.workout.WorkoutMode
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the contract WorkoutModeReceiver depends on: tapping the notification
 * action calls manager.toggle(), which transitions Off ↔ On(MANUAL).
 *
 * Broadcasting through Robolectric requires a HiltAndroidTest fixture, so we test
 * the manager.toggle() contract directly. The receiver itself is a thin wrapper
 * (5 lines) that just delegates — exercised end-to-end by manual smoke test.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WorkoutNotificationActionTest {

    @Test
    fun `toggle while Off transitions to On(MANUAL)`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val ds = createTestDataStore(this)
        val widgetSettings = WidgetSettingsRepository(context)
        val settings = SettingsRepository(context, widgetSettings, ds)
        val nextEventFlow = MutableStateFlow<WorkoutEvent?>(null)
        val poller = object : CalendarPollerSource { override val nextEvent = nextEventFlow }
        val clock = MutableClock(1_700_000_000_000L)
        val manager = WorkoutModeManager(settings, poller, clock, backgroundScope)

        assertTrue(manager.state.first() is WorkoutMode.Off)

        manager.toggle()
        assertTrue(manager.state.first { it is WorkoutMode.On } is WorkoutMode.On)
        val on = manager.state.value as WorkoutMode.On
        assertTrue(on.source == WorkoutMode.On.Source.MANUAL)

        manager.toggle()
        assertTrue(manager.state.first { it is WorkoutMode.Off } is WorkoutMode.Off)
    }
}
