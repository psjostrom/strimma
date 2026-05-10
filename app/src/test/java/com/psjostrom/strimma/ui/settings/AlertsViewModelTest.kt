package com.psjostrom.strimma.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import com.psjostrom.strimma.notification.AlertCategory
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.testutil.workout.FakeCalendarPoller
import com.psjostrom.strimma.testutil.workout.MutableClock
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [AlertsViewModel.unifiedPauseExpiryMs] — the single owner of the "is the
 * pause unified?" rule. Both the BG-screen pill and the pause sheet read this; if
 * the derivation drifts, both surfaces flip silently.
 *
 * Real AlertManager + real SettingsRepository (Robolectric DataStore) per Strimma's
 * "no mocking internal modules" rule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AlertsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var alertManager: AlertManager
    private lateinit var viewModel: AlertsViewModel
    private lateinit var managerScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        // Wipe any pause prefs left over from previous tests in the same VM/process.
        context.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE)
            .edit().clear().apply()

        settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        val poller = FakeCalendarPoller()
        val clock = MutableClock(System.currentTimeMillis())
        // Cancelled in @After so the eager ticker doesn't leak across tests.
        managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val workoutModeManager = WorkoutModeManager(settings, poller, clock, managerScope)
        alertManager = AlertManager(context, settings, workoutModeManager, managerScope)
        viewModel = AlertsViewModel(settings, alertManager)
    }

    @After
    fun tearDown() {
        managerScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `pauseAllAlerts produces a unified expiry equal to both per-category expiries`() = runTest {
        viewModel.pauseAllAlerts(3_600_000L)

        val unified = viewModel.unifiedPauseExpiryMs.first { it != null }
        assertEquals(viewModel.pauseLowExpiryMs.first(), unified)
        assertEquals(viewModel.pauseHighExpiryMs.first(), unified)
    }

    @Test
    fun `mismatched expiries leave unifiedPauseExpiryMs null`() = runTest {
        viewModel.pauseAlerts(AlertCategory.LOW, 3_600_000L)
        viewModel.pauseAlerts(AlertCategory.HIGH, 1_800_000L)

        assertNotNull(viewModel.pauseLowExpiryMs.first())
        assertNotNull(viewModel.pauseHighExpiryMs.first())
        assertNull(viewModel.unifiedPauseExpiryMs.first())
    }

    @Test
    fun `cancelAllAlertPauses clears low, high, and the unified flow`() = runTest {
        viewModel.pauseAllAlerts(3_600_000L)
        viewModel.unifiedPauseExpiryMs.first { it != null } // wait until unified emits

        viewModel.cancelAllAlertPauses()

        assertNull(viewModel.pauseLowExpiryMs.first())
        assertNull(viewModel.pauseHighExpiryMs.first())
        assertNull(viewModel.unifiedPauseExpiryMs.first())
    }

    @Test
    fun `cancelling a single category under a unified pause collapses unified to null`() = runTest {
        // The most important transition for the sheet: when one category is cancelled
        // out of a unified pause, the unified state must collapse so per-category rows
        // reappear and the user can manage the still-paused side.
        viewModel.pauseAllAlerts(3_600_000L)
        viewModel.unifiedPauseExpiryMs.first { it != null }

        viewModel.cancelAlertPause(AlertCategory.LOW)

        assertNull(viewModel.pauseLowExpiryMs.first())
        assertNotNull(viewModel.pauseHighExpiryMs.first())
        assertNull(viewModel.unifiedPauseExpiryMs.first())
    }
}
