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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `exercise threshold setter changes exercise protocol without changing regular value`() = runTest {
        settings.setAlertLow(100f)
        settings.setExerciseAlertLow(120f)

        viewModel.setExerciseAlertLow(140f)

        assertEquals(
            140f,
            viewModel.exerciseAlertProtocol.filterNotNull().first { it.lowMgdl == 140f }.lowMgdl
        )
        assertEquals(100f, settings.alertLow.first())
    }

    @Test
    fun `invalid exercise threshold ordering leaves prior exercise value unchanged`() = runTest {
        viewModel.setExerciseAlertLow(120f)
        settings.exerciseAlertLow.first { it == 120f }

        val validation = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.validationError.first()
        }
        viewModel.setExerciseAlertUrgentLow(130f)

        assertEquals(AlertsViewModel.ValidationError.Order, validation.await())
        assertEquals(90f, settings.exerciseAlertUrgentLow.first())
    }

    @Test
    fun `grouped protocol state is unavailable until DataStore emits`() {
        assertNull(viewModel.regularAlertProtocol.value)
        assertNull(viewModel.exerciseAlertProtocol.value)
    }

    @Test
    fun `concurrent exercise threshold edits leave persisted protocol ordered`() = runTest {
        val jobs = listOf(
            viewModel.setExerciseAlertLow(240f),
            viewModel.setExerciseAlertHigh(200f),
        )

        jobs.joinAll()

        val protocol = settings.exerciseAlertProtocol.first()
        assertTrue(protocol.urgentLowMgdl <= protocol.lowMgdl)
        assertTrue(protocol.lowMgdl <= protocol.highMgdl)
        assertTrue(protocol.highMgdl <= protocol.urgentHighMgdl)
    }
}
