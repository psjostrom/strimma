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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration test for the real [ReadingPipeline].
 *
 * Pipeline is now pure storage — no push/upload/alert side effects to inject. Each test
 * gets a fresh [Env] via [withPipeline]; no `@Before`/`lateinit` shared state.
 */
@RunWith(RobolectricTestRunner::class)
class ReadingPipelineIntegrationTest {

    private val baseTs = 1_700_000_000_000L
    private val eversense = "com.senseonics.eversense365.us"
    private val libre3 = "com.freestylelibre3.app"

    private data class Env(
        val db: StrimmaDatabase,
        val dao: ReadingDao,
        val pipeline: ReadingPipeline,
    )

    private fun createEnv(): Env {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.readingDao()
        val pipeline = ReadingPipeline(dao = dao, directionComputer = DirectionComputer())
        return Env(db, dao, pipeline)
    }

    private suspend fun withPipeline(block: suspend (Env) -> Unit) {
        val env = createEnv()
        try {
            block(env)
        } finally {
            env.db.close()
        }
    }

    // --- SGV validation ---

    @Test
    fun `sgv below minimum is rejected`() = runTest {
        withPipeline { env ->
            assertNull(env.pipeline.processReading(17.0, baseTs))
            assertTrue(env.dao.since(0).isEmpty())
        }
    }

    @Test
    fun `sgv of zero is rejected`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(0.0, baseTs)
            assertTrue(env.dao.since(0).isEmpty())
        }
    }

    @Test
    fun `negative sgv is rejected`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(-5.0, baseTs)
            assertTrue(env.dao.since(0).isEmpty())
        }
    }

    @Test
    fun `sgv above maximum is rejected`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(901.0, baseTs)
            assertTrue(env.dao.since(0).isEmpty())
        }
    }

    @Test
    fun `sgv at minimum boundary is accepted`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(18.0, baseTs)
            assertEquals(1, env.dao.since(0).size)
        }
    }

    @Test
    fun `sgv at maximum boundary is accepted`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(900.0, baseTs)
            assertEquals(900, env.dao.since(0)[0].sgv)
        }
    }

    // --- mg/dL → Int rounding ---

    @Test
    fun `mgdl is rounded to nearest int`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(108.7, baseTs)
            assertEquals(109, env.dao.since(0)[0].sgv)
        }
    }

    @Test
    fun `mgdl halfway rounds up`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(108.5, baseTs)
            assertEquals(109, env.dao.since(0)[0].sgv)
        }
    }

    @Test
    fun `mgdl just below half rounds down`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(108.4, baseTs)
            assertEquals(108, env.dao.since(0)[0].sgv)
        }
    }

    // --- In-bucket replacement (changing value within one sample period) ---

    @Test
    fun `same bucket - changing value replaces the prior reading at the new ts`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(108.0, baseTs)
            env.pipeline.processReading(120.0, baseTs + 1)

            val all = env.dao.since(0)
            assertEquals(1, all.size)
            assertEquals(120, all[0].sgv)
            assertEquals(baseTs + 1, all[0].ts)
        }
    }

    @Test
    fun `same bucket - cluster of changing values keeps only the last`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(103.0, baseTs)
            env.pipeline.processReading(101.0, baseTs + 50)
            env.pipeline.processReading(100.0, baseTs + 100)

            val all = env.dao.since(0)
            assertEquals(1, all.size)
            assertEquals(100, all[0].sgv)
            assertEquals(baseTs + 100, all[0].ts)
        }
    }

    @Test
    fun `different bucket - different value is stored as a new reading`() = runTest {
        withPipeline { env ->
            // Default 1-min bucket; baseTs is at +20 s offset within its bucket, so +41 s
            // crosses into the next bucket.
            env.pipeline.processReading(108.0, baseTs)
            env.pipeline.processReading(120.0, baseTs + 41_000)
            val all = env.dao.since(0)
            assertEquals(2, all.size)
            assertEquals(108, all[0].sgv)
            assertEquals(120, all[1].sgv)
        }
    }

    // --- Backwards-in-time guard ---

    @Test
    fun `backwards-in-time reading in the same bucket is rejected`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(108.0, baseTs + 1_000)
            // A new reading with an OLDER ts than what's in the bucket would have
            // corrupted dao.latestOnce/since chronological order.
            val result = env.pipeline.processReading(120.0, baseTs)
            assertNull(result)

            val all = env.dao.since(0)
            assertEquals("prior reading preserved", 1, all.size)
            assertEquals(108, all[0].sgv)
            assertEquals(baseTs + 1_000, all[0].ts)
        }
    }

    @Test
    fun `same-ts reading with same value is dropped`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(108.0, baseTs)
            val result = env.pipeline.processReading(108.0, baseTs)
            assertNull(result)
            assertEquals(1, env.dao.since(0).size)
        }
    }

    // --- Same-value repost dedup (source-aware) ---

    @Test
    fun `eversense - same-value repost within sample period is dropped`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(103.0, baseTs, source = eversense)
            env.pipeline.processReading(103.0, baseTs + 30_000, source = eversense)
            env.pipeline.processReading(103.0, baseTs + 90_000, source = eversense)

            val all = env.dao.since(0)
            assertEquals(1, all.size)
            assertEquals(baseTs, all[0].ts)
        }
    }

    @Test
    fun `eversense - same value in next bucket is stored separately`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(103.0, baseTs, source = eversense)
            env.pipeline.processReading(103.0, baseTs + 101_000, source = eversense)
            assertEquals(2, env.dao.since(0).size)
        }
    }

    @Test
    fun `eversense - changing value within same 5-min bucket replaces at new ts`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(103.0, baseTs, source = eversense)
            env.pipeline.processReading(101.0, baseTs + 90_000, source = eversense)

            val all = env.dao.since(0)
            assertEquals(1, all.size)
            assertEquals(101, all[0].sgv)
            assertEquals(baseTs + 90_000, all[0].ts)
        }
    }

    @Test
    fun `eversense - changing value across 5-min buckets is stored separately`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(103.0, baseTs, source = eversense)
            env.pipeline.processReading(101.0, baseTs + 101_000, source = eversense)
            val all = env.dao.since(0)
            assertEquals(2, all.size)
            assertEquals(103, all[0].sgv)
            assertEquals(101, all[1].sgv)
        }
    }

    @Test
    fun `libre 3 - same value at 60s gap is stored, not deduped`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(108.0, baseTs, source = libre3)
            env.pipeline.processReading(108.0, baseTs + 60_000, source = libre3)
            assertEquals(2, env.dao.since(0).size)
        }
    }

    @Test
    fun `libre 3 - same value within same bucket is dropped as repost`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(108.0, baseTs, source = libre3)
            env.pipeline.processReading(108.0, baseTs + 30_000, source = libre3)
            assertEquals(1, env.dao.since(0).size)
        }
    }

    @Test
    fun `unknown source defaults to 1-min bucketing`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(108.0, baseTs)
            env.pipeline.processReading(108.0, baseTs + 30_000)
            env.pipeline.processReading(108.0, baseTs + 60_000)
            assertEquals(2, env.dao.since(0).size)
        }
    }

    // --- Pre-existing same-bucket duplicates (e.g. NightscoutPuller backfill) ---

    @Test
    fun `dedup matches ANY in-bucket row, not just the latest`() = runTest {
        withPipeline { env ->
            // NightscoutPuller backfilled three rows into one Eversense 5-min bucket.
            val bucketStart = (baseTs / 300_000) * 300_000
            env.dao.insert(GlucoseReading(bucketStart + 10_000, 100, "Flat", null, 1))
            env.dao.insert(GlucoseReading(bucketStart + 70_000, 102, "Flat", null, 1))
            env.dao.insert(GlucoseReading(bucketStart + 130_000, 104, "Flat", null, 1))

            // New reading with sgv 100 — matches the OLDEST puller row (not the latest).
            // Must be deduped, not added as a fourth row.
            val result = env.pipeline.processReading(100.0, baseTs, source = eversense)
            assertNull(result)
            assertEquals(
                "puller's three rows preserved; pipeline's matching value dropped",
                3, env.dao.since(0).size
            )
        }
    }

    @Test
    fun `direction filter excludes ALL same-bucket rows`() = runTest {
        withPipeline { env ->
            // Eversense's 5-min bucket is wide enough to contain both prior puller
            // backfill AND multiple minutes of steady history if naively seeded — so we
            // use libre3 (1-min buckets) for the steady history to keep each in its own
            // bucket, and synthesise the puller backfill directly in baseTs's bucket.
            val bucketStart = (baseTs / 60_000) * 60_000
            // Three pre-existing rows in baseTs's bucket — all earlier than baseTs so
            // baseTs isn't backwards-in-time vs. them. Wildly different value (200) would
            // skew DirectionComputer's slope if NOT filtered out by the bucket exclusion.
            env.dao.insert(GlucoseReading(bucketStart + 1_000, 200, "DoubleDown", null, 1))
            env.dao.insert(GlucoseReading(bucketStart + 5_000, 200, "DoubleDown", null, 1))
            env.dao.insert(GlucoseReading(bucketStart + 10_000, 200, "DoubleDown", null, 1))

            // Steady history in prior 1-min buckets so direction can compute as Flat.
            for (i in 6 downTo 1) {
                env.dao.insert(
                    GlucoseReading(baseTs - i * 60_000L, 100, "Flat", null, 1)
                )
            }

            val result = env.pipeline.processReading(100.0, baseTs, source = libre3)
            assertNotNull(result)
            // If direction had included the in-bucket 200s, slope would have read as a
            // steep drop. Excluding the bucket entirely, slope is Flat.
            assertEquals("Flat", result!!.direction)
        }
    }

    // --- Eversense replay (issue #192) ---

    @Test
    fun `eversense replay - repost spam collapses to one reading per 5-min bucket`() = runTest {
        withPipeline { env ->
            val secsToMs = 1000L

            env.pipeline.processReading(103.0, baseTs + 31 * secsToMs, source = eversense)

            for (m in 0..10) {
                env.pipeline.processReading(
                    103.0, baseTs + (m * 60 + 46) * secsToMs, source = eversense
                )
                if (m < 10) {
                    env.pipeline.processReading(
                        103.0, baseTs + ((m + 1) * 60 + 31) * secsToMs, source = eversense
                    )
                }
            }

            val cluster1Ts = baseTs + (11 * 60 + 31) * secsToMs
            env.pipeline.processReading(103.0, cluster1Ts, source = eversense)
            for (offsetMs in listOf(57L, 64L, 97L, 148L, 285L, 922L)) {
                env.pipeline.processReading(101.0, cluster1Ts + offsetMs, source = eversense)
            }

            for (offsetSecs in listOf(46, 91, 106, 151, 166, 211, 226, 271, 286, 331)) {
                env.pipeline.processReading(
                    101.0, baseTs + (11 * 60 + offsetSecs) * secsToMs, source = eversense
                )
            }

            val all = env.dao.since(0)
            assertEquals(
                "test traffic should land at most one stored reading per 5-min bucket",
                4, all.size
            )
            assertEquals(2, all.count { it.sgv == 103 })
            assertEquals(2, all.count { it.sgv == 101 })

            val cluster101 = all.single { it.sgv == 101 && it.ts == cluster1Ts + 57L }
            assertEquals(cluster1Ts + 57L, cluster101.ts)
        }
    }

    // --- Delta and direction ---

    @Test
    fun `delta is rounded to 0_1 precision`() = runTest {
        withPipeline { env ->
            for (i in 6 downTo 1) {
                env.pipeline.processReading(100.0, baseTs - i * 60_000L, source = libre3)
            }
            env.pipeline.processReading(107.0, baseTs, source = libre3)

            val latest = env.dao.latest().first()!!
            assertNotNull(latest.delta)
            val scaledDelta = latest.delta!! * 10.0
            assertEquals(
                "Delta should be rounded to 0.1: was ${latest.delta}",
                Math.round(scaledDelta).toDouble(),
                scaledDelta,
                0.001
            )
        }
    }

    @Test
    fun `delta of zero for an unchanged reading`() = runTest {
        withPipeline { env ->
            for (i in 5 downTo 1) {
                env.pipeline.processReading(100.0, baseTs - i * 60_000L, source = libre3)
            }
            env.pipeline.processReading(100.0, baseTs, source = libre3)
            assertEquals(0.0, env.dao.latest().first()!!.delta!!, 0.001)
        }
    }

    @Test
    fun `steady readings produce Flat direction`() = runTest {
        withPipeline { env ->
            for (i in 6 downTo 1) {
                env.pipeline.processReading(108.0, baseTs - i * 60_000L, source = libre3)
            }
            env.pipeline.processReading(108.0, baseTs, source = libre3)
            assertEquals("Flat", env.dao.latest().first()!!.direction)
        }
    }

    @Test
    fun `rapidly rising readings produce upward direction`() = runTest {
        withPipeline { env ->
            for (i in 6 downTo 1) {
                env.pipeline.processReading(
                    100.0 + (6 - i) * 4.0, baseTs - i * 60_000L, source = libre3
                )
            }
            env.pipeline.processReading(124.0, baseTs, source = libre3)
            val dir = Direction.valueOf(env.dao.latest().first()!!.direction)
            assertTrue(
                "Expected upward direction, got $dir",
                dir == Direction.FortyFiveUp || dir == Direction.SingleUp || dir == Direction.DoubleUp
            )
        }
    }

    @Test
    fun `rapidly falling readings produce downward direction`() = runTest {
        withPipeline { env ->
            for (i in 6 downTo 1) {
                env.pipeline.processReading(
                    180.0 - (6 - i) * 4.0, baseTs - i * 60_000L, source = libre3
                )
            }
            env.pipeline.processReading(156.0, baseTs, source = libre3)
            val dir = Direction.valueOf(env.dao.latest().first()!!.direction)
            assertTrue(
                "Expected downward direction, got $dir",
                dir == Direction.FortyFiveDown || dir == Direction.SingleDown || dir == Direction.DoubleDown
            )
        }
    }

    @Test
    fun `first reading has NONE direction and null delta`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(108.0, baseTs)
            val latest = env.dao.latest().first()!!
            assertEquals("NONE", latest.direction)
            assertNull(latest.delta)
        }
    }

    @Test
    fun `gap in data resets direction to NONE`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(108.0, baseTs - 20 * 60_000L, source = libre3)
            env.pipeline.processReading(108.0, baseTs, source = libre3)
            assertEquals("NONE", env.dao.latest().first()!!.direction)
        }
    }

    // --- Stored state ---

    @Test
    fun `reading is stored as unpushed`() = runTest {
        withPipeline { env ->
            env.pipeline.processReading(108.0, baseTs)
            val unpushed = env.dao.unpushed()
            assertEquals(1, unpushed.size)
            assertEquals(0, unpushed[0].pushed)
        }
    }

    @Test
    fun `multiple changing readings stored in chronological order`() = runTest {
        withPipeline { env ->
            for (i in 0..4) {
                env.pipeline.processReading(
                    90.0 + i * 10, baseTs + i * 60_000L, source = libre3
                )
            }
            val all = env.dao.since(0)
            assertEquals(5, all.size)
            for (i in 1 until all.size) {
                assertTrue(all[i].ts > all[i - 1].ts)
            }
        }
    }
}
