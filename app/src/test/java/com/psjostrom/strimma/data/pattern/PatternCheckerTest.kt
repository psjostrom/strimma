package com.psjostrom.strimma.data.pattern

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.MS_PER_DAY
import com.psjostrom.strimma.data.MS_PER_HOUR
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.notification.PatternNotifier
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class PatternCheckerTest {

    private lateinit var context: Context
    private lateinit var db: StrimmaDatabase
    private lateinit var readingDao: ReadingDao
    private lateinit var exerciseDao: ExerciseDao
    private lateinit var settings: SettingsRepository
    private lateinit var notifier: PatternNotifier
    private lateinit var checker: PatternChecker
    private lateinit var notifManager: NotificationManager

    private val zone = ZoneId.of("Europe/Stockholm")
    private val baseInstant = LocalDateTime.of(2026, 3, 15, 21, 0)
        .atZone(zone).toInstant()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        readingDao = db.readingDao()
        exerciseDao = db.exerciseDao()

        settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        notifier = PatternNotifier(context)
        checker = PatternChecker(readingDao, exerciseDao, settings, notifier)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertHour(date: LocalDate, hour: Int, sgvs: List<Int>) {
        val step = 60 / sgvs.size
        sgvs.forEachIndexed { idx, sgv ->
            val dt = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, idx * step)
            val ts = dt.atZone(zone).toInstant().toEpochMilli()
            readingDao.insert(GlucoseReading(ts = ts, sgv = sgv, direction = "Flat", delta = null))
        }
    }

    @Test
    fun `checkNow returns null when pattern alerts disabled`() = runTest {
        settings.setPatternAlertsEnabled(false)

        val result = checker.checkNow(now = baseInstant, zone = zone)
        assertNull(result)
        assertTrue(checker.activePatterns.value.isEmpty())
    }

    @Test
    fun `checkNow returns null when less than 5 days of data`() = runTest {
        // Only 2 days of readings
        val today = baseInstant.atZone(zone).toLocalDate()
        insertHour(today.minusDays(1), 12, listOf(120, 120, 120))
        insertHour(today, 12, listOf(120, 120, 120))

        val result = checker.checkNow(now = baseInstant, zone = zone)
        assertNull(result)
    }

    @Test
    fun `checkNow detects pattern, posts notification and records hash`() = runTest {
        val today = baseInstant.atZone(zone).toLocalDate()

        // Seed 7 days with day 7 having high readings at 15:00
        for (d in 1..7) {
            val date = today.minusDays(d.toLong())
            val sgvs = if (d <= 5) listOf(220, 230, 240) else listOf(110, 115, 120)
            insertHour(date, 15, sgvs)
            // also add morning reading to establish historical range
            insertHour(date, 8, listOf(100, 100, 100))
        }

        val result = checker.checkNow(now = baseInstant, zone = zone)
        assertNotNull(result)
        assertEquals(1, result!!.patterns.size)
        assertEquals(1, checker.activePatterns.value.size)

        val hash = settings.patternLastHash.first()
        assertTrue(hash.isNotEmpty())
        assertTrue(hash.contains("HIGH:15-16:5/7"))

        val lastNotified = settings.patternLastNotifiedTs.first()
        assertEquals(baseInstant.toEpochMilli(), lastNotified)

        // Notification was posted with ID 200
        val activeNotifs = notifManager.activeNotifications
        assertTrue(activeNotifs.any { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN })
    }

    @Test
    fun `checkNow suppresses notification if unchanged within 72h cooldown`() = runTest {
        val today = baseInstant.atZone(zone).toLocalDate()
        for (d in 0..7) {
            val date = today.minusDays(d.toLong())
            val sgvs = if (d in 1..5) listOf(220, 230, 240) else listOf(110, 115, 120)
            insertHour(date, 15, sgvs)
            insertHour(date, 8, listOf(100, 100, 100))
        }

        // First run -> notifies
        checker.checkNow(now = baseInstant, zone = zone)
        val firstNotifiedTs = settings.patternLastNotifiedTs.first()

        // Clear notification to verify it is NOT re-posted on second run
        notifier.cancel()

        // Run 24 hours later (within 72h) with identical pattern
        val nextDayInstant = baseInstant.plusMillis(24 * MS_PER_HOUR)
        checker.checkNow(now = nextDayInstant, zone = zone)

        // Timestamp did not advance because notification was not re-sent
        assertEquals(firstNotifiedTs, settings.patternLastNotifiedTs.first())
        val activeNotifs = notifManager.activeNotifications
        assertTrue(activeNotifs.none { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN })
    }

    @Test
    fun `checkNow clears hash and cancels notification when pattern resolves`() = runTest {
        val today = baseInstant.atZone(zone).toLocalDate()
        // Simulate pre-existing notified state
        settings.setPatternLastHash("HIGH:15-16:5/7")
        settings.setPatternLastNotifiedTs(baseInstant.toEpochMilli())

        // Seed 7 days with all in-range readings
        for (d in 1..7) {
            val date = today.minusDays(d.toLong())
            insertHour(date, 15, listOf(110, 120, 115))
            insertHour(date, 8, listOf(100, 100, 100))
        }

        val result = checker.checkNow(now = baseInstant, zone = zone)
        assertNotNull(result)
        assertTrue(result!!.patterns.isEmpty())
        assertTrue(checker.activePatterns.value.isEmpty())

        // Hash cleared
        assertEquals("", settings.patternLastHash.first())
    }

    @Test
    fun `millisUntilNextTargetHour computes correct remaining milliseconds`() {
        val testNow = LocalDateTime.of(2026, 3, 15, 18, 0).atZone(zone).toInstant()
        val millis = PatternChecker.millisUntilNextTargetHour(
            targetHour = 21,
            zone = zone,
            now = testNow
        )
        // 18:00 to 21:00 is exactly 3 hours
        assertEquals(3 * 3600_000L, millis)

        val testPast = LocalDateTime.of(2026, 3, 15, 22, 0).atZone(zone).toInstant()
        val millisPast = PatternChecker.millisUntilNextTargetHour(
            targetHour = 21,
            zone = zone,
            now = testPast
        )
        // 22:00 to next day 21:00 is 23 hours
        assertEquals(23 * 3600_000L, millisPast)
    }
}
