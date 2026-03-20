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
}
