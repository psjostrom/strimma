package com.psjostrom.strimma.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins behavior of [WorkoutSettingsViewModel]:
 * - Auto-off duration persists through the real SettingsRepository
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
    fun `defaults match SettingsRepository constant`() = runTest {
        val (settings, vm) = makeFixture()
        assertEquals(SettingsRepository.DEFAULT_WORKOUT_MODE_MAX_HOURS, settings.workoutModeMaxHours.first())
        assertEquals(SettingsRepository.DEFAULT_WORKOUT_MODE_MAX_HOURS, vm.maxHours.first())
    }

    @Test
    fun `setMaxHours persists across VM re-instantiation`() = runTest {
        val (settings, vm) = makeFixture()
        vm.setMaxHours(7)
        assertEquals(7, settings.workoutModeMaxHours.first { it == 7 })

        val vm2 = WorkoutSettingsViewModel(settings)
        assertEquals(7, vm2.maxHours.first { it == 7 })
    }
}
