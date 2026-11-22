package com.psjostrom.strimma.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.health.ExerciseCategory
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
    }

    @Test
    fun `setGlucoseSource updates both DataStore and SharedPreferences`() = runTest {
        repo.setGlucoseSource(GlucoseSource.NIGHTSCOUT_FOLLOWER)

        val fromFlow = repo.glucoseSource.first()
        val fromSync = repo.getGlucoseSourceSync()

        assertEquals(GlucoseSource.NIGHTSCOUT_FOLLOWER, fromFlow)
        assertEquals(GlucoseSource.NIGHTSCOUT_FOLLOWER, fromSync)
    }

    @Test
    fun `setStartOnBoot updates both DataStore and SharedPreferences`() = runTest {
        repo.setStartOnBoot(false)

        val fromFlow = repo.startOnBoot.first()
        val fromSync = repo.getStartOnBootSync()

        assertEquals(false, fromFlow)
        assertEquals(false, fromSync)
    }

    @Test
    fun `glucoseSource does not emit again when another setting is written`() = runTest {
        val store = createTestDataStore(scope = backgroundScope)
        val repo = SettingsRepository(context, WidgetSettingsRepository(context), store)
        val seen = mutableListOf<GlucoseSource>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.glucoseSource.toList(seen)
        }
        advanceUntilIdle()

        repo.setHcLastSync(1_000L)
        advanceUntilIdle()
        repo.setGlucoseSource(GlucoseSource.LIBRELINKUP)
        advanceUntilIdle()

        assertEquals(listOf(GlucoseSource.COMPANION, GlucoseSource.LIBRELINKUP), seen)
    }

    @Test
    fun `getGlucoseSourceSync defaults to COMPANION when unset`() {
        assertEquals(GlucoseSource.COMPANION, repo.getGlucoseSourceSync())
    }

    @Test
    fun `getStartOnBootSync defaults to true when unset`() {
        assertEquals(true, repo.getStartOnBootSync())
    }

    // --- RetentionPolicy ---

    @Test
    fun `retentionPolicy defaults to INDEFINITE when unset`() = runTest {
        assertEquals(RetentionPolicy.INDEFINITE, repo.retentionPolicy.first())
    }

    @Test
    fun `setRetentionPolicy round-trips a written value`() = runTest {
        repo.setRetentionPolicy(RetentionPolicy.SIX_MONTHS)
        assertEquals(RetentionPolicy.SIX_MONTHS, repo.retentionPolicy.first())

        repo.setRetentionPolicy(RetentionPolicy.INDEFINITE)
        assertEquals(RetentionPolicy.INDEFINITE, repo.retentionPolicy.first())
    }

    @Test
    fun `exercise target edit to one end keeps the other end`() = runTest {
        repo.setExerciseTargetHigh(ExerciseCategory.RUNNING, 200f)
        repo.setExerciseTargetLow(ExerciseCategory.RUNNING, 110f)

        assertEquals(110f, repo.exerciseTargetLow(ExerciseCategory.RUNNING).first())
        assertEquals(200f, repo.exerciseTargetHigh(ExerciseCategory.RUNNING).first())
    }

    @Test
    fun `exercise target Low at or above High is not stored`() = runTest {
        val low = repo.exerciseTargetLow(ExerciseCategory.RUNNING).first()
        val high = repo.exerciseTargetHigh(ExerciseCategory.RUNNING).first()

        assertEquals(false, repo.setExerciseTargetLow(ExerciseCategory.RUNNING, high))
        assertEquals(false, repo.setExerciseTargetHigh(ExerciseCategory.RUNNING, low))

        assertEquals(low, repo.exerciseTargetLow(ExerciseCategory.RUNNING).first())
        assertEquals(high, repo.exerciseTargetHigh(ExerciseCategory.RUNNING).first())
    }
}
