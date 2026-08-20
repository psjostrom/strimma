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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private data class Fixture(
        val settings: SettingsRepository,
        val viewModel: AlertsViewModel,
    )

    private fun runFixtureTest(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE)
            .edit().clear().apply()
        val settings = SettingsRepository(
            context,
            WidgetSettingsRepository(context),
            createTestDataStore(backgroundScope),
        )
        val workoutModeManager = WorkoutModeManager(
            settings,
            FakeCalendarPoller(),
            MutableClock(System.currentTimeMillis()),
            backgroundScope,
        )
        val alertManager = AlertManager(context, settings, workoutModeManager, backgroundScope)
        try {
            block(Fixture(settings, AlertsViewModel(settings, alertManager)))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `pauseAllAlerts produces a unified expiry equal to both per-category expiries`() = runFixtureTest { rig ->
        rig.viewModel.pauseAllAlerts(3_600_000L)

        val unified = rig.viewModel.unifiedPauseExpiryMs.first { it != null }
        assertEquals(rig.viewModel.pauseLowExpiryMs.first(), unified)
        assertEquals(rig.viewModel.pauseHighExpiryMs.first(), unified)
    }

    @Test
    fun `mismatched expiries leave unifiedPauseExpiryMs null`() = runFixtureTest { rig ->
        rig.viewModel.pauseAlerts(AlertCategory.LOW, 3_600_000L)
        rig.viewModel.pauseAlerts(AlertCategory.HIGH, 1_800_000L)

        assertNotNull(rig.viewModel.pauseLowExpiryMs.first())
        assertNotNull(rig.viewModel.pauseHighExpiryMs.first())
        assertNull(rig.viewModel.unifiedPauseExpiryMs.first())
    }

    @Test
    fun `cancelAllAlertPauses clears low, high, and the unified flow`() = runFixtureTest { rig ->
        rig.viewModel.pauseAllAlerts(3_600_000L)
        rig.viewModel.unifiedPauseExpiryMs.first { it != null }

        rig.viewModel.cancelAllAlertPauses()

        assertNull(rig.viewModel.pauseLowExpiryMs.first())
        assertNull(rig.viewModel.pauseHighExpiryMs.first())
        assertNull(rig.viewModel.unifiedPauseExpiryMs.first())
    }

    @Test
    fun `cancelling a single category under a unified pause collapses unified to null`() = runFixtureTest { rig ->
        // The most important transition for the sheet: when one category is cancelled
        // out of a unified pause, the unified state must collapse so per-category rows
        // reappear and the user can manage the still-paused side.
        rig.viewModel.pauseAllAlerts(3_600_000L)
        rig.viewModel.unifiedPauseExpiryMs.first { it != null }

        rig.viewModel.cancelAlertPause(AlertCategory.LOW)

        assertNull(rig.viewModel.pauseLowExpiryMs.first())
        assertNotNull(rig.viewModel.pauseHighExpiryMs.first())
        assertNull(rig.viewModel.unifiedPauseExpiryMs.first())
    }

    @Test
    fun `exercise threshold setter changes exercise protocol without changing regular value`() = runFixtureTest { rig ->
        rig.settings.setAlertLow(100f)
        rig.settings.setExerciseAlertLow(120f)

        rig.viewModel.setExerciseAlertLow(140f)

        assertEquals(
            140f,
            rig.viewModel.exerciseAlertProtocol.filterNotNull().first { it.lowMgdl == 140f }.lowMgdl
        )
        assertEquals(100f, rig.settings.alertLow.first())
    }

    @Test
    fun `invalid exercise threshold ordering leaves prior exercise value unchanged`() = runFixtureTest { rig ->
        rig.viewModel.setExerciseAlertLow(120f)
        rig.settings.exerciseAlertLow.first { it == 120f }

        val validation = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            rig.viewModel.validationError.first()
        }
        rig.viewModel.setExerciseAlertUrgentLow(130f)

        assertEquals(AlertsViewModel.ValidationError.Order, validation.await())
        assertEquals(90f, rig.settings.exerciseAlertUrgentLow.first())
    }

    @Test
    fun `grouped protocol state is unavailable until DataStore emits`() = runFixtureTest { rig ->
        assertNull(rig.viewModel.regularAlertProtocol.value)
        assertNull(rig.viewModel.exerciseAlertProtocol.value)
    }

    @Test
    fun `concurrent exercise threshold edits leave persisted protocol ordered`() = runFixtureTest { rig ->
        val jobs = listOf(
            rig.viewModel.setExerciseAlertLow(240f),
            rig.viewModel.setExerciseAlertHigh(200f),
        )

        jobs.joinAll()

        val protocol = rig.settings.exerciseAlertProtocol.first()
        assertTrue(protocol.urgentLowMgdl <= protocol.lowMgdl)
        assertTrue(protocol.lowMgdl <= protocol.highMgdl)
        assertTrue(protocol.highMgdl <= protocol.urgentHighMgdl)
    }
}
