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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
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
        val notifier: PatternNotifier,
        val checker: PatternChecker,
        val notifManager: NotificationManager
    )

    private val zone = ZoneId.of("Europe/Stockholm")
    private val baseInstant = LocalDateTime.of(2026, 3, 15, 21, 0)
        .atZone(zone).toInstant()

    private fun createFixture(): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val readingDao = db.readingDao()
        val exerciseDao = db.exerciseDao()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        val notifier = PatternNotifier(context)
        val checker = PatternChecker(readingDao, exerciseDao, settings, notifier)
        return Fixture(db, readingDao, exerciseDao, settings, notifier, checker, notifManager)
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
            fix.settings.setPatternLastHash("HIGH:15-16")
            fix.settings.setPatternLastNotifiedTs(baseInstant.toEpochMilli())
            val builder = android.app.Notification.Builder(ApplicationProvider.getApplicationContext(), AlertManager.CHANNEL_PATTERN)
                .setContentTitle("Test Pattern")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
            fix.notifManager.notify(PatternNotifier.NOTIFICATION_ID_PATTERN, builder.build())
            assertTrue(fix.notifManager.activeNotifications.any { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN })

            fix.settings.setPatternAlertsEnabled(false)

            val result = fix.checker.checkNow(now = baseInstant, zone = zone)
            assertNull(result)
            assertTrue(fix.checker.activePatterns.value.isEmpty())
            assertEquals("", fix.settings.patternLastHash.first())
            assertTrue(fix.notifManager.activeNotifications.none { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN })
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
    fun `checkNow detects pattern, posts notification and records hash`() = runTest {
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

            val hash = fix.settings.patternLastHash.first()
            assertTrue(hash.isNotEmpty())
            assertTrue(hash.contains("HIGH:15-16"))

            val lastNotified = fix.settings.patternLastNotifiedTs.first()
            assertEquals(baseInstant.toEpochMilli(), lastNotified)

            val activeNotifs = fix.notifManager.activeNotifications
            assertTrue(activeNotifs.any { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN })
        } finally {
            fix.db.close()
        }
    }

    @Test
    fun `checkNow suppresses notification if unchanged within 72h cooldown`() = runTest {
        val fix = createFixture()
        try {
            val today = baseInstant.atZone(zone).toLocalDate()
            for (d in 0..7) {
                val date = today.minusDays(d.toLong())
                val sgvs = if (d in 1..5) listOf(220, 230, 240) else listOf(110, 115, 120)
                insertHour(fix.readingDao, date, 15, sgvs)
                insertHour(fix.readingDao, date, 8, listOf(100, 100, 100))
            }

            fix.checker.checkNow(now = baseInstant, zone = zone)
            val firstNotifiedTs = fix.settings.patternLastNotifiedTs.first()

            fix.notifier.cancel()

            val nextDayInstant = baseInstant.plusMillis(24 * MS_PER_HOUR)
            fix.checker.checkNow(now = nextDayInstant, zone = zone)

            assertEquals(firstNotifiedTs, fix.settings.patternLastNotifiedTs.first())
            val activeNotifs = fix.notifManager.activeNotifications
            assertTrue(activeNotifs.none { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN })
        } finally {
            fix.db.close()
        }
    }

    @Test
    fun `checkNow clears hash and cancels notification when pattern resolves`() = runTest {
        val fix = createFixture()
        try {
            val today = baseInstant.atZone(zone).toLocalDate()
            fix.settings.setPatternLastHash("HIGH:15-16")
            fix.settings.setPatternLastNotifiedTs(baseInstant.toEpochMilli())
            val builder = android.app.Notification.Builder(ApplicationProvider.getApplicationContext(), AlertManager.CHANNEL_PATTERN)
                .setContentTitle("Test Pattern")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
            fix.notifManager.notify(PatternNotifier.NOTIFICATION_ID_PATTERN, builder.build())
            assertTrue(fix.notifManager.activeNotifications.any { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN })

            for (d in 1..7) {
                val date = today.minusDays(d.toLong())
                insertHour(fix.readingDao, date, 15, listOf(110, 120, 115))
                insertHour(fix.readingDao, date, 8, listOf(100, 100, 100))
            }

            val result = fix.checker.checkNow(now = baseInstant, zone = zone)
            assertNotNull(result)
            assertTrue(result!!.patterns.isEmpty())
            assertTrue(fix.checker.activePatterns.value.isEmpty())

            assertEquals("", fix.settings.patternLastHash.first())
            assertTrue(fix.notifManager.activeNotifications.none { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN })
        } finally {
            fix.db.close()
        }
    }

    @Test
    fun `reset clears active patterns, cancels notification and clears hash`() = runTest {
        val fix = createFixture()
        try {
            fix.settings.setPatternLastHash("HIGH:15-16")
            val builder = android.app.Notification.Builder(ApplicationProvider.getApplicationContext(), AlertManager.CHANNEL_PATTERN)
                .setContentTitle("Test Pattern")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
            fix.notifManager.notify(PatternNotifier.NOTIFICATION_ID_PATTERN, builder.build())
            assertTrue(fix.notifManager.activeNotifications.any { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN })

            fix.checker.reset()

            assertTrue(fix.checker.activePatterns.value.isEmpty())
            assertEquals("", fix.settings.patternLastHash.first())
            assertTrue(fix.notifManager.activeNotifications.none { it.id == PatternNotifier.NOTIFICATION_ID_PATTERN })
        } finally {
            fix.db.close()
        }
    }

    @Test
    fun `millisUntilNextTargetHour computes correct remaining milliseconds`() {
        val testNow = LocalDateTime.of(2026, 3, 15, 18, 0).atZone(zone).toInstant()
        val millis = PatternChecker.millisUntilNextTargetHour(
            targetHour = 21,
            zone = zone,
            now = testNow
        )
        assertEquals(3 * 3600_000L, millis)

        val testPast = LocalDateTime.of(2026, 3, 15, 22, 0).atZone(zone).toInstant()
        val millisPast = PatternChecker.millisUntilNextTargetHour(
            targetHour = 21,
            zone = zone,
            now = testPast
        )
        assertEquals(23 * 3600_000L, millisPast)
    }
}
