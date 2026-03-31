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
class TreatmentDaoTest {

    private lateinit var db: StrimmaDatabase
    private lateinit var dao: TreatmentDao

    private val baseTs = 1_700_000_000_000L

    private fun treatment(
        id: String,
        minutesAgo: Int,
        insulin: Double? = null,
        carbs: Double? = null,
        basalRate: Double? = null,
        duration: Int? = null
    ) = Treatment(
        id = id,
        createdAt = baseTs - minutesAgo * 60_000L,
        eventType = if (carbs != null) "Meal Bolus" else "Correction Bolus",
        insulin = insulin,
        carbs = carbs,
        basalRate = basalRate,
        duration = duration,
        enteredBy = "test",
        fetchedAt = baseTs
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.treatmentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsert inserts new treatments`() = runTest {
        val treatments = listOf(
            treatment("a", 10, insulin = 2.0),
            treatment("b", 5, carbs = 30.0)
        )
        dao.upsert(treatments)

        val results = dao.allSince(0)
        assertEquals(2, results.size)
    }

    @Test
    fun `upsert replaces existing treatment by id`() = runTest {
        dao.upsert(listOf(treatment("a", 10, insulin = 2.0)))
        dao.upsert(listOf(treatment("a", 10, insulin = 5.0)))

        val results = dao.allSince(0)
        assertEquals(1, results.size)
        assertEquals(5.0, results[0].insulin!!, 0.01)
    }

    @Test
    fun `since returns flow sorted ascending`() = runTest {
        dao.upsert(listOf(
            treatment("c", 2, insulin = 1.0),
            treatment("a", 10, insulin = 3.0),
            treatment("b", 5, carbs = 30.0)
        ))

        val since = baseTs - 11 * 60_000L
        val results = dao.since(since).first()
        assertEquals(3, results.size)
        assertTrue("should be sorted ASC", results[0].createdAt < results[1].createdAt)
        assertEquals("a", results[0].id)
    }

    @Test
    fun `carbsInRange filters by carbs and time range`() = runTest {
        dao.upsert(listOf(
            treatment("a", 10, insulin = 2.0),               // no carbs
            treatment("b", 8, carbs = 30.0, insulin = 3.0),  // has carbs, in range
            treatment("c", 3, carbs = 0.0),                   // zero carbs
            treatment("d", 1, carbs = 15.0),                  // has carbs, in range
            treatment("e", 100, carbs = 50.0)                 // has carbs, out of range
        ))

        val start = baseTs - 15 * 60_000L
        val end = baseTs
        val results = dao.carbsInRange(start, end)
        assertEquals(2, results.size)
        assertEquals("b", results[0].id)
        assertEquals("d", results[1].id)
    }

    @Test
    fun `insulinSince filters insulin-only treatments`() = runTest {
        dao.upsert(listOf(
            treatment("a", 10, insulin = 2.0),
            treatment("b", 5, carbs = 30.0),
            treatment("c", 3, insulin = 1.5)
        ))

        val results = dao.insulinSince(0)
        assertEquals(2, results.size)
        assertEquals("a", results[0].id)
        assertEquals("c", results[1].id)
    }

    @Test
    fun `deleteOlderThan prunes old treatments`() = runTest {
        dao.upsert(listOf(
            treatment("a", 100, insulin = 2.0),
            treatment("b", 50, carbs = 30.0),
            treatment("c", 5, insulin = 1.0)
        ))

        dao.deleteOlderThan(baseTs - 60 * 60_000L)
        val results = dao.allSince(0)
        assertEquals(2, results.size)
    }

    @Test
    fun `latestFetchedAt returns null when table is empty`() = runTest {
        assertNull(dao.latestFetchedAt())
    }

    @Test
    fun `latestFetchedAt returns max fetchedAt value`() = runTest {
        val t1 = treatment("a", 10, insulin = 1.0).copy(fetchedAt = 1000L)
        val t2 = treatment("b", 5, insulin = 2.0).copy(fetchedAt = 3000L)
        val t3 = treatment("c", 1, insulin = 3.0).copy(fetchedAt = 2000L)
        dao.upsert(listOf(t1, t2, t3))

        assertEquals(3000L, dao.latestFetchedAt())
    }

    @Test
    fun `allSince returns descending order`() = runTest {
        dao.upsert(listOf(
            treatment("a", 10, insulin = 1.0),
            treatment("b", 5, insulin = 2.0),
            treatment("c", 1, insulin = 3.0)
        ))

        val results = dao.allSince(0)
        assertEquals(3, results.size)
        assertTrue("should be sorted DESC", results[0].createdAt > results[1].createdAt)
    }
}
