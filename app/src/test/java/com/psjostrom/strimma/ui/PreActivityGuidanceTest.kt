package com.psjostrom.strimma.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.calendar.CalendarReader
import com.psjostrom.strimma.data.calendar.GuidanceState
import com.psjostrom.strimma.testutil.FakeCalendarProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var dao: ReadingDao
    @Inject lateinit var calendarReader: CalendarReader

    @Before
    fun setUp() {
        hiltRule.inject()

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)

        // Register fake CalendarContract provider (the "MSW" boundary)
        val providerInfo = ProviderInfo().apply {
            authority = CalendarContract.AUTHORITY
        }
        Robolectric.buildContentProvider(FakeCalendarProvider::class.java)
            .create(providerInfo)
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

    @Test
    fun `getNextWorkout delegates to getUpcomingWorkouts`() = runTest {
        val calendarId = 1L
        val now = System.currentTimeMillis()
        val startTime = now + 3600_000L
        val endTime = startTime + 2400_000L

        insertCalendarEvent(calendarId, "Easy Run", startTime, endTime)

        val result = calendarReader.getNextWorkout(calendarId, 4 * 3600_000L)
        val allResults = calendarReader.getUpcomingWorkouts(calendarId, 4 * 3600_000L)

        assertTrue("getNextWorkout should return a workout", result != null)
        assertTrue("getUpcomingWorkouts should return workouts", allResults.isNotEmpty())
        assertEquals(
            "getNextWorkout should return first from getUpcomingWorkouts",
            allResults.first(),
            result
        )
    }

    @Test
    fun `getNextWorkout returns null when no events in range`() = runTest {
        val calendarId = 1L
        val now = System.currentTimeMillis()
        // Event far in the future, outside lookahead
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

    @Test
    fun `guidanceState computes WorkoutApproaching for in-range BG with upcoming event`() = runTest {
        val calendarId = 1L
        val now = System.currentTimeMillis()
        val startTime = now + 3600_000L
        val endTime = startTime + 2400_000L

        insertCalendarEvent(calendarId, "Easy Run", startTime, endTime)

        val event = calendarReader.getNextWorkout(calendarId, 4 * 3600_000L)
        assertTrue("Precondition: event must exist", event != null)

        val reading = GlucoseReading(
            ts = now - 60_000L,
            sgv = 140,
            direction = "Flat",
            delta = null,
            pushed = 1
        )

        val guidance = MainViewModel.computeGuidance(
            event = event,
            latest = reading,
            allReadings = listOf(reading),
            iob = 0.0,
            targetLow = 126f,
            targetHigh = 162f,
            bgLow = 70.0,
            bgHigh = 180.0,
            nowMs = now
        )

        assertTrue(
            "Should be WorkoutApproaching, got $guidance",
            guidance is GuidanceState.WorkoutApproaching
        )
    }
}
