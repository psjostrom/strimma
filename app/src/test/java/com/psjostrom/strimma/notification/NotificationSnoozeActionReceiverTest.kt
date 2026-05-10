package com.psjostrom.strimma.notification

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.notification.SnoozeCategory
import com.psjostrom.strimma.data.notification.SnoozeDuration
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import com.psjostrom.strimma.testutil.workout.FakeCalendarPoller
import com.psjostrom.strimma.testutil.workout.MutableClock
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The receiver itself is a thin wrapper that reads two intent extras and dispatches
 * to AlertManager.pauseAllAlerts / pauseAlertCategory. We exercise the pure logic via
 * [NotificationSnoozeActionReceiver.handleSnoozeAction] — going through the receiver's
 * `onReceive` would trigger Hilt's @AndroidEntryPoint bytecode transform, which
 * re-injects `alertManager` on every call and would overwrite our test instance.
 *
 * Expiry assertions use a `[before, after]` window because [AlertManager] reads
 * `System.currentTimeMillis()` directly when computing expiries (the static helpers
 * it shares with non-DI callers also use wall clock, so threading an injected clock
 * through would create a two-source-of-truth split). The window naturally expands
 * with execution time, so the assertion is robust under load.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotificationSnoozeActionReceiverTest {

    private data class Rig(val mgr: AlertManager)

    private fun kotlinx.coroutines.test.TestScope.rig(): Rig {
        val context: Context = ApplicationProvider.getApplicationContext()
        // Each test gets its own snooze prefs slate so leftover values from a sibling
        // test can't pre-populate any pause expiry.
        context.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE)
            .edit().clear().apply()

        val ds = createTestDataStore(this)
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), ds)
        val clock = MutableClock(System.currentTimeMillis())
        val workoutMgr = WorkoutModeManager(settings, FakeCalendarPoller(), clock, backgroundScope)
        val mgr = AlertManager(context, settings, workoutMgr, backgroundScope)
        return Rig(mgr)
    }

    private fun snoozeIntent(category: SnoozeCategory, duration: SnoozeDuration): Intent =
        Intent().apply {
            putExtra(NotificationSnoozeActionReceiver.EXTRA_CATEGORY, category.name)
            putExtra(NotificationSnoozeActionReceiver.EXTRA_DURATION, duration.name)
        }

    @Test
    fun `ALL category pauses both LOW and HIGH`() = runTest {
        val r = rig()
        NotificationSnoozeActionReceiver.handleSnoozeAction(
            snoozeIntent(SnoozeCategory.ALL, SnoozeDuration.H1),
            r.mgr
        )

        assertTrue(r.mgr.isAlertCategoryPaused(AlertCategory.LOW))
        assertTrue(r.mgr.isAlertCategoryPaused(AlertCategory.HIGH))
    }

    @Test
    fun `HIGH category pauses HIGH only`() = runTest {
        val r = rig()
        NotificationSnoozeActionReceiver.handleSnoozeAction(
            snoozeIntent(SnoozeCategory.HIGH, SnoozeDuration.M30),
            r.mgr
        )

        assertTrue(r.mgr.isAlertCategoryPaused(AlertCategory.HIGH))
        assertFalse(r.mgr.isAlertCategoryPaused(AlertCategory.LOW))
    }

    @Test
    fun `LOW category pauses LOW only`() = runTest {
        val r = rig()
        NotificationSnoozeActionReceiver.handleSnoozeAction(
            snoozeIntent(SnoozeCategory.LOW, SnoozeDuration.M15),
            r.mgr
        )

        assertTrue(r.mgr.isAlertCategoryPaused(AlertCategory.LOW))
        assertFalse(r.mgr.isAlertCategoryPaused(AlertCategory.HIGH))
    }

    @Test
    fun `expiry reflects the duration extra`() = runTest {
        val r = rig()
        val before = System.currentTimeMillis()
        NotificationSnoozeActionReceiver.handleSnoozeAction(
            snoozeIntent(SnoozeCategory.ALL, SnoozeDuration.H2),
            r.mgr
        )
        val after = System.currentTimeMillis()

        val expiry = r.mgr.alertPauseExpiryMs(AlertCategory.LOW)
        assertNotNull(expiry)
        val twoHoursMs = SnoozeDuration.H2.durationMs
        assertTrue(
            "expiry within now+2h window (window expands with execution time)",
            expiry!! in (before + twoHoursMs)..(after + twoHoursMs)
        )
    }

    @Test
    fun `missing extras default to ALL plus 1h`() = runTest {
        val r = rig()
        val before = System.currentTimeMillis()
        NotificationSnoozeActionReceiver.handleSnoozeAction(Intent(), r.mgr)
        val after = System.currentTimeMillis()

        // Default category is ALL → both paused
        assertTrue(r.mgr.isAlertCategoryPaused(AlertCategory.LOW))
        assertTrue(r.mgr.isAlertCategoryPaused(AlertCategory.HIGH))
        // Default duration is H1
        val expiry = r.mgr.alertPauseExpiryMs(AlertCategory.LOW)
        val oneHourMs = SnoozeDuration.H1.durationMs
        assertTrue(
            "expiry within now+1h window",
            expiry!! in (before + oneHourMs)..(after + oneHourMs)
        )
    }

    @Test
    fun `garbage extras fall back to defaults`() = runTest {
        val r = rig()
        val intent = Intent().apply {
            putExtra(NotificationSnoozeActionReceiver.EXTRA_CATEGORY, "BOGUS")
            putExtra(NotificationSnoozeActionReceiver.EXTRA_DURATION, "ALSO_BOGUS")
        }
        NotificationSnoozeActionReceiver.handleSnoozeAction(intent, r.mgr)

        // Falls back to ALL + H1 — both categories paused
        assertTrue(r.mgr.isAlertCategoryPaused(AlertCategory.LOW))
        assertTrue(r.mgr.isAlertCategoryPaused(AlertCategory.HIGH))
    }
}
