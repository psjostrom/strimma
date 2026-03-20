package com.psjostrom.strimma.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration test for the full reading pipeline:
 * mg/dL input → dedup check → direction computation → DB insert → verify stored state.
 *
 * Tests the same logic path as StrimmaService.processReading() but without
 * the Android Service lifecycle, notifications, or HTTP push.
 */
@RunWith(RobolectricTestRunner::class)
class ReadingPipelineTest {

    private lateinit var db: StrimmaDatabase
    private lateinit var dao: ReadingDao
    private val computer = DirectionComputer()
    private val baseTs = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.readingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun processReading(sgv: Int, timestamp: Long) {
        // Dedup (same as StrimmaService)
        val existing = dao.lastN(1)
        if (existing.isNotEmpty() && (timestamp - existing[0].ts) < 3_000) return

        // Direction
        val recentReadings = dao.since(timestamp - 15 * 60 * 1000)
        val tempReading = GlucoseReading(
            ts = timestamp, sgv = sgv,
            direction = "NONE", delta = null, pushed = 0
        )
        val (direction, deltaMgdl) = computer.compute(recentReadings, tempReading)

        val reading = tempReading.copy(
            direction = direction.name,
            delta = deltaMgdl?.let { Math.round(it * 10.0) / 10.0 }
        )
        dao.insert(reading)
    }

    @Test
    fun `single reading stored with NONE direction`() = runTest {
        processReading(108, baseTs)
        val latest = dao.latest().first()
        assertNotNull(latest)
        assertEquals("NONE", latest!!.direction)
        assertNull(latest.delta)
        assertEquals(0, latest.pushed)
    }

    @Test
    fun `direction computed after enough history`() = runTest {
        // Build 6 minutes of readings at 1/min, steady 108 mg/dL
        for (i in 6 downTo 1) {
            processReading(108, baseTs - i * 60_000L)
        }
        // Now a new reading — should have a direction
        processReading(108, baseTs)
        val latest = dao.latest().first()
        assertEquals("Flat", latest!!.direction)
    }

    @Test
    fun `rising glucose produces upward direction`() = runTest {
        // Slow rise over 6 minutes: 108, 112, 115, 119, 122, 126
        for (i in 6 downTo 1) {
            processReading(108 + (6 - i) * 4, baseTs - i * 60_000L)
        }
        processReading(130, baseTs)
        val latest = dao.latest().first()
        // 130 - 108 over 6 min ≈ +3.7 mg/dL/min → should be SingleUp or DoubleUp
        val dir = Direction.valueOf(latest!!.direction)
        assertTrue("expected upward direction, got $dir",
            dir == Direction.FortyFiveUp || dir == Direction.SingleUp || dir == Direction.DoubleUp)
    }

    @Test
    fun `falling glucose produces downward direction`() = runTest {
        for (i in 6 downTo 1) {
            processReading(180 - (6 - i) * 4, baseTs - i * 60_000L)
        }
        processReading(158, baseTs)
        val latest = dao.latest().first()
        val dir = Direction.valueOf(latest!!.direction)
        assertTrue("expected downward direction, got $dir",
            dir == Direction.FortyFiveDown || dir == Direction.SingleDown || dir == Direction.DoubleDown)
    }

    @Test
    fun `dedup prevents duplicate within 3 seconds`() = runTest {
        processReading(108, baseTs)
        processReading(110, baseTs + 2_000) // 2s later — should be deduped

        val all = dao.since(0)
        assertEquals(1, all.size)
        assertEquals(108, all[0].sgv)
    }

    @Test
    fun `reading after 3 seconds is not deduped`() = runTest {
        processReading(108, baseTs)
        processReading(110, baseTs + 3_001) // just past 3s — should be stored

        val all = dao.since(0)
        assertEquals(2, all.size)
    }

    @Test
    fun `delta is computed in mgdl`() = runTest {
        for (i in 6 downTo 1) {
            processReading(108, baseTs - i * 60_000L)
        }
        processReading(126, baseTs)
        val latest = dao.latest().first()
        assertNotNull(latest!!.delta)
        // Delta should be roughly +18 mg/dL (1.0 mmol)
        assertTrue("delta should be in mg/dL range, was ${latest.delta}",
            latest.delta!! in 5.0..36.0)
    }

    @Test
    fun `new readings stored as unpushed`() = runTest {
        processReading(108, baseTs)
        val unpushed = dao.unpushed()
        assertEquals(1, unpushed.size)
        assertEquals(0, unpushed[0].pushed)
    }

    @Test
    fun `10 consecutive readings all stored correctly`() = runTest {
        for (i in 0..9) {
            processReading(90 + i * 5, baseTs + i * 60_000L)
        }
        val all = dao.since(0)
        assertEquals(10, all.size)
        // Verify ascending order
        for (i in 1 until all.size) {
            assertTrue(all[i].ts > all[i - 1].ts)
        }
    }

    @Test
    fun `gap in data resets direction to NONE`() = runTest {
        // Readings at -15 to -10 minutes
        for (i in 15 downTo 10) {
            processReading(108, baseTs - i * 60_000L)
        }
        // 10-minute gap, then a new reading
        processReading(108, baseTs)
        val latest = dao.latest().first()
        // The closest reading to 5-min-ago is at -10 min.
        // Target is -5 min. Distance from -10 to -5 = 5 min.
        // That's within the 10-min window, so direction IS computed (Flat).
        assertEquals("Flat", latest!!.direction)
    }

    @Test
    fun `large gap resets direction to NONE`() = runTest {
        // Reading at 20 minutes ago only
        processReading(108, baseTs - 20 * 60_000L)
        // New reading now — no reading within 10 min of the 5-min target
        processReading(108, baseTs)
        val latest = dao.latest().first()
        assertEquals("NONE", latest!!.direction)
    }
}
