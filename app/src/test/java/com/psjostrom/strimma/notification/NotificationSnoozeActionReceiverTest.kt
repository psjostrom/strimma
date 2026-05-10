package com.psjostrom.strimma.notification

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import com.psjostrom.strimma.testutil.workout.FakeCalendarPoller
import com.psjostrom.strimma.testutil.workout.MutableClock
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The receiver itself is a thin wrapper that reads two intent extras and dispatches
 * to AlertManager.pauseAllAlerts / pauseAlertCategory. Hilt-based broadcast tests
 * are heavy, so we skip the manifest dispatch and exercise the receiver instance
 * directly with manually-injected AlertManager — the receiver's contract is "given
 * (category, duration) extras, call the right pause API."
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotificationSnoozeActionReceiverTest {

    private fun kotlinx.coroutines.test.TestScope.alertManager(): Pair<Context, AlertManager> {
        val context: Context = ApplicationProvider.getApplicationContext()
        val ds = createTestDataStore(this)
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), ds)
        val clock = MutableClock(1_700_000_000_000L)
        val workoutMgr = WorkoutModeManager(settings, FakeCalendarPoller(), clock, backgroundScope)
        val mgr = AlertManager(context, settings, workoutMgr)
        return context to mgr
    }

    private fun snoozeIntent(category: SnoozeCategory, duration: SnoozeDuration): Intent =
        Intent().apply {
            putExtra(NotificationSnoozeActionReceiver.EXTRA_CATEGORY, category.name)
            putExtra(NotificationSnoozeActionReceiver.EXTRA_DURATION, duration.name)
        }

    @Test
    fun `ALL category pauses both LOW and HIGH`() = runTest {
        val (context, mgr) = alertManager()
        val receiver = NotificationSnoozeActionReceiver().apply { alertManager = mgr }

        receiver.onReceive(context, snoozeIntent(SnoozeCategory.ALL, SnoozeDuration.H1))

        assertTrue(mgr.isAlertCategoryPaused(AlertCategory.LOW))
        assertTrue(mgr.isAlertCategoryPaused(AlertCategory.HIGH))
    }

    @Test
    fun `HIGH category pauses HIGH only`() = runTest {
        val (context, mgr) = alertManager()
        val receiver = NotificationSnoozeActionReceiver().apply { alertManager = mgr }

        receiver.onReceive(context, snoozeIntent(SnoozeCategory.HIGH, SnoozeDuration.M30))

        assertTrue(mgr.isAlertCategoryPaused(AlertCategory.HIGH))
        assertFalse(mgr.isAlertCategoryPaused(AlertCategory.LOW))
    }

    @Test
    fun `LOW category pauses LOW only`() = runTest {
        val (context, mgr) = alertManager()
        val receiver = NotificationSnoozeActionReceiver().apply { alertManager = mgr }

        receiver.onReceive(context, snoozeIntent(SnoozeCategory.LOW, SnoozeDuration.M15))

        assertTrue(mgr.isAlertCategoryPaused(AlertCategory.LOW))
        assertFalse(mgr.isAlertCategoryPaused(AlertCategory.HIGH))
    }

    @Test
    fun `expiry reflects the duration extra`() = runTest {
        val (context, mgr) = alertManager()
        val receiver = NotificationSnoozeActionReceiver().apply { alertManager = mgr }

        val before = System.currentTimeMillis()
        receiver.onReceive(context, snoozeIntent(SnoozeCategory.ALL, SnoozeDuration.H2))
        val after = System.currentTimeMillis()

        val expiry = mgr.alertPauseExpiryMs(AlertCategory.LOW)
        assertNotNull(expiry)
        val twoHoursMs = SnoozeDuration.H2.durationMs
        assertTrue("expiry within now+2h window", expiry!! in (before + twoHoursMs)..(after + twoHoursMs))
    }

    @Test
    fun `missing extras default to ALL plus 1h`() = runTest {
        val (context, mgr) = alertManager()
        val receiver = NotificationSnoozeActionReceiver().apply { alertManager = mgr }

        // Empty intent — both extras absent
        val before = System.currentTimeMillis()
        receiver.onReceive(context, Intent())
        val after = System.currentTimeMillis()

        // Default category is ALL → both paused
        assertTrue(mgr.isAlertCategoryPaused(AlertCategory.LOW))
        assertTrue(mgr.isAlertCategoryPaused(AlertCategory.HIGH))
        // Default duration is H1
        val expiry = mgr.alertPauseExpiryMs(AlertCategory.LOW)
        val oneHourMs = SnoozeDuration.H1.durationMs
        assertTrue("expiry within now+1h", expiry!! in (before + oneHourMs)..(after + oneHourMs))
    }

    @Test
    fun `garbage extras fall back to defaults`() = runTest {
        val (context, mgr) = alertManager()
        val receiver = NotificationSnoozeActionReceiver().apply { alertManager = mgr }

        val intent = Intent().apply {
            putExtra(NotificationSnoozeActionReceiver.EXTRA_CATEGORY, "BOGUS")
            putExtra(NotificationSnoozeActionReceiver.EXTRA_DURATION, "ALSO_BOGUS")
        }
        receiver.onReceive(context, intent)

        // Falls back to ALL + H1 — both categories paused
        assertTrue(mgr.isAlertCategoryPaused(AlertCategory.LOW))
        assertTrue(mgr.isAlertCategoryPaused(AlertCategory.HIGH))
    }
}
