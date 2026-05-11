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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression test for the AlertManager construction crash that took CGM coverage
 * down on every cold start when a pause was already in prefs.
 *
 * Root cause: pauseExpiryFlows' initializer called scheduleExpiryClear for any
 * active pause, and scheduleExpiryClear touches expiryClearJobs — but
 * expiryClearJobs was declared *after* pauseExpiryFlows, so Kotlin's top-down
 * property init left it null at the moment of use → NPE → StrimmaService.onCreate
 * fails → ActivityManager backs off restarts up to an hour.
 *
 * The default AlertManagerTest @Before constructs the instance with an empty
 * snooze prefs, which is why the regression never tripped a unit test. This test
 * pre-populates an active pause *before* construction.
 */
@RunWith(RobolectricTestRunner::class)
class AlertManagerInitOrderTest {

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        managerScope.cancel()
    }

    @Test
    fun `construction succeeds when an active pause is already in prefs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Write an active pause directly to the snooze prefs that the production
        // AlertManager reads from in alertPauseExpiryMs (-> pauseExpiryMs).
        // An hour out, well in the future, so scheduleExpiryClear will treat it
        // as live and try to register an expiry job.
        context.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE)
            .edit()
            .putLong("pause_low", System.currentTimeMillis() + 60 * 60 * 1000L)
            .commit()

        val widgetSettings = WidgetSettingsRepository(context)
        val settings = SettingsRepository(context, widgetSettings, createTestDataStore())
        val workoutModeManager = WorkoutModeManager(
            settings,
            FakeCalendarPoller(),
            MutableClock(System.currentTimeMillis()),
            managerScope,
        )

        // Will throw NullPointerException with the regressed declaration order.
        AlertManager(context, settings, workoutModeManager, managerScope)
    }
}
