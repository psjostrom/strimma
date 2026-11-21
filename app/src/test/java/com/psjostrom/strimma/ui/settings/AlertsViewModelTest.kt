package com.psjostrom.strimma.ui.settings

import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.workout.AlertSet
import com.psjostrom.strimma.data.workout.NightWindow
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import com.psjostrom.strimma.notification.AlertCategory
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.testutil.workout.FakeCalendarPoller
import com.psjostrom.strimma.testutil.workout.MutableClock
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
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
import org.robolectric.shadows.ShadowToast
import java.time.Instant

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
        val context: Context,
        val notifManager: NotificationManager,
        val settings: SettingsRepository,
        val viewModel: AlertsViewModel,
    )

    private fun runFixtureTest(
        nowMs: Long = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli(),
        setup: suspend (SettingsRepository) -> Unit = {},
        block: suspend TestScope.(Fixture) -> Unit,
    ) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.cancelAll()
        var vm: AlertsViewModel? = null
        try {
            context.getSharedPreferences("strimma_snooze", Context.MODE_PRIVATE)
                .edit().clear().apply()
            val settings = SettingsRepository(
                context,
                WidgetSettingsRepository(context),
                createTestDataStore(backgroundScope),
            )
            setup(settings)
            val workoutModeManager = WorkoutModeManager(
                settings,
                FakeCalendarPoller(),
                MutableClock(nowMs),
                backgroundScope,
            )
            val alertManager = AlertManager(context, settings, workoutModeManager, backgroundScope)
            val viewModel = AlertsViewModel(settings, alertManager, workoutModeManager, backgroundScope, context)
            vm = viewModel
            block(Fixture(context, notifManager, settings, viewModel))
        } finally {
            vm?.viewModelScope?.cancel()
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

        rig.viewModel.setExerciseAlertUrgentLow(130f).join()

        assertEquals(rig.context.getString(R.string.alert_threshold_order_error), ShadowToast.getTextOfLatestToast())
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

    @Test
    fun `setPatternAlertsEnabled persists setting`() = runFixtureTest { rig ->
        rig.viewModel.setPatternAlertsEnabled(false).join()
        assertEquals(false, rig.settings.patternAlertsEnabled.first())

        rig.viewModel.setPatternAlertsEnabled(true).join()
        assertEquals(true, rig.settings.patternAlertsEnabled.first())
    }

    @Test
    fun `window edit making start equal end is rejected`() = runFixtureTest { rig ->
        rig.viewModel.setNightWindowEnd(23 * 60).join()

        assertEquals(rig.context.getString(R.string.settings_alerts_night_empty_window), ShadowToast.getTextOfLatestToast())
        assertEquals(NightWindow.DEFAULT, rig.settings.nightWindow.first())
    }

    @Test
    fun `valid window edits persist`() = runFixtureTest { rig ->
        rig.viewModel.setNightWindowStart(22 * 60).join()
        rig.viewModel.setNightWindowEnd(6 * 60).join()

        assertEquals(NightWindow(22 * 60, 6 * 60), rig.settings.nightWindow.first())
    }

    @Test
    fun `night threshold out of order is rejected`() = runFixtureTest { rig ->
        rig.viewModel.setNightAlertsEnabled(true).join()
        rig.viewModel.setNightAlertLow(300f).join()

        assertEquals(rig.context.getString(R.string.alert_threshold_order_error), ShadowToast.getTextOfLatestToast())
        assertEquals(rig.settings.alertLow.first(), rig.settings.nightAlertProtocol.first().lowMgdl)
    }

    @Test
    fun `day threshold out of order is rejected`() = runFixtureTest { rig ->
        rig.viewModel.setAlertHigh(60f).join()

        assertEquals(rig.context.getString(R.string.alert_threshold_order_error), ShadowToast.getTextOfLatestToast())
        assertEquals(SettingsRepository.DEFAULT_ALERT_HIGH, rig.settings.alertHigh.first())
    }

    @Test
    fun `day set stored with two pairs out of order can be repaired one field at a time`() = runFixtureTest(
        setup = { settings ->
            settings.setAlertUrgentLow(100f)
            settings.setAlertHigh(300f)
        },
    ) { rig ->
        rig.viewModel.setAlertLow(150f).join()
        rig.viewModel.setAlertUrgentHigh(350f).join()

        assertEquals(150f, rig.settings.alertLow.first())
        assertEquals(350f, rig.settings.alertUrgentHigh.first())
    }

    @Test
    fun `edit to a day set stored out of order is rejected when it breaks a pair that was in order`() = runFixtureTest(
        setup = { settings -> settings.setAlertUrgentLow(100f) },
    ) { rig ->
        rig.viewModel.setAlertHigh(60f).join()

        assertEquals(rig.context.getString(R.string.alert_threshold_order_error), ShadowToast.getTextOfLatestToast())
        assertEquals(SettingsRepository.DEFAULT_ALERT_HIGH, rig.settings.alertHigh.first())
    }

    @Test
    fun `threshold save finishes after the ViewModel is cleared`() = runFixtureTest { rig ->
        val save = rig.viewModel.setAlertHigh(200f)
        rig.viewModel.viewModelScope.cancel()
        save.join()

        assertEquals(200f, rig.settings.alertHigh.first())
    }

    @Test
    fun `rejected save after the ViewModel is cleared still shows the message`() = runFixtureTest { rig ->
        val save = rig.viewModel.setAlertHigh(60f)
        rig.viewModel.viewModelScope.cancel()
        save.join()

        assertEquals(rig.context.getString(R.string.alert_threshold_order_error), ShadowToast.getTextOfLatestToast())
        assertEquals(SettingsRepository.DEFAULT_ALERT_HIGH, rig.settings.alertHigh.first())
    }

    @Test
    fun `valid day threshold persists`() = runFixtureTest { rig ->
        rig.viewModel.setAlertHigh(200f).join()

        assertEquals(200f, rig.settings.alertHigh.first())
    }

    @Test
    fun `valid night threshold persists`() = runFixtureTest { rig ->
        rig.viewModel.setNightAlertsEnabled(true).join()
        rig.viewModel.setNightAlertLow(100f).join()

        assertEquals(100f, rig.settings.nightAlertProtocol.first().lowMgdl)
        assertEquals(true, rig.viewModel.nightAlertsEnabled.filterNotNull().first())
    }

    @Test
    fun `active set reports Night inside the night window`() = runFixtureTest(
        nowMs = Instant.parse("2026-01-15T02:00:00Z").toEpochMilli(),
        setup = { it.setNightAlertsEnabled(true) },
    ) { rig ->
        val set = withTimeout(5_000) { rig.viewModel.activeSet.filterNotNull().first() }

        assertEquals(AlertSet.NIGHT, set)
    }
}
