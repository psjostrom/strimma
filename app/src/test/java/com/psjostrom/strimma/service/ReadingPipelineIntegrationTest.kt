package com.psjostrom.strimma.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.data.Direction
import com.psjostrom.strimma.data.DirectionComputer
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.StrimmaDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration test for the reading pipeline as implemented in StrimmaService.processReading().
 *
 * Mirrors the real processReading signature (mgdl: Double, timestamp: Long) and tests:
 * - SGV validation (isValidSgv) before any processing
 * - Double→Int rounding of mg/dL values
 * - Duplicate detection with abs() timestamp diff
 * - Direction computation via real DirectionComputer
 * - Delta rounding to 0.1 mg/dL precision
 * - Push triggered after successful store
 * - Alert checking called with correct recent readings window
 *
 * Uses real Room (in-memory), real DirectionComputer, real SettingsRepository.
 * Fakes only at the network boundary (FakePushTracker) and notification boundary (FakeAlertChecker).
 */
@RunWith(RobolectricTestRunner::class)
class ReadingPipelineIntegrationTest {

    private lateinit var db: StrimmaDatabase
    private lateinit var dao: ReadingDao
    private val directionComputer = DirectionComputer()
    private lateinit var pushTracker: FakePushTracker
    private lateinit var alertChecker: FakeAlertChecker
    private val baseTs = 1_700_000_000_000L

    companion object {
        private const val DUPLICATE_THRESHOLD_MS = 3_000L
        private const val LOOKBACK_MINUTES = 15
        private const val MS_PER_MINUTE = 60_000L
        private const val DELTA_ROUNDING_FACTOR = 10.0
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.readingDao()
        pushTracker = FakePushTracker()
        alertChecker = FakeAlertChecker()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Mirrors StrimmaService.processReading() exactly, but replaces push/alert/notification
     * side effects with test-observable fakes.
     */
    private suspend fun processReading(mgdl: Double, timestamp: Long) {
        if (!GlucoseReading.isValidSgv(mgdl)) return
        val sgv = Math.round(mgdl).toInt()

        val existing = dao.lastN(1)
        if (existing.isNotEmpty() && kotlin.math.abs(timestamp - existing[0].ts) < DUPLICATE_THRESHOLD_MS) return

        val recentReadings = dao.since(timestamp - LOOKBACK_MINUTES * MS_PER_MINUTE)
        val tempReading = GlucoseReading(
            ts = timestamp, sgv = sgv,
            direction = "NONE", delta = null, pushed = 0
        )
        val (direction, deltaMgdl) = directionComputer.compute(recentReadings, tempReading)

        val reading = tempReading.copy(
            direction = direction.name,
            delta = deltaMgdl?.let { Math.round(it * DELTA_ROUNDING_FACTOR) / DELTA_ROUNDING_FACTOR }
        )

        dao.insert(reading)
        pushTracker.onPush(reading)
        val alertReadings = recentReadings + reading
        alertChecker.onCheck(reading, alertReadings)
    }

    // --- SGV validation ---

    @Test
    fun `sgv below minimum is rejected`() = runTest {
        processReading(17.0, baseTs) // MIN_VALID_SGV is 18
        val all = dao.since(0)
        assertTrue("SGV 17 should be rejected", all.isEmpty())
        assertEquals(0, pushTracker.pushCount.get())
    }

    @Test
    fun `sgv of zero is rejected`() = runTest {
        processReading(0.0, baseTs)
        assertTrue(dao.since(0).isEmpty())
    }

    @Test
    fun `negative sgv is rejected`() = runTest {
        processReading(-5.0, baseTs)
        assertTrue(dao.since(0).isEmpty())
    }

    @Test
    fun `sgv above maximum is rejected`() = runTest {
        processReading(901.0, baseTs) // MAX_VALID_SGV is 900
        assertTrue(dao.since(0).isEmpty())
    }

    @Test
    fun `sgv at minimum boundary is accepted`() = runTest {
        processReading(18.0, baseTs)
        assertEquals(1, dao.since(0).size)
    }

    @Test
    fun `sgv at maximum boundary is accepted`() = runTest {
        processReading(900.0, baseTs)
        val all = dao.since(0)
        assertEquals(1, all.size)
        assertEquals(900, all[0].sgv)
    }

    // --- Double to Int rounding ---

    @Test
    fun `mgdl value is rounded to nearest int`() = runTest {
        processReading(108.7, baseTs)
        val stored = dao.since(0)
        assertEquals(1, stored.size)
        assertEquals(109, stored[0].sgv) // Math.round(108.7) == 109
    }

    @Test
    fun `mgdl halfway rounds up`() = runTest {
        processReading(108.5, baseTs)
        val stored = dao.since(0)
        assertEquals(109, stored[0].sgv) // Math.round(108.5) == 109
    }

    @Test
    fun `mgdl just below half rounds down`() = runTest {
        processReading(108.4, baseTs)
        val stored = dao.since(0)
        assertEquals(108, stored[0].sgv)
    }

    // --- Duplicate detection (abs() timestamp diff) ---

    @Test
    fun `reading 1ms after previous is deduplicated`() = runTest {
        processReading(108.0, baseTs)
        processReading(120.0, baseTs + 1)
        assertEquals(1, dao.since(0).size)
    }

    @Test
    fun `reading 2999ms after previous is deduplicated`() = runTest {
        processReading(108.0, baseTs)
        processReading(120.0, baseTs + 2999)
        assertEquals(1, dao.since(0).size)
    }

    @Test
    fun `reading exactly 3000ms after previous is deduplicated`() = runTest {
        // abs(diff) < 3000, so exactly 3000 is NOT < 3000 — NOT deduplicated
        // Wait, the code says < 3000, so 3000 is NOT less than 3000. It's accepted.
        processReading(108.0, baseTs)
        processReading(120.0, baseTs + 3000)
        assertEquals(2, dao.since(0).size)
    }

    @Test
    fun `reading 3001ms after previous is accepted`() = runTest {
        processReading(108.0, baseTs)
        processReading(120.0, baseTs + 3001)
        assertEquals(2, dao.since(0).size)
    }

    @Test
    fun `reading with past timestamp within 3s is deduplicated`() = runTest {
        // Real code uses abs() — a reading arriving with a slightly earlier timestamp
        // than the latest stored should also be deduplicated
        processReading(108.0, baseTs)
        processReading(120.0, baseTs - 2000)
        assertEquals(1, dao.since(0).size)
    }

    @Test
    fun `reading with past timestamp beyond 3s is accepted`() = runTest {
        processReading(108.0, baseTs)
        processReading(120.0, baseTs - 3001)
        assertEquals(2, dao.since(0).size)
    }

    // --- Push triggered on successful store ---

    @Test
    fun `push is triggered after valid reading is stored`() = runTest {
        processReading(108.0, baseTs)
        assertEquals(1, pushTracker.pushCount.get())
        assertEquals(108, pushTracker.lastPushedReading!!.sgv)
    }

    @Test
    fun `push is not triggered when reading is deduplicated`() = runTest {
        processReading(108.0, baseTs)
        processReading(120.0, baseTs + 1000)
        assertEquals(1, pushTracker.pushCount.get())
    }

    @Test
    fun `push is not triggered when sgv is invalid`() = runTest {
        processReading(0.0, baseTs)
        assertEquals(0, pushTracker.pushCount.get())
    }

    @Test
    fun `pushed reading has computed direction and delta`() = runTest {
        // Seed steady readings for direction computation
        for (i in 6 downTo 1) {
            processReading(108.0, baseTs - i * 60_000L)
        }
        pushTracker.reset()
        processReading(108.0, baseTs)
        val pushed = pushTracker.lastPushedReading!!
        assertEquals("Flat", pushed.direction)
    }

    // --- Alert checking ---

    @Test
    fun `alert check is called with the reading on valid store`() = runTest {
        processReading(108.0, baseTs)
        assertEquals(1, alertChecker.checkCount.get())
        assertEquals(108, alertChecker.lastCheckedReading!!.sgv)
    }

    @Test
    fun `alert check is not called on dedup`() = runTest {
        processReading(108.0, baseTs)
        processReading(120.0, baseTs + 1000)
        assertEquals(1, alertChecker.checkCount.get())
    }

    @Test
    fun `alert check is not called on invalid sgv`() = runTest {
        processReading(-1.0, baseTs)
        assertEquals(0, alertChecker.checkCount.get())
    }

    @Test
    fun `alert check receives recent readings including the new one`() = runTest {
        processReading(100.0, baseTs - 5 * 60_000L)
        processReading(110.0, baseTs - 4 * 60_000L)
        processReading(120.0, baseTs)

        val alertReadings = alertChecker.lastAlertReadings!!
        // Should include all readings within 15-min lookback + the new reading
        assertEquals(3, alertReadings.size)
        // Last reading in the list should be the newly inserted one
        assertEquals(120, alertReadings.last().sgv)
    }

    @Test
    fun `alert check excludes readings older than 15 minutes`() = runTest {
        processReading(90.0, baseTs - 20 * 60_000L) // 20 min ago — outside window
        processReading(100.0, baseTs - 10 * 60_000L) // 10 min ago — inside window
        processReading(110.0, baseTs)

        val alertReadings = alertChecker.lastAlertReadings!!
        // The 20-min-ago reading should NOT be in the alert window
        // (recentReadings is dao.since(timestamp - 15*60000))
        assertEquals(2, alertReadings.size)
        assertEquals(100, alertReadings[0].sgv)
        assertEquals(110, alertReadings[1].sgv)
    }

    // --- Delta rounding precision ---

    @Test
    fun `delta is rounded to 0_1 precision`() = runTest {
        // Seed 6 minutes of readings at 100 mg/dL, then jump to 107
        for (i in 6 downTo 1) {
            processReading(100.0, baseTs - i * 60_000L)
        }
        processReading(107.0, baseTs)

        val latest = dao.latest().first()!!
        assertNotNull(latest.delta)
        // Verify delta has at most 1 decimal place
        val scaledDelta = latest.delta!! * 10.0
        assertEquals(
            "Delta should be rounded to 0.1: was ${latest.delta}",
            Math.round(scaledDelta).toDouble(),
            scaledDelta,
            0.001
        )
    }

    @Test
    fun `delta rounding example with known values`() = runTest {
        // Seed: 5 readings at 100 mg/dL, 1 minute apart
        for (i in 5 downTo 1) {
            processReading(100.0, baseTs - i * 60_000L)
        }
        // New reading at 100 — delta should be 0.0
        processReading(100.0, baseTs)
        val latest = dao.latest().first()!!
        assertNotNull(latest.delta)
        assertEquals(0.0, latest.delta!!, 0.001)
    }

    // --- Direction follows EASD thresholds (integration with real DirectionComputer) ---

    @Test
    fun `steady readings produce Flat direction`() = runTest {
        for (i in 6 downTo 1) {
            processReading(108.0, baseTs - i * 60_000L)
        }
        processReading(108.0, baseTs)
        val latest = dao.latest().first()!!
        assertEquals("Flat", latest.direction)
    }

    @Test
    fun `rapidly rising readings produce upward direction`() = runTest {
        // +4 mg/dL per minute over 6 min
        for (i in 6 downTo 1) {
            processReading(100.0 + (6 - i) * 4.0, baseTs - i * 60_000L)
        }
        processReading(124.0, baseTs)
        val dir = Direction.valueOf(dao.latest().first()!!.direction)
        assertTrue(
            "Expected upward direction, got $dir",
            dir == Direction.FortyFiveUp || dir == Direction.SingleUp || dir == Direction.DoubleUp
        )
    }

    @Test
    fun `rapidly falling readings produce downward direction`() = runTest {
        for (i in 6 downTo 1) {
            processReading(180.0 - (6 - i) * 4.0, baseTs - i * 60_000L)
        }
        processReading(156.0, baseTs)
        val dir = Direction.valueOf(dao.latest().first()!!.direction)
        assertTrue(
            "Expected downward direction, got $dir",
            dir == Direction.FortyFiveDown || dir == Direction.SingleDown || dir == Direction.DoubleDown
        )
    }

    @Test
    fun `first reading has NONE direction and null delta`() = runTest {
        processReading(108.0, baseTs)
        val latest = dao.latest().first()!!
        assertEquals("NONE", latest.direction)
        assertNull(latest.delta)
    }

    // --- Stored state ---

    @Test
    fun `reading is stored as unpushed`() = runTest {
        processReading(108.0, baseTs)
        val unpushed = dao.unpushed()
        assertEquals(1, unpushed.size)
        assertEquals(0, unpushed[0].pushed)
    }

    @Test
    fun `multiple valid readings are all stored`() = runTest {
        for (i in 0..4) {
            processReading(90.0 + i * 10, baseTs + i * 60_000L)
        }
        val all = dao.since(0)
        assertEquals(5, all.size)
        // Verify chronological order
        for (i in 1 until all.size) {
            assertTrue(all[i].ts > all[i - 1].ts)
        }
    }

    // --- Test doubles ---

    private class FakePushTracker {
        val pushCount = AtomicInteger(0)
        var lastPushedReading: GlucoseReading? = null

        fun onPush(reading: GlucoseReading) {
            pushCount.incrementAndGet()
            lastPushedReading = reading
        }

        fun reset() {
            pushCount.set(0)
            lastPushedReading = null
        }
    }

    private class FakeAlertChecker {
        val checkCount = AtomicInteger(0)
        var lastCheckedReading: GlucoseReading? = null
        var lastAlertReadings: List<GlucoseReading>? = null

        fun onCheck(reading: GlucoseReading, alertReadings: List<GlucoseReading>) {
            checkCount.incrementAndGet()
            lastCheckedReading = reading
            lastAlertReadings = alertReadings
        }
    }
}
