package com.psjostrom.strimma.notification

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.notification.SnoozeDuration
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import com.psjostrom.strimma.testutil.workout.FakeCalendarPoller
import com.psjostrom.strimma.testutil.workout.MutableClock
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlertSnoozeReceiverTest {

    private fun withAlertManager(block: (Context, AlertManager) -> Unit) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val workoutModeManager = WorkoutModeManager(
                settings,
                FakeCalendarPoller(),
                MutableClock(System.currentTimeMillis()),
                scope,
            )
            val alertManager = AlertManager(context, settings, workoutModeManager, scope)
            context.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE).edit().clear().apply()
            block(context, alertManager)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `missing duration extra falls back to M30`() = withAlertManager { context, alertManager ->
        val before = System.currentTimeMillis()
        val intent = Intent().putExtra("alert_id", AlertManager.ALERT_STALE_ID)
        AlertSnoozeReceiver.handleSnooze(intent, alertManager)
        val until = context.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE)
            .getLong(AlertManager.ALERT_STALE_ID.toString(), 0L)
        assertTrue(until in (before + SnoozeDuration.M30.durationMs - 5_000)..(before + SnoozeDuration.M30.durationMs + 5_000))
    }

    @Test
    fun `invalid duration extra falls back to M30`() = withAlertManager { context, alertManager ->
        val before = System.currentTimeMillis()
        val intent = Intent()
            .putExtra("alert_id", AlertManager.ALERT_STALE_ID)
            .putExtra(AlertSnoozeReceiver.EXTRA_DURATION, "NOPE")
        AlertSnoozeReceiver.handleSnooze(intent, alertManager)
        val until = context.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE)
            .getLong(AlertManager.ALERT_STALE_ID.toString(), 0L)
        assertTrue(until in (before + SnoozeDuration.M30.durationMs - 5_000)..(before + SnoozeDuration.M30.durationMs + 5_000))
    }

    @Test
    fun `valid duration extra uses H1`() = withAlertManager { context, alertManager ->
        val before = System.currentTimeMillis()
        val intent = Intent()
            .putExtra("alert_id", AlertManager.ALERT_STALE_ID)
            .putExtra(AlertSnoozeReceiver.EXTRA_DURATION, SnoozeDuration.H1.name)
        AlertSnoozeReceiver.handleSnooze(intent, alertManager)
        val until = context.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE)
            .getLong(AlertManager.ALERT_STALE_ID.toString(), 0L)
        assertTrue(until in (before + SnoozeDuration.H1.durationMs - 5_000)..(before + SnoozeDuration.H1.durationMs + 5_000))
    }
}
