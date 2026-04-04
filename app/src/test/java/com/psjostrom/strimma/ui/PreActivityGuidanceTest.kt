package com.psjostrom.strimma.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.TreatmentDao
import com.psjostrom.strimma.data.calendar.CalendarReader
import com.psjostrom.strimma.data.calendar.GuidanceState
import com.psjostrom.strimma.data.health.ExerciseBGAnalyzer
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.data.meal.MealAnalyzer
import com.psjostrom.strimma.network.LibreLinkUpFollower
import com.psjostrom.strimma.network.NightscoutFollower
import com.psjostrom.strimma.network.NightscoutPuller
import com.psjostrom.strimma.network.NightscoutPusher
import com.psjostrom.strimma.network.TreatmentSyncer
import com.psjostrom.strimma.testutil.FakeCalendarProvider
import com.psjostrom.strimma.tidepool.TidepoolAuthManager
import com.psjostrom.strimma.update.UpdateChecker
import com.psjostrom.strimma.update.UpdateInstaller
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class PreActivityGuidanceTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var dao: ReadingDao
    @Inject lateinit var treatmentDao: TreatmentDao
    @Inject lateinit var exerciseDao: ExerciseDao
    @Inject lateinit var exerciseBGAnalyzer: ExerciseBGAnalyzer
    @Inject lateinit var calendarReader: CalendarReader
    @Inject lateinit var nightscoutFollower: NightscoutFollower
    @Inject lateinit var libreLinkUpFollower: LibreLinkUpFollower
    @Inject lateinit var nightscoutPuller: NightscoutPuller
    @Inject lateinit var nightscoutPusher: NightscoutPusher
    @Inject lateinit var treatmentSyncer: TreatmentSyncer
    @Inject lateinit var mealAnalyzer: MealAnalyzer
    @Inject lateinit var tidepoolAuthManager: TidepoolAuthManager
    @Inject lateinit var tidepoolUploader: com.psjostrom.strimma.tidepool.TidepoolUploader
    @Inject lateinit var updateChecker: UpdateChecker
    @Inject lateinit var updateInstaller: UpdateInstaller

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        hiltRule.inject()

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)

        val providerInfo = ProviderInfo().apply {
            authority = CalendarContract.AUTHORITY
        }
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java)
            .create(providerInfo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun insertCalendarEvent(calendarId: Long, title: String, startTime: Long, endTime: Long) {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startTime)
            put(CalendarContract.Events.DTEND, endTime)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        }
        app.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
    }

    private fun createViewModel() = MainViewModel(
        dao, treatmentDao, exerciseDao, exerciseBGAnalyzer, settings,
        nightscoutFollower, libreLinkUpFollower, nightscoutPuller, nightscoutPusher,
        treatmentSyncer, calendarReader, mealAnalyzer, tidepoolAuthManager,
        tidepoolUploader, updateChecker, updateInstaller
    )

    // --- CalendarReader delegation ---

    @Test
    fun `getNextWorkout delegates to getUpcomingWorkouts`() = runTest {
        val calendarId = 1L
        val now = System.currentTimeMillis()
        insertCalendarEvent(calendarId, "Easy Run", now + 3600_000L, now + 6000_000L)

        val result = calendarReader.getNextWorkout(calendarId, 4 * 3600_000L)
        val allResults = calendarReader.getUpcomingWorkouts(calendarId, 4 * 3600_000L)

        assertTrue("getNextWorkout should return a workout", result != null)
        assertEquals(allResults.first(), result)
    }

    @Test
    fun `getNextWorkout returns null when no events in range`() = runTest {
        val calendarId = 1L
        val now = System.currentTimeMillis()
        insertCalendarEvent(calendarId, "Easy Run", now + 24 * 3600_000L, now + 25 * 3600_000L)

        val result = calendarReader.getNextWorkout(calendarId, 3 * 3600_000L)
        assertTrue("getNextWorkout should return null for out-of-range events", result == null)
    }

    @Test
    fun `getNextWorkout returns earliest event`() = runTest {
        val calendarId = 1L
        val now = System.currentTimeMillis()
        insertCalendarEvent(calendarId, "Tempo Run", now + 3 * 3600_000L, now + 4 * 3600_000L)
        insertCalendarEvent(calendarId, "Easy Run", now + 1 * 3600_000L, now + 2 * 3600_000L)

        val result = calendarReader.getNextWorkout(calendarId, 5 * 3600_000L)
        assertTrue("getNextWorkout should return the earliest event", result != null)
        assertEquals("Easy Run", result!!.title)
    }

    // --- computeGuidance ---

    @Test
    fun `computeGuidance returns WorkoutApproaching for in-range BG with upcoming event`() = runTest {
        val calendarId = 1L
        val now = System.currentTimeMillis()
        insertCalendarEvent(calendarId, "Easy Run", now + 3600_000L, now + 6000_000L)

        val event = calendarReader.getNextWorkout(calendarId, 4 * 3600_000L)
        assertTrue("Precondition: event must exist", event != null)

        val reading = GlucoseReading(
            ts = now - 60_000L, sgv = 140, direction = "Flat", delta = null, pushed = 1
        )
        val guidance = MainViewModel.computeGuidance(
            event = event, latest = reading, allReadings = listOf(reading),
            iob = 0.0, targetLow = 126f, targetHigh = 162f,
            bgLow = 70.0, bgHigh = 180.0, nowMs = now
        )

        assertTrue("Should be WorkoutApproaching", guidance is GuidanceState.WorkoutApproaching)
    }

    // --- ViewModel reactive DataStore ---

    @Test
    fun `guidanceState reacts to workoutCalendarId without waiting for poll`() = runTest {
        val calendarId = 1L
        val now = System.currentTimeMillis()
        insertCalendarEvent(calendarId, "Easy Run", now + 3600_000L, now + 6000_000L)

        // Insert a BG reading so guidance can compute
        dao.insert(
            GlucoseReading(ts = now - 60_000L, sgv = 140, direction = "Flat", delta = null, pushed = 1)
        )

        // Set calendar ID BEFORE creating ViewModel — simulates a configured calendar
        settings.setWorkoutCalendarId(calendarId)
        settings.setWorkoutCalendarName("Test Calendar")

        // Let DataStore persist
        advanceUntilIdle()

        // Create ViewModel — its init should collect the calendarId immediately
        val viewModel = createViewModel()

        // Subscribe on a real dispatcher so Room flows can emit
        val state = withContext(Dispatchers.Default) {
            withTimeout(5000) {
                viewModel.guidanceState.first { it is GuidanceState.WorkoutApproaching }
            }
        }
        assertTrue(
            "guidanceState should be WorkoutApproaching, got $state",
            state is GuidanceState.WorkoutApproaching
        )
    }

    @Test
    fun `guidanceState stays NoWorkout when calendar not configured`() = runTest {
        val now = System.currentTimeMillis()
        dao.insert(
            GlucoseReading(ts = now - 60_000L, sgv = 140, direction = "Flat", delta = null, pushed = 1)
        )

        // Don't set any calendar ID — default is -1
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(GuidanceState.NoWorkout, viewModel.guidanceState.value)
    }
}
