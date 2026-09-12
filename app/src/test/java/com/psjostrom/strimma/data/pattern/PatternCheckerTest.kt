package com.psjostrom.strimma.data.pattern

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.widget.WidgetSettingsRepository
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

    private fun createFixture(): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val readingDao = db.readingDao()
        val exerciseDao = db.exerciseDao()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        val checker = PatternChecker(readingDao, exerciseDao, settings)
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
                insertHour(fix.readingDao, date, 15, listOf(110, 120, 115))
                insertHour(fix.readingDao, date, 8, listOf(100, 100, 100))
            }

            val result = fix.checker.checkNow(now = baseInstant, zone = zone)
            assertNotNull(result)
            assertTrue(result!!.patterns.isEmpty())
            assertTrue(fix.checker.activePatterns.value.isEmpty())
        } finally {
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
