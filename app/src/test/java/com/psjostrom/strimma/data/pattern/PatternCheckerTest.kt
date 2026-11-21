package com.psjostrom.strimma.data.pattern

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StatsReadings
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.data.workout.Clock
import com.psjostrom.strimma.testutil.workout.MutableClock
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@RunWith(RobolectricTestRunner::class)
class PatternCheckerTest {

    private data class Fixture(
        val db: StrimmaDatabase,
        val readingDao: ReadingDao,
        val exerciseDao: ExerciseDao,
        val settings: SettingsRepository,
        val checker: PatternChecker
    )

    private val zone = ZoneId.of("Europe/Stockholm")
    private val baseInstant = LocalDateTime.of(2026, 3, 15, 21, 0)
        .atZone(zone).toInstant()

    private fun createFixture(
        testClock: Clock = MutableClock(baseInstant.toEpochMilli()),
        dataStoreScope: CoroutineScope? = null
    ): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val readingDao = db.readingDao()
        val exerciseDao = db.exerciseDao()
        val dataStore = if (dataStoreScope != null) createTestDataStore(dataStoreScope) else createTestDataStore()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), dataStore)
        val checker = PatternChecker(
            readingDao, exerciseDao, settings, StatsReadings(readingDao, db.treatmentDao(), exerciseDao, settings), testClock
        )
        return Fixture(db, readingDao, exerciseDao, settings, checker)
    }

    private suspend fun insertHour(readingDao: ReadingDao, date: LocalDate, hour: Int, sgvs: List<Int>) {
        val step = 60 / sgvs.size
        sgvs.forEachIndexed { idx, sgv ->
            val dt = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, idx * step)
            val ts = dt.atZone(zone).toInstant().toEpochMilli()
            readingDao.insert(GlucoseReading(ts = ts, sgv = sgv, direction = "Flat", delta = null))
        }
    }

    @Test
    fun `checkNow returns null when pattern alerts disabled`() = runTest {
        val fix = createFixture()
        try {
            fix.settings.setPatternAlertsEnabled(false)

            val result = fix.checker.checkNow(now = baseInstant, zone = zone)
            assertNull(result)
            assertTrue(fix.checker.activePatterns.value.isEmpty())
        } finally {
            fix.db.close()
        }
    }

    @Test
    fun `checkNow returns null when less than 5 days of data`() = runTest {
        val fix = createFixture()
        try {
            val today = baseInstant.atZone(zone).toLocalDate()
            insertHour(fix.readingDao, today.minusDays(1), 12, listOf(120, 120, 120))
            insertHour(fix.readingDao, today, 12, listOf(120, 120, 120))

            val result = fix.checker.checkNow(now = baseInstant, zone = zone)
            assertNull(result)
        } finally {
            fix.db.close()
        }
    }

    @Test
    fun `checkNow detects pattern and updates activePatterns`() = runTest {
        val fix = createFixture()
        try {
            val today = baseInstant.atZone(zone).toLocalDate()

            for (d in 1..7) {
                val date = today.minusDays(d.toLong())
                val sgvs = if (d <= 5) listOf(220, 230, 240) else listOf(110, 115, 120)
                insertHour(fix.readingDao, date, 15, sgvs)
                insertHour(fix.readingDao, date, 8, listOf(100, 100, 100))
            }

            val result = fix.checker.checkNow(now = baseInstant, zone = zone)
            assertNotNull(result)
            assertEquals(1, result!!.patterns.size)
            assertEquals(1, fix.checker.activePatterns.value.size)
            assertEquals(15, fix.checker.activePatterns.value.first().startHour)
        } finally {
            fix.db.close()
        }
    }

    @Test
    fun `checkNow clears activePatterns when pattern resolves`() = runTest {
        val fix = createFixture()
        try {
            val today = baseInstant.atZone(zone).toLocalDate()

            for (d in 1..7) {
                val date = today.minusDays(d.toLong())
                val sgvs = if (d <= 5) listOf(220, 230, 240) else listOf(110, 115, 120)
                insertHour(fix.readingDao, date, 15, sgvs)
                insertHour(fix.readingDao, date, 8, listOf(100, 100, 100))
            }

            val initial = fix.checker.checkNow(now = baseInstant, zone = zone)
            assertNotNull(initial)
            assertEquals(1, fix.checker.activePatterns.value.size)

            val nextWeek = baseInstant.plus(7L * 24 * 3600, ChronoUnit.SECONDS)
            val nextWeekDate = nextWeek.atZone(zone).toLocalDate()
            for (d in 1..7) {
                val date = nextWeekDate.minusDays(d.toLong())
                insertHour(fix.readingDao, date, 15, listOf(110, 120, 115))
                insertHour(fix.readingDao, date, 8, listOf(100, 100, 100))
            }

            val resolved = fix.checker.checkNow(now = nextWeek, zone = zone)
            assertNotNull(resolved)
            assertTrue(resolved!!.patterns.isEmpty())
            assertTrue(fix.checker.activePatterns.value.isEmpty())
        } finally {
            fix.db.close()
        }
    }

    @Test
    fun `start observes patternAlertsEnabled and clears or rechecks activePatterns`() = runTest {
        val fix = createFixture(dataStoreScope = backgroundScope)
        try {
            val today = baseInstant.atZone(zone).toLocalDate()
            for (d in 1..7) {
                val date = today.minusDays(d.toLong())
                val sgvs = if (d <= 5) listOf(220, 230, 240) else listOf(110, 115, 120)
                insertHour(fix.readingDao, date, 15, sgvs)
                insertHour(fix.readingDao, date, 8, listOf(100, 100, 100))
            }

            val initial = fix.checker.checkNow(now = baseInstant, zone = zone)
            assertNotNull(initial)
            assertEquals(1, fix.checker.activePatterns.value.size)

            fix.checker.start(backgroundScope, zone)

            fix.settings.setPatternAlertsEnabled(false)
            val cleared = fix.checker.activePatterns.first { it.isEmpty() }
            assertTrue(cleared.isEmpty())

            fix.settings.setPatternAlertsEnabled(true)
            val restored = fix.checker.activePatterns.first { it.isNotEmpty() }
            assertEquals(1, restored.size)
        } finally {
            fix.checker.stop()
            fix.db.close()
        }
    }

    @Test
    fun `nightly compression lows count as a low pattern only when detection is off`() = runTest {
        val fix = createFixture(dataStoreScope = backgroundScope)
        // The detector judges night in the system zone, so the readings use the same zone.
        val localZone = ZoneId.systemDefault()
        try {
            val today = baseInstant.atZone(localZone).toLocalDate()
            for (d in 1..7) {
                val start = today.minusDays(d.toLong()).atTime(3, 0).atZone(localZone).toInstant().toEpochMilli()
                // 03:00-05:55 every 5 minutes. On five nights the sensor drops to 55 for 04:00-04:25.
                val readings = (0 until 36).map { i ->
                    val sgv = if (d <= 5 && i in 12..17) 55 else 140
                    GlucoseReading(ts = start + i * 5 * 60_000L, sgv = sgv, direction = "Flat", delta = null)
                }
                fix.readingDao.insertBatch(readings)
            }

            fix.checker.start(backgroundScope, localZone)
            assertTrue(fix.checker.checkNow(now = baseInstant, zone = localZone)!!.patterns.isEmpty())

            fix.settings.setCompressionLowDetection(false)
            val patterns = fix.checker.activePatterns.first { it.isNotEmpty() }
            assertEquals(PatternType.LOW, patterns.single().type)
            assertEquals(4, patterns.single().startHour)
        } finally {
            fix.checker.stop()
            fix.db.close()
        }
    }

    @Test
    fun `reset clears active patterns`() = runTest {
        val fix = createFixture()
        try {
            val today = baseInstant.atZone(zone).toLocalDate()
            for (d in 1..7) {
                val date = today.minusDays(d.toLong())
                val sgvs = if (d <= 5) listOf(220, 230, 240) else listOf(110, 115, 120)
                insertHour(fix.readingDao, date, 15, sgvs)
                insertHour(fix.readingDao, date, 8, listOf(100, 100, 100))
            }

            fix.checker.checkNow(now = baseInstant, zone = zone)
            assertEquals(1, fix.checker.activePatterns.value.size)

            fix.checker.reset()
            assertTrue(fix.checker.activePatterns.value.isEmpty())
        } finally {
            fix.db.close()
        }
    }
}
