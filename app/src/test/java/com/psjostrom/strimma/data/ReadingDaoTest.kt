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

@RunWith(RobolectricTestRunner::class)
class ReadingDaoTest {

    private lateinit var db: StrimmaDatabase
    private lateinit var dao: ReadingDao

    private val baseTs = 1_700_000_000_000L

    private fun reading(minutesAgo: Int, sgv: Int, pushed: Int = 1): GlucoseReading {
        return GlucoseReading(
            ts = baseTs - minutesAgo * 60_000L,
            sgv = sgv,
            direction = "Flat", delta = 0.0, pushed = pushed
        )
    }

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

    // --- Insert & query ---

    @Test
    fun `insert and retrieve latest`() = runTest {
        dao.insert(reading(0, 108))
        val latest = dao.latest().first()
        assertNotNull(latest)
        assertEquals(108, latest!!.sgv)
    }

    @Test
    fun `latest returns most recent by timestamp`() = runTest {
        dao.insert(reading(5, 90))
        dao.insert(reading(2, 126))
        dao.insert(reading(0, 108))
        val latest = dao.latest().first()
        assertEquals(baseTs, latest!!.ts)
        assertEquals(108, latest.sgv)
    }

    @Test
    fun `since returns readings after cutoff sorted ascending`() = runTest {
        dao.insert(reading(10, 90))
        dao.insert(reading(5, 108))
        dao.insert(reading(2, 126))
        dao.insert(reading(0, 144))

        val since = baseTs - 6 * 60_000L
        val results = dao.since(since)
        assertEquals(3, results.size)
        assertEquals(108, results[0].sgv) // 5 min ago
        assertEquals(144, results[2].sgv) // 0 min ago
        assertTrue("should be sorted ASC by ts", results[0].ts < results[1].ts)
    }

    @Test
    fun `lastN returns N most recent`() = runTest {
        for (i in 0..9) dao.insert(reading(i, 90 + i * 2))
        val last3 = dao.lastN(3)
        assertEquals(3, last3.size)
        assertEquals(baseTs, last3[0].ts) // most recent first (DESC)
    }

    // --- Dedup (insert OR REPLACE) ---

    @Test
    fun `duplicate timestamp replaces existing reading`() = runTest {
        dao.insert(reading(0, 108))
        dao.insert(GlucoseReading(
            ts = baseTs, sgv = 144,
            direction = "SingleUp", delta = 18.0, pushed = 0
        ))
        val latest = dao.latest().first()
        assertEquals(144, latest!!.sgv)
    }

    // --- Unpushed tracking ---

    @Test
    fun `unpushed returns only readings with pushed=0`() = runTest {
        dao.insert(reading(3, 90).copy(pushed = 1))
        dao.insert(reading(2, 108).copy(pushed = 0))
        dao.insert(reading(1, 126).copy(pushed = 0))
        dao.insert(reading(0, 144).copy(pushed = 1))

        val unpushed = dao.unpushed()
        assertEquals(2, unpushed.size)
        assertEquals(108, unpushed[0].sgv) // oldest first (ASC)
    }

    @Test
    fun `markPushed updates pushed flag`() = runTest {
        dao.insert(reading(1, 108).copy(pushed = 0))
        dao.insert(reading(0, 126).copy(pushed = 0))

        val ts = listOf(baseTs - 60_000L)
        dao.markPushed(ts)

        val unpushed = dao.unpushed()
        assertEquals(1, unpushed.size)
        assertEquals(126, unpushed[0].sgv) // only the un-marked one remains
    }

    // --- Prune ---

    @Test
    fun `pruneBefore deletes old readings`() = runTest {
        dao.insert(reading(100, 90)) // 100 min ago
        dao.insert(reading(50, 108))  // 50 min ago
        dao.insert(reading(0, 126))   // now

        dao.pruneBefore(baseTs - 60 * 60_000L) // prune > 60 min ago
        val all = dao.since(0)
        assertEquals(2, all.size)
    }

    // --- Flow reactivity ---

    @Test
    fun `latest flow emits null when DB is empty`() = runTest {
        val latest = dao.latest().first()
        assertNull(latest)
    }

    // --- replaceInBucket: load-bearing @Transaction contract ---
    //
    // Direct tests for the DAO method that ReadingPipeline relies on. The @Transaction is
    // the guarantee that concurrent readers (notification update, UI Flow, stale-check
    // loop) never observe the bucket as empty between the delete and the insert. A future
    // maintainer who removes @Transaction thinking "two statements doesn't need one"
    // breaks the guarantee silently — these tests pin the contract.

    @Test
    fun `replaceInBucket - oldTs equals newTs uses REPLACE without delete`() = runTest {
        // Same-ts case: deleteByTs would remove the row we're about to insert. The
        // replaceInBucket impl must skip the delete in this case and rely on
        // OnConflictStrategy.REPLACE.
        dao.insert(reading(0, 108).copy(direction = "Flat", delta = 0.0))
        dao.replaceInBucket(
            oldTs = baseTs,
            newReading = GlucoseReading(baseTs, 120, "SingleUp", 12.0, 0)
        )
        val all = dao.since(0)
        assertEquals(1, all.size)
        assertEquals(120, all[0].sgv)
        assertEquals("SingleUp", all[0].direction)
        assertEquals(0, all[0].pushed)
    }

    @Test
    fun `replaceInBucket - oldTs differs swaps row at new ts`() = runTest {
        dao.insert(reading(0, 108))
        dao.replaceInBucket(
            oldTs = baseTs,
            newReading = GlucoseReading(baseTs + 100, 120, "SingleUp", 12.0, 0)
        )
        val all = dao.since(0)
        assertEquals("old row deleted, new row inserted", 1, all.size)
        assertEquals(120, all[0].sgv)
        assertEquals(baseTs + 100, all[0].ts)
    }

    @Test
    fun `replaceInBucket - bucket has exactly one row regardless of starting state`() = runTest {
        // Multi-row bucket (pre-existing puller backfill); replaceInBucket only touches
        // the named oldTs and inserts the new one. Other rows are untouched.
        dao.insert(GlucoseReading(baseTs - 100, 90, "Flat", null, 1))
        dao.insert(GlucoseReading(baseTs - 50, 95, "Flat", null, 1))
        dao.insert(GlucoseReading(baseTs, 100, "Flat", null, 1))

        dao.replaceInBucket(
            oldTs = baseTs,
            newReading = GlucoseReading(baseTs + 10, 110, "SingleUp", 10.0, 0)
        )

        val all = dao.since(0).sortedBy { it.ts }
        assertEquals(3, all.size)
        assertEquals("oldest puller row preserved", 90, all[0].sgv)
        assertEquals("middle puller row preserved", 95, all[1].sgv)
        assertEquals("named row replaced by new", 110, all[2].sgv)
        assertEquals(baseTs + 10, all[2].ts)
    }

    @Test
    fun `replaceInBucket - newReading is unpushed by default flow`() = runTest {
        dao.insert(reading(0, 108).copy(pushed = 1))
        dao.replaceInBucket(
            oldTs = baseTs,
            newReading = GlucoseReading(baseTs + 10, 120, "Flat", 0.0, 0)
        )
        val unpushed = dao.unpushed()
        assertEquals(
            "fresh reading should be marked unpushed for the next push attempt",
            1, unpushed.size
        )
        assertEquals(120, unpushed[0].sgv)
    }
}
