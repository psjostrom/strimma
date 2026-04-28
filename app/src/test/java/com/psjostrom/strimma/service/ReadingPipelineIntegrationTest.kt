package com.psjostrom.strimma.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.data.Direction
import com.psjostrom.strimma.data.DirectionComputer
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SensorIntervals
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
 * Integration test for the real [ReadingPipeline].
 *
 * Wires:
 *  - real [ReadingDao] (Room in-memory)
 *  - real [DirectionComputer]
 *  - hand-written [FakePusher] / [FakeUploader] standing in for the network and Tidepool
 *    side-effects (we own the [ReadingPusher] / [ReadingUploader] interfaces specifically
 *    so the pipeline can be tested without dragging in HTTP, settings, alerts, etc.).
 *
 * Covers SGV validation, mg/dL rounding, source-aware bucketed dedup, in-bucket
 * replacement (Eversense cluster behavior), direction/delta integration, and that
 * the side-effects fire on store but not on dedup.
 */
@RunWith(RobolectricTestRunner::class)
class ReadingPipelineIntegrationTest {

    private lateinit var db: StrimmaDatabase
    private lateinit var dao: ReadingDao
    private lateinit var pusher: FakePusher
    private lateinit var uploader: FakeUploader
    private lateinit var pipeline: ReadingPipeline

    private val baseTs = 1_700_000_000_000L
    private val eversense = "com.senseonics.eversense365.us"
    private val libre3 = "com.freestylelibre3.app"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.readingDao()
        pusher = FakePusher()
        uploader = FakeUploader()
        pipeline = ReadingPipeline(
            dao = dao,
            directionComputer = DirectionComputer(),
            pusher = pusher,
            uploader = uploader,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- SGV validation ---

    @Test
    fun `sgv below minimum is rejected`() = runTest {
        pipeline.processReading(17.0, baseTs)
        assertTrue(dao.since(0).isEmpty())
        assertEquals(0, pusher.count.get())
        assertEquals(0, uploader.count.get())
    }

    @Test
    fun `sgv of zero is rejected`() = runTest {
        pipeline.processReading(0.0, baseTs)
        assertTrue(dao.since(0).isEmpty())
    }

    @Test
    fun `negative sgv is rejected`() = runTest {
        pipeline.processReading(-5.0, baseTs)
        assertTrue(dao.since(0).isEmpty())
    }

    @Test
    fun `sgv above maximum is rejected`() = runTest {
        pipeline.processReading(901.0, baseTs)
        assertTrue(dao.since(0).isEmpty())
    }

    @Test
    fun `sgv at minimum boundary is accepted`() = runTest {
        pipeline.processReading(18.0, baseTs)
        assertEquals(1, dao.since(0).size)
    }

    @Test
    fun `sgv at maximum boundary is accepted`() = runTest {
        pipeline.processReading(900.0, baseTs)
        assertEquals(900, dao.since(0)[0].sgv)
    }

    // --- mg/dL → Int rounding ---

    @Test
    fun `mgdl is rounded to nearest int`() = runTest {
        pipeline.processReading(108.7, baseTs)
        assertEquals(109, dao.since(0)[0].sgv)
    }

    @Test
    fun `mgdl halfway rounds up`() = runTest {
        pipeline.processReading(108.5, baseTs)
        assertEquals(109, dao.since(0)[0].sgv)
    }

    @Test
    fun `mgdl just below half rounds down`() = runTest {
        pipeline.processReading(108.4, baseTs)
        assertEquals(108, dao.since(0)[0].sgv)
    }

    // --- In-bucket replacement (changing value within one sample period) ---

    @Test
    fun `same bucket - changing value replaces the prior reading at the new ts`() = runTest {
        pipeline.processReading(108.0, baseTs)
        pipeline.processReading(120.0, baseTs + 1)

        val all = dao.since(0)
        assertEquals(1, all.size)
        assertEquals(120, all[0].sgv)
        assertEquals("the new reading's ts is preserved", baseTs + 1, all[0].ts)
    }

    @Test
    fun `same bucket - cluster of changing values keeps only the last`() = runTest {
        pipeline.processReading(103.0, baseTs)
        pipeline.processReading(101.0, baseTs + 50)
        pipeline.processReading(100.0, baseTs + 100)

        val all = dao.since(0)
        assertEquals(1, all.size)
        assertEquals(100, all[0].sgv)
        assertEquals(baseTs + 100, all[0].ts)
    }

    @Test
    fun `different bucket - different value is stored as a new reading`() = runTest {
        // Default 1-min bucket; baseTs is at +20 s offset within its bucket, so +41 s
        // crosses into the next bucket.
        pipeline.processReading(108.0, baseTs)
        pipeline.processReading(120.0, baseTs + 41_000)
        val all = dao.since(0).sortedBy { it.ts }
        assertEquals(2, all.size)
        assertEquals(108, all[0].sgv)
        assertEquals(120, all[1].sgv)
    }

    @Test
    fun `same bucket replacement triggers push and uploader with the new value`() = runTest {
        pipeline.processReading(108.0, baseTs)
        pipeline.processReading(120.0, baseTs + 100)

        assertEquals(2, pusher.count.get())
        assertEquals(120, pusher.last!!.sgv)
        assertEquals(2, uploader.count.get())
    }

    // --- Same-value repost dedup (source-aware) ---

    @Test
    fun `eversense - same-value repost within sample period is dropped`() = runTest {
        pipeline.processReading(103.0, baseTs, source = eversense)
        // Stays in the same 5-min bucket — both reposts collapse to the first stored value.
        pipeline.processReading(103.0, baseTs + 30_000, source = eversense)
        pipeline.processReading(103.0, baseTs + 90_000, source = eversense)

        val all = dao.since(0)
        assertEquals(1, all.size)
        assertEquals(baseTs, all[0].ts)
    }

    @Test
    fun `eversense - same value in next bucket is stored separately`() = runTest {
        pipeline.processReading(103.0, baseTs, source = eversense)
        // baseTs is +200 s into its bucket; +101 s lands in the next 5-min bucket.
        pipeline.processReading(103.0, baseTs + 101_000, source = eversense)
        assertEquals(2, dao.since(0).size)
    }

    @Test
    fun `eversense - changing value within same 5-min bucket replaces at new ts`() = runTest {
        // The late-cluster value (101) supersedes the leading stale 103, with the real
        // notification ts preserved rather than the earlier repost's ts.
        pipeline.processReading(103.0, baseTs, source = eversense)
        pipeline.processReading(101.0, baseTs + 90_000, source = eversense)

        val all = dao.since(0)
        assertEquals(1, all.size)
        assertEquals(101, all[0].sgv)
        assertEquals(baseTs + 90_000, all[0].ts)
    }

    @Test
    fun `eversense - changing value across 5-min buckets is stored separately`() = runTest {
        pipeline.processReading(103.0, baseTs, source = eversense)
        pipeline.processReading(101.0, baseTs + 101_000, source = eversense)
        val all = dao.since(0).sortedBy { it.ts }
        assertEquals(2, all.size)
        assertEquals(103, all[0].sgv)
        assertEquals(101, all[1].sgv)
    }

    @Test
    fun `libre 3 - same value at 60s gap is stored, not deduped`() = runTest {
        // Each Libre 3 reading lands in its own 1-min bucket.
        pipeline.processReading(108.0, baseTs, source = libre3)
        pipeline.processReading(108.0, baseTs + 60_000, source = libre3)
        assertEquals(2, dao.since(0).size)
    }

    @Test
    fun `libre 3 - same value within same bucket is dropped as repost`() = runTest {
        pipeline.processReading(108.0, baseTs, source = libre3)
        pipeline.processReading(108.0, baseTs + 30_000, source = libre3)
        assertEquals(1, dao.since(0).size)
    }

    @Test
    fun `unknown source defaults to 1-min bucketing`() = runTest {
        pipeline.processReading(108.0, baseTs)
        pipeline.processReading(108.0, baseTs + 30_000) // same bucket → drop
        pipeline.processReading(108.0, baseTs + 60_000) // next bucket → store
        assertEquals(2, dao.since(0).size)
    }

    // --- Eversense replay (issue #192) ---

    @Test
    fun `eversense replay - repost spam collapses to one reading per 5-min bucket`() = runTest {
        // Mirrors halprewitt's log on issue #192: two notifications per minute (at :31 and
        // :46 of each minute) plus a sub-second value-change cluster at the 5-min mark.
        // The cluster's first notification still carries the OLD value; the new value
        // arrives within ~1 s.

        val secsToMs = 1000L

        pipeline.processReading(103.0, baseTs + 31 * secsToMs, source = eversense)

        for (m in 0..10) {
            pipeline.processReading(103.0, baseTs + (m * 60 + 46) * secsToMs, source = eversense)
            if (m < 10) {
                pipeline.processReading(
                    103.0, baseTs + ((m + 1) * 60 + 31) * secsToMs, source = eversense
                )
            }
        }

        val cluster1Ts = baseTs + (11 * 60 + 31) * secsToMs
        pipeline.processReading(103.0, cluster1Ts, source = eversense)
        for (offsetMs in listOf(57L, 64L, 97L, 148L, 285L, 922L)) {
            pipeline.processReading(101.0, cluster1Ts + offsetMs, source = eversense)
        }

        for (offsetSecs in listOf(46, 91, 106, 151, 166, 211, 226, 271, 286, 331)) {
            pipeline.processReading(
                101.0, baseTs + (11 * 60 + offsetSecs) * secsToMs, source = eversense
            )
        }

        val all = dao.since(0).sortedBy { it.ts }
        // baseTs is +200 s into its 5-min bucket, so the test data spans four wall-clock
        // buckets: one for the initial :31 read, one for the late-:46 reposts that
        // crossed the bucket boundary, one containing the 5-min value-change cluster, and
        // one for the 101 reposts after the cluster.
        assertEquals(
            "test traffic should land at most one stored reading per 5-min bucket",
            4, all.size
        )
        assertEquals(2, all.count { it.sgv == 103 })
        assertEquals(2, all.count { it.sgv == 101 })

        val cluster101 = all.single { it.sgv == 101 && it.ts == cluster1Ts + 57L }
        assertEquals(
            "the bucket replacement keeps the first new-value notification's ts",
            cluster1Ts + 57L, cluster101.ts
        )

        assertNull(
            "reading at +151 s should have been dropped as a same-value repost",
            all.find { it.ts == baseTs + 151 * secsToMs }
        )
    }

    // --- Side-effect plumbing ---

    @Test
    fun `push fires when a reading is stored`() = runTest {
        pipeline.processReading(108.0, baseTs)
        assertEquals(1, pusher.count.get())
        assertEquals(108, pusher.last!!.sgv)
    }

    @Test
    fun `push does not fire when a same-value repost is dropped`() = runTest {
        pipeline.processReading(108.0, baseTs, source = eversense)
        pipeline.processReading(108.0, baseTs + 30_000, source = eversense)
        assertEquals(1, pusher.count.get())
    }

    @Test
    fun `push does not fire when sgv is invalid`() = runTest {
        pipeline.processReading(0.0, baseTs)
        assertEquals(0, pusher.count.get())
    }

    @Test
    fun `pushed reading carries computed direction and delta`() = runTest {
        for (i in 6 downTo 1) {
            pipeline.processReading(108.0, baseTs - i * 60_000L, source = libre3)
        }
        pusher.reset()
        pipeline.processReading(108.0, baseTs, source = libre3)
        assertEquals("Flat", pusher.last!!.direction)
    }

    @Test
    fun `uploader fires on store`() = runTest {
        pipeline.processReading(108.0, baseTs)
        assertEquals(1, uploader.count.get())
    }

    @Test
    fun `uploader does not fire on same-value repost dedup`() = runTest {
        pipeline.processReading(108.0, baseTs, source = eversense)
        pipeline.processReading(108.0, baseTs + 30_000, source = eversense)
        assertEquals(1, uploader.count.get())
    }

    // --- Delta rounding precision ---

    @Test
    fun `delta is rounded to 0_1 precision`() = runTest {
        for (i in 6 downTo 1) {
            pipeline.processReading(100.0, baseTs - i * 60_000L, source = libre3)
        }
        pipeline.processReading(107.0, baseTs, source = libre3)

        val latest = dao.latest().first()!!
        assertNotNull(latest.delta)
        val scaledDelta = latest.delta!! * 10.0
        assertEquals(
            "Delta should be rounded to 0.1: was ${latest.delta}",
            Math.round(scaledDelta).toDouble(),
            scaledDelta,
            0.001
        )
    }

    @Test
    fun `delta of zero for an unchanged reading`() = runTest {
        for (i in 5 downTo 1) {
            pipeline.processReading(100.0, baseTs - i * 60_000L, source = libre3)
        }
        pipeline.processReading(100.0, baseTs, source = libre3)
        assertEquals(0.0, dao.latest().first()!!.delta!!, 0.001)
    }

    // --- Direction follows EASD thresholds (real DirectionComputer) ---

    @Test
    fun `steady readings produce Flat direction`() = runTest {
        for (i in 6 downTo 1) {
            pipeline.processReading(108.0, baseTs - i * 60_000L, source = libre3)
        }
        pipeline.processReading(108.0, baseTs, source = libre3)
        assertEquals("Flat", dao.latest().first()!!.direction)
    }

    @Test
    fun `rapidly rising readings produce upward direction`() = runTest {
        for (i in 6 downTo 1) {
            pipeline.processReading(100.0 + (6 - i) * 4.0, baseTs - i * 60_000L, source = libre3)
        }
        pipeline.processReading(124.0, baseTs, source = libre3)
        val dir = Direction.valueOf(dao.latest().first()!!.direction)
        assertTrue(
            "Expected upward direction, got $dir",
            dir == Direction.FortyFiveUp || dir == Direction.SingleUp || dir == Direction.DoubleUp
        )
    }

    @Test
    fun `rapidly falling readings produce downward direction`() = runTest {
        for (i in 6 downTo 1) {
            pipeline.processReading(180.0 - (6 - i) * 4.0, baseTs - i * 60_000L, source = libre3)
        }
        pipeline.processReading(156.0, baseTs, source = libre3)
        val dir = Direction.valueOf(dao.latest().first()!!.direction)
        assertTrue(
            "Expected downward direction, got $dir",
            dir == Direction.FortyFiveDown || dir == Direction.SingleDown || dir == Direction.DoubleDown
        )
    }

    @Test
    fun `first reading has NONE direction and null delta`() = runTest {
        pipeline.processReading(108.0, baseTs)
        val latest = dao.latest().first()!!
        assertEquals("NONE", latest.direction)
        assertNull(latest.delta)
    }

    @Test
    fun `gap in data resets direction to NONE`() = runTest {
        // Only a single reading 20 min ago — nothing within 10 min of the 5-min target.
        pipeline.processReading(108.0, baseTs - 20 * 60_000L, source = libre3)
        pipeline.processReading(108.0, baseTs, source = libre3)
        assertEquals("NONE", dao.latest().first()!!.direction)
    }

    // --- Stored state ---

    @Test
    fun `reading is stored as unpushed`() = runTest {
        pipeline.processReading(108.0, baseTs)
        val unpushed = dao.unpushed()
        assertEquals(1, unpushed.size)
        assertEquals(0, unpushed[0].pushed)
    }

    @Test
    fun `multiple changing readings are all stored in chronological order`() = runTest {
        for (i in 0..4) {
            pipeline.processReading(90.0 + i * 10, baseTs + i * 60_000L, source = libre3)
        }
        val all = dao.since(0)
        assertEquals(5, all.size)
        for (i in 1 until all.size) {
            assertTrue(all[i].ts > all[i - 1].ts)
        }
    }

    // --- Test doubles for the side-effect interfaces ---

    private class FakePusher : ReadingPusher {
        val count = AtomicInteger(0)
        var last: GlucoseReading? = null

        override fun pushReading(reading: GlucoseReading) {
            count.incrementAndGet()
            last = reading
        }

        fun reset() {
            count.set(0)
            last = null
        }
    }

    private class FakeUploader : ReadingUploader {
        val count = AtomicInteger(0)

        override fun onNewReading() {
            count.incrementAndGet()
        }
    }
}
