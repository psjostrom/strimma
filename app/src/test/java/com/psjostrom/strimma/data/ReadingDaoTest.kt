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

    private fun reading(minutesAgo: Int, mmol: Double, pushed: Int = 1): GlucoseReading {
        val sgv = (mmol * 18.0182).toInt()
        return GlucoseReading(
            ts = baseTs - minutesAgo * 60_000L,
            sgv = sgv, mmol = mmol,
            direction = "Flat", deltaMmol = 0.0, pushed = pushed
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
        dao.insert(reading(0, 6.0))
        val latest = dao.latest().first()
        assertNotNull(latest)
        assertEquals(6.0, latest!!.mmol, 0.001)
    }

    @Test
    fun `latest returns most recent by timestamp`() = runTest {
        dao.insert(reading(5, 5.0))
        dao.insert(reading(2, 7.0))
        dao.insert(reading(0, 6.0))
        val latest = dao.latest().first()
        assertEquals(baseTs, latest!!.ts)
        assertEquals(6.0, latest.mmol, 0.001)
    }

    @Test
    fun `since returns readings after cutoff sorted ascending`() = runTest {
        dao.insert(reading(10, 5.0))
        dao.insert(reading(5, 6.0))
        dao.insert(reading(2, 7.0))
        dao.insert(reading(0, 8.0))

        val since = baseTs - 6 * 60_000L
        val results = dao.since(since)
        assertEquals(3, results.size)
        assertEquals(6.0, results[0].mmol, 0.001) // 5 min ago
        assertEquals(8.0, results[2].mmol, 0.001) // 0 min ago
        assertTrue("should be sorted ASC by ts", results[0].ts < results[1].ts)
    }

    @Test
    fun `lastN returns N most recent`() = runTest {
        for (i in 0..9) dao.insert(reading(i, 5.0 + i * 0.1))
        val last3 = dao.lastN(3)
        assertEquals(3, last3.size)
        assertEquals(baseTs, last3[0].ts) // most recent first (DESC)
    }

    // --- Dedup (insert OR REPLACE) ---

    @Test
    fun `duplicate timestamp replaces existing reading`() = runTest {
        dao.insert(reading(0, 6.0))
        dao.insert(GlucoseReading(
            ts = baseTs, sgv = 144, mmol = 8.0,
            direction = "SingleUp", deltaMmol = 1.0, pushed = 0
        ))
        val latest = dao.latest().first()
        assertEquals(8.0, latest!!.mmol, 0.001)
    }

    // --- Unpushed tracking ---

    @Test
    fun `unpushed returns only readings with pushed=0`() = runTest {
        dao.insert(reading(3, 5.0).copy(pushed = 1))
        dao.insert(reading(2, 6.0).copy(pushed = 0))
        dao.insert(reading(1, 7.0).copy(pushed = 0))
        dao.insert(reading(0, 8.0).copy(pushed = 1))

        val unpushed = dao.unpushed()
        assertEquals(2, unpushed.size)
        assertEquals(6.0, unpushed[0].mmol, 0.001) // oldest first (ASC)
    }

    @Test
    fun `markPushed updates pushed flag`() = runTest {
        dao.insert(reading(1, 6.0).copy(pushed = 0))
        dao.insert(reading(0, 7.0).copy(pushed = 0))

        val ts = listOf(baseTs - 60_000L)
        dao.markPushed(ts)

        val unpushed = dao.unpushed()
        assertEquals(1, unpushed.size)
        assertEquals(7.0, unpushed[0].mmol, 0.001) // only the un-marked one remains
    }

    // --- Prune ---

    @Test
    fun `pruneBefore deletes old readings`() = runTest {
        dao.insert(reading(100, 5.0)) // 100 min ago
        dao.insert(reading(50, 6.0))  // 50 min ago
        dao.insert(reading(0, 7.0))   // now

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
