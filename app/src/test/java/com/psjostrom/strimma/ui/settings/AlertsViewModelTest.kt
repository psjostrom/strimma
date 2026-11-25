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
import com.psjostrom.strimma.notification.PauseScope
import com.psjostrom.strimma.notification.activePauseIndicators
import com.psjostrom.strimma.notification.notificationLabel
import com.psjostrom.strimma.notification.pillLabel
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
 * Pins the pause state the BG-screen pills, the header icon and the pause sheet show,
 * which they all derive from the view model's stored pauses through
 * activePauseIndicators.
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
        val alertManager: AlertManager,
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
            val alertManager = AlertManager(context, settings, workoutModeManager)
            val viewModel = AlertsViewModel(settings, alertManager, workoutModeManager, backgroundScope, context)
            vm = viewModel
            block(Fixture(context, notifManager, settings, viewModel, alertManager))
        } finally {
            vm?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun AlertsViewModel.pausesAt(nowMs: Long = System.currentTimeMillis()) =
        activePauseIndicators(pauseLow.value, pauseHigh.value, nowMs)

    @Test
    fun `pause all shows one All indicator`() = runFixtureTest { rig ->
        rig.viewModel.pauseAllAlerts(3_600_000L)

        assertEquals(listOf(PauseScope.ALL), rig.viewModel.pausesAt().map { it.scope })
    }

    @Test
    fun `low and high paused for different times show one indicator each with its own expiry`() =
        runFixtureTest { rig ->
            rig.viewModel.pauseAlerts(AlertCategory.LOW, 1_800_000L)
            rig.viewModel.pauseAlerts(AlertCategory.HIGH, 7_200_000L)

            val pauses = rig.viewModel.pausesAt()

            assertEquals(listOf(PauseScope.LOW, PauseScope.HIGH), pauses.map { it.scope })
            assertEquals(rig.viewModel.pauseLow.value!!.expiryMs, pauses[0].expiryMs)
            assertEquals(rig.viewModel.pauseHigh.value!!.expiryMs, pauses[1].expiryMs)
            assertEquals(
                listOf(R.string.notif_alerts_paused_low, R.string.notif_alerts_paused_high),
                pauses.map { it.notificationLabel() }
            )
        }

    @Test
    fun `snoozing a low soon alert shows only low soon as paused`() = runFixtureTest { rig ->
        rig.alertManager.snooze(AlertManager.ALERT_LOW_SOON_ID, 1_800_000L)

        val pause = rig.viewModel.pausesAt().single()

        assertEquals("Low soon paused · 30m", rig.context.getString(pause.pillLabel(), "30m"))
    }

    @Test
    fun `snoozing a low alert says that urgent low stays on`() = runFixtureTest { rig ->
        rig.alertManager.snooze(AlertManager.ALERT_LOW_ID, 1_800_000L)

        val pause = rig.viewModel.pausesAt().single()

        assertEquals("Low paused, urgent on · 30m", rig.context.getString(pause.pillLabel(), "30m"))
    }

    @Test
    fun `a pause stops showing when the wall clock passes its expiry`() = runFixtureTest { rig ->
        rig.viewModel.pauseAlerts(AlertCategory.LOW, 1_800_000L)
        val expiry = rig.viewModel.pauseLow.value!!.expiryMs

        assertEquals(1, rig.viewModel.pausesAt(expiry - 1).size)
        assertTrue(rig.viewModel.pausesAt(expiry).isEmpty())
    }

    @Test
    fun `cancelAllAlertPauses clears every pause`() = runFixtureTest { rig ->
        rig.viewModel.pauseAllAlerts(3_600_000L)

        rig.viewModel.cancelAllAlertPauses()

        assertTrue(rig.viewModel.pausesAt().isEmpty())
    }

    @Test
    fun `cancelling one category under pause all leaves the other category paused`() = runFixtureTest { rig ->
        // When one category is cancelled out of a pause-all, the sheet must fall back to
        // per-category rows so the user can manage the side that is still paused.
        rig.viewModel.pauseAllAlerts(3_600_000L)

        rig.viewModel.cancelAlertPause(AlertCategory.LOW)

        assertEquals(listOf(PauseScope.HIGH), rig.viewModel.pausesAt().map { it.scope })
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
