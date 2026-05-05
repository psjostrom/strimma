package com.psjostrom.strimma.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins behavior of [WorkoutSettingsViewModel]:
 * - Reset restores thresholds but deliberately leaves `maxHours` untouched
 * - Persistence round-trips correctly
 * - Threshold-ordering invariant is enforced (urgent_low ≤ low ≤ high ≤ urgent_high)
 *
 * Real DataStore-backed [SettingsRepository] per Strimma's "no mocking internal modules" rule.
 * The DataStore is bound to runTest's TestScope (not Dispatchers.IO) so virtual-time
 * advancement actually drains the writes — otherwise the tests race the real IO threads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WorkoutSettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.makeFixture(): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Bind DataStore to the test scope so writes happen on the test dispatcher
        // and `runTest`'s virtual-time advancement actually drains them.
        val ds = createTestDataStore(this)
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), ds)
        val vm = WorkoutSettingsViewModel(settings)
        return Fixture(settings, vm)
    }

    private data class Fixture(val settings: SettingsRepository, val vm: WorkoutSettingsViewModel)

    @Test
    fun `defaults match SettingsRepository constants`() = runTest {
        val (settings, _) = makeFixture()
        assertEquals(SettingsRepository.DEFAULT_WORKOUT_ALERT_LOW, settings.workoutAlertLow.first())
        assertEquals(SettingsRepository.DEFAULT_WORKOUT_ALERT_URGENT_LOW, settings.workoutAlertUrgentLow.first())
        assertEquals(SettingsRepository.DEFAULT_WORKOUT_ALERT_HIGH, settings.workoutAlertHigh.first())
        assertEquals(SettingsRepository.DEFAULT_WORKOUT_ALERT_URGENT_HIGH, settings.workoutAlertUrgentHigh.first())
    }

    @Test
    fun `setLow persists across VM re-instantiation`() = runTest {
        val (settings, vm) = makeFixture()
        vm.setLow(120f)
        settings.workoutAlertLow.first { it == 120f }

        val vm2 = WorkoutSettingsViewModel(settings)
        assertEquals(120f, vm2.workoutLow.first { it == 120f })
    }

    @Test
    fun `resetToDefaults restores all four thresholds but leaves maxHours untouched`() = runTest {
        val (settings, vm) = makeFixture()
        vm.setMaxHours(7)
        vm.setUrgentLow(80f)
        vm.setLow(100f)
        vm.setHigh(220f)
        vm.setUrgentHigh(280f)

        // Wait for all five writes to land before continuing.
        settings.workoutModeMaxHours.first { it == 7 }
        settings.workoutAlertUrgentLow.first { it == 80f }
        settings.workoutAlertLow.first { it == 100f }
        settings.workoutAlertHigh.first { it == 220f }
        settings.workoutAlertUrgentHigh.first { it == 280f }

        vm.resetToDefaults()

        // Four thresholds reset to defaults...
        settings.workoutAlertLow.first { it == SettingsRepository.DEFAULT_WORKOUT_ALERT_LOW }
        settings.workoutAlertUrgentLow.first { it == SettingsRepository.DEFAULT_WORKOUT_ALERT_URGENT_LOW }
        settings.workoutAlertHigh.first { it == SettingsRepository.DEFAULT_WORKOUT_ALERT_HIGH }
        settings.workoutAlertUrgentHigh.first { it == SettingsRepository.DEFAULT_WORKOUT_ALERT_URGENT_HIGH }
        // ...but maxHours is NOT reset (deliberate — auto-off preference is independent of thresholds).
        assertEquals(7, settings.workoutModeMaxHours.first())
    }

    @Test
    fun `setLow rejects a value greater than current high`() = runTest {
        val (settings, vm) = makeFixture()
        // Defaults: high = 252. Try to set low above it.
        vm.setLow(260f)
        // Wait for the validation pipeline to settle: the validationError fires after the
        // ordering check rejects the write, so once we observe it the write attempt is done.
        vm.validationError.first()
        // Persisted value unchanged.
        assertEquals(SettingsRepository.DEFAULT_WORKOUT_ALERT_LOW, settings.workoutAlertLow.first())
    }

    @Test
    fun `setUrgentLow rejects a value greater than current low`() = runTest {
        val (settings, vm) = makeFixture()
        // Defaults: low = 108, urgent_low = 90. Try to set urgent_low above low.
        vm.setUrgentLow(120f)
        vm.validationError.first()
        assertEquals(SettingsRepository.DEFAULT_WORKOUT_ALERT_URGENT_LOW, settings.workoutAlertUrgentLow.first())
    }

    @Test
    fun `setHigh rejects a value less than current low`() = runTest {
        val (settings, vm) = makeFixture()
        // Defaults: low = 108. Try to set high below it.
        vm.setHigh(100f)
        vm.validationError.first()
        assertEquals(SettingsRepository.DEFAULT_WORKOUT_ALERT_HIGH, settings.workoutAlertHigh.first())
    }

    @Test
    fun `setUrgentHigh rejects a value less than current high`() = runTest {
        val (settings, vm) = makeFixture()
        // Defaults: high = 252. Try to set urgent_high below it.
        vm.setUrgentHigh(200f)
        vm.validationError.first()
        assertEquals(SettingsRepository.DEFAULT_WORKOUT_ALERT_URGENT_HIGH, settings.workoutAlertUrgentHigh.first())
    }

    @Test
    fun `validation error is surfaced through validationError flow`() = runTest {
        val (_, vm) = makeFixture()
        val deferred = backgroundScope.async {
            vm.validationError.first()
        }
        // Provoke an order violation.
        vm.setUrgentLow(200f)
        val error = deferred.await()
        assertNotNull(error)
        assertEquals(WorkoutSettingsViewModel.ValidationError.Order, error)
    }

    @Test
    fun `valid threshold change persists`() = runTest {
        val (settings, vm) = makeFixture()
        // Defaults: urgentLow=90, low=108, high=252, urgentHigh=288.
        // 110 fits between low (108) and high (252), so setting it as the new low keeps the invariant.
        vm.setLow(110f)
        settings.workoutAlertLow.first { it == 110f }
        assertNotEquals(SettingsRepository.DEFAULT_WORKOUT_ALERT_LOW, settings.workoutAlertLow.first())
    }
}
