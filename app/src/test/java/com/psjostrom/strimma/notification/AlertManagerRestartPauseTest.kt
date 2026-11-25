package com.psjostrom.strimma.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import com.psjostrom.strimma.testutil.workout.FakeCalendarPoller
import com.psjostrom.strimma.testutil.workout.MutableClock
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A pause must survive a restart of the foreground service, including the alert level it
 * silences, because the pause indicators name that level. This test writes a pause to the
 * snooze prefs before AlertManager exists, the way a cold start finds it.
 */
@RunWith(RobolectricTestRunner::class)
class AlertManagerRestartPauseTest {

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        managerScope.cancel()
    }

    @Test
    fun `a pause stored before construction is exposed with its level`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expiry = System.currentTimeMillis() + 60 * 60 * 1000L
        context.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE)
            .edit()
            .putLong("pause_low", expiry)
            .putInt("pause_low_level", AlertManager.ALERT_LEVEL_SOON)
            .commit()

        val widgetSettings = WidgetSettingsRepository(context)
        val settings = SettingsRepository(context, widgetSettings, createTestDataStore())
        val workoutModeManager = WorkoutModeManager(
            settings,
            FakeCalendarPoller(),
            MutableClock(System.currentTimeMillis()),
            managerScope,
        )

        val alertManager = AlertManager(context, settings, workoutModeManager)

        assertEquals(AlertPause(expiry, AlertManager.ALERT_LEVEL_SOON), alertManager.pauseLow.value)
    }
}
