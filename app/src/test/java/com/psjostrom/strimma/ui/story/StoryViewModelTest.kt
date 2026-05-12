package com.psjostrom.strimma.ui.story

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.data.meal.MealAnalyzer
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StoryViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    // Seed data in the same zone the production VM uses so month-edge
    // assertions (canGoBack at the earliest reading's month) don't drift
    // when CI runs in UTC and dev runs in CET. Reading at Feb 1 00:00
    // Stockholm = Jan 31 23:00 UTC, which would map to January for the VM
    // running on a UTC runner — making "current is earliest" silently false.
    private val zone: ZoneId = ZoneId.systemDefault()

    private lateinit var db: StrimmaDatabase
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun readingAt(sgv: Int, year: Int, month: Int, day: Int, hour: Int): GlucoseReading {
        val dt = LocalDateTime.of(year, month, day, hour, 0)
        val ts = dt.atZone(zone).toInstant().toEpochMilli()
        return GlucoseReading(ts = ts, sgv = sgv, direction = "Flat", delta = null)
    }

    private fun fullDay(sgv: Int, year: Int, month: Int, day: Int): List<GlucoseReading> =
        (0 until 24).map { hour -> readingAt(sgv, year, month, day, hour) }

    private fun createViewModel(year: Int, month: Int): StoryViewModel {
        val handle = SavedStateHandle(mapOf("year" to year, "month" to month))
        return StoryViewModel(handle, db.readingDao(), db.treatmentDao(), settings, MealAnalyzer())
    }

    private suspend fun awaitLoaded(vm: StoryViewModel) {
        withTimeout(5000) { vm.loading.first { !it } }
    }

    @Test
    fun `loading completes after init`() = runBlocking {
        val vm = createViewModel(2026, 3)
        awaitLoaded(vm)
        assertFalse(vm.loading.value)
    }

    @Test
    fun `story is null when month has insufficient data`() = runBlocking {
        val readings = (1..3).flatMap { fullDay(120, 2026, 3, it) }
        db.readingDao().insertBatch(readings)

        val vm = createViewModel(2026, 3)
        awaitLoaded(vm)

        assertNull(vm.story.value)
        assertNull(vm.error.value)
    }

    @Test
    fun `story is populated when month has sufficient data`() = runBlocking {
        val readings = (1..10).flatMap { fullDay(120, 2026, 3, it) }
        db.readingDao().insertBatch(readings)

        val vm = createViewModel(2026, 3)
        awaitLoaded(vm)

        assertNotNull(vm.story.value)
        assertEquals(2026, vm.story.value!!.year)
        assertEquals(3, vm.story.value!!.month)
        assertEquals(10, vm.story.value!!.dayCount)
    }

    @Test
    fun `defaults to previous month when no SavedStateHandle args`() = runBlocking {
        val handle = SavedStateHandle()
        val vm = StoryViewModel(handle, db.readingDao(), db.treatmentDao(), settings, MealAnalyzer())
        awaitLoaded(vm)

        assertNull(vm.error.value)
    }

    // --- Month navigation ---

    @Test
    fun `canGoBack is false when current is earliest reading's month`() = runBlocking {
        // Earliest reading lives in February 2020 (always in the past, regardless of
        // when this test runs). Open Story at the same month — there's nothing older
        // to navigate to.
        db.readingDao().insertBatch(fullDay(120, 2020, 2, 1))

        val vm = createViewModel(2020, 2)
        awaitLoaded(vm)

        assertFalse(vm.canGoBack.value)
    }

    @Test
    fun `canGoBack is true when an earlier reading exists`() = runBlocking {
        db.readingDao().insertBatch(fullDay(120, 2020, 2, 1)) // earliest
        val vm = createViewModel(2020, 3) // open March; Feb is older

        awaitLoaded(vm)

        assertTrue(vm.canGoBack.value)
    }

    @Test
    fun `canGoForward is false at the last completed month`() = runBlocking {
        // Cap is YearMonth.now().minusMonths(1) — opening Story at that exact month
        // means there's no further-forward "completed" month to navigate to.
        val lastCompleted = YearMonth.now().minusMonths(1)
        val vm = createViewModel(lastCompleted.year, lastCompleted.monthValue)

        awaitLoaded(vm)

        assertFalse(vm.canGoForward.value)
    }

    @Test
    fun `goToPreviousMonth advances currentMonth and reloads story`() = runBlocking {
        // Two months with sufficient data: Feb 2020 and Mar 2020. Open March, then
        // navigate back. currentMonth should flip and the story payload should
        // reflect February.
        val feb = (1..10).flatMap { fullDay(120, 2020, 2, it) }
        val mar = (1..10).flatMap { fullDay(150, 2020, 3, it) }
        db.readingDao().insertBatch(feb + mar)

        val vm = createViewModel(2020, 3)
        awaitLoaded(vm)
        assertEquals(YearMonth.of(2020, 3), vm.currentMonth.value)

        vm.goToPreviousMonth()
        awaitLoaded(vm)

        assertEquals(YearMonth.of(2020, 2), vm.currentMonth.value)
        assertEquals(2, vm.story.value!!.month)
    }

    @Test
    fun `goToPreviousMonth is a no-op at the earliest boundary`() = runBlocking {
        db.readingDao().insertBatch(fullDay(120, 2020, 2, 1))
        val vm = createViewModel(2020, 2)
        awaitLoaded(vm)

        // Capture the story reference. If the call accidentally triggered a
        // reload, navigateTo() would set _story = null synchronously — the
        // ref check below would fail.
        val storyBefore = vm.story.value

        vm.goToPreviousMonth()

        assertEquals(YearMonth.of(2020, 2), vm.currentMonth.value)
        assertSame(
            "Boundary tap must not reload — _story should retain the same reference",
            storyBefore, vm.story.value
        )
    }

    @Test
    fun `goToNextMonth is a no-op at the last completed month`() = runBlocking {
        val lastCompleted = YearMonth.now().minusMonths(1)
        val vm = createViewModel(lastCompleted.year, lastCompleted.monthValue)
        awaitLoaded(vm)

        val storyBefore = vm.story.value

        vm.goToNextMonth()

        assertEquals(lastCompleted, vm.currentMonth.value)
        assertSame(
            "Boundary tap must not reload — _story should retain the same reference",
            storyBefore, vm.story.value
        )
    }

    @Test
    fun `canGoBack flips false after navigating from non-boundary to earliest`() = runBlocking {
        // Two months: Feb (earliest) and Mar. Open March (canGoBack=true), navigate
        // back to Feb (canGoBack should flip false).
        val feb = (1..10).flatMap { fullDay(120, 2020, 2, it) }
        val mar = (1..10).flatMap { fullDay(150, 2020, 3, it) }
        db.readingDao().insertBatch(feb + mar)

        val vm = createViewModel(2020, 3)
        awaitLoaded(vm)
        assertTrue("Pre-navigation: still above earliest", vm.canGoBack.value)

        vm.goToPreviousMonth()
        awaitLoaded(vm)

        assertFalse("After navigating to earliest, back-arrow disables", vm.canGoBack.value)
    }

    @Test
    fun `canGoForward is true when below the last completed month`() = runBlocking {
        // Open at a month known to be in the past, regardless of current real time.
        val vm = createViewModel(2020, 3)
        awaitLoaded(vm)

        assertTrue(vm.canGoForward.value)
    }

    @Test
    fun `marker is the maximum month seen across an arbitrary navigation trajectory`() = runBlocking {
        // Stronger than "back-nav doesn't downgrade once": pins monotonicity for
        // any sequence of forward and back navigations. A future regression that
        // weakens the compare to `>=` (or back to unconditional) would let the
        // final back-step to Feb silently overwrite May as the latest viewed.
        val readings = (2..5).flatMap { month ->
            (1..10).flatMap { day -> fullDay(120, 2020, month, day) }
        }
        db.readingDao().insertBatch(readings)

        val vm = createViewModel(2020, 3)
        awaitLoaded(vm)
        vm.goToNextMonth(); awaitLoaded(vm) // Mar -> Apr
        vm.goToNextMonth(); awaitLoaded(vm) // Apr -> May
        vm.goToPreviousMonth(); awaitLoaded(vm) // May -> Apr
        vm.goToPreviousMonth(); awaitLoaded(vm) // Apr -> Mar
        vm.goToPreviousMonth(); awaitLoaded(vm) // Mar -> Feb

        assertEquals(
            "Marker must equal the latest month opened (May), not the last (Feb)",
            "2020-05", settings.storyViewedMonth.first()
        )
    }

    @Test
    fun `marker advances when initial state is a non-empty earlier month`() = runBlocking {
        // Realistic startup case after first install + a previous session: the
        // marker is already set to last month, and the user opens a newer one.
        // Exercises the `newKey > currentKey` branch (vs. the empty-default
        // branch the other tests cover incidentally).
        val mar = (1..10).flatMap { fullDay(120, 2020, 3, it) }
        db.readingDao().insertBatch(mar)
        settings.setStoryViewedMonthIfLater("2020-02")
        assertEquals("2020-02", settings.storyViewedMonth.first()) // pre-condition

        val vm = createViewModel(2020, 3)
        awaitLoaded(vm)

        assertEquals("2020-03", settings.storyViewedMonth.first())
    }

    @Test
    fun `marker preserves a future value that was set out-of-band`() = runBlocking {
        // Pins the "never downgrade" half of the monotonic contract against an
        // already-future marker (e.g. a debug override or a forward sync). Opening
        // an older month must not move the marker backwards.
        val mar = (1..10).flatMap { fullDay(120, 2020, 3, it) }
        db.readingDao().insertBatch(mar)
        settings.setStoryViewedMonthIfLater("2099-12")

        val vm = createViewModel(2020, 3)
        awaitLoaded(vm)

        assertEquals("2099-12", settings.storyViewedMonth.first())
    }

    @Test
    fun `currentMonth is persisted to SavedStateHandle on navigation`() = runBlocking {
        val feb = (1..10).flatMap { fullDay(120, 2020, 2, it) }
        val mar = (1..10).flatMap { fullDay(150, 2020, 3, it) }
        db.readingDao().insertBatch(feb + mar)

        val handle = SavedStateHandle(mapOf("year" to 2020, "month" to 3))
        val vm = StoryViewModel(handle, db.readingDao(), db.treatmentDao(), settings, MealAnalyzer())
        awaitLoaded(vm)

        vm.goToPreviousMonth()
        awaitLoaded(vm)

        // Process death simulation: only the SavedStateHandle survives. After
        // restore, the new VM should land on the same month the user navigated to.
        assertEquals(2020, handle.get<Int>("year"))
        assertEquals(2, handle.get<Int>("month"))
    }

    @Test
    fun `error state is set when exception occurs`() = runBlocking {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val closedDb = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        closedDb.close()

        val handle = SavedStateHandle(mapOf("year" to 2026, "month" to 3))
        val vm = StoryViewModel(handle, closedDb.readingDao(), closedDb.treatmentDao(), settings, MealAnalyzer())
        awaitLoaded(vm)

        assertNotNull(vm.error.value)
    }
}
