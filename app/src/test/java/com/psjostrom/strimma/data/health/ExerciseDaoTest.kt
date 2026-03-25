package com.psjostrom.strimma.data.health

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
class ExerciseDaoTest {

    private lateinit var db: com.psjostrom.strimma.data.StrimmaDatabase
    private lateinit var dao: ExerciseDao

    private val baseTime = 1_700_000_000_000L

    private fun session(
        id: String = "s1",
        type: Int = 8, // RUNNING
        startTime: Long = baseTime,
        endTime: Long = baseTime + 3_600_000L,
        title: String? = "Morning run",
        totalSteps: Int? = 6000,
        activeCalories: Double? = 350.0
    ) = StoredExerciseSession(id, type, startTime, endTime, title, totalSteps, activeCalories)

    private fun hrSample(sessionId: String, minutesIn: Int, bpm: Int) =
        HeartRateSample(sessionId = sessionId, time = baseTime + minutesIn * 60_000L, bpm = bpm)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, com.psjostrom.strimma.data.StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.exerciseDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and retrieve session`() = runTest {
        val s = session()
        dao.upsertSession(s)
        val result = dao.getSessionById("s1")
        assertNotNull(result)
        assertEquals("Morning run", result!!.title)
        assertEquals(8, result.type)
    }

    @Test
    fun `upsert replaces existing session`() = runTest {
        dao.upsertSession(session(title = "Original"))
        dao.upsertSession(session(title = "Updated"))
        val result = dao.getSessionById("s1")
        assertEquals("Updated", result!!.title)
    }

    @Test
    fun `getSessionsInRange returns overlapping sessions`() = runTest {
        // Session 1: baseTime to baseTime + 1h
        dao.upsertSession(session(id = "s1", startTime = baseTime, endTime = baseTime + 3_600_000))
        // Session 2: baseTime + 2h to baseTime + 3h
        dao.upsertSession(
            session(
                id = "s2",
                startTime = baseTime + 7_200_000,
                endTime = baseTime + 10_800_000
            )
        )
        // Session 3: baseTime + 5h to baseTime + 6h (outside range)
        dao.upsertSession(
            session(
                id = "s3",
                startTime = baseTime + 18_000_000,
                endTime = baseTime + 21_600_000
            )
        )

        // Query range: baseTime to baseTime + 4h — should get s1 and s2
        val results = dao.getSessionsInRange(baseTime, baseTime + 14_400_000)
        assertEquals(2, results.size)
        assertEquals("s1", results[0].id)
        assertEquals("s2", results[1].id)
    }

    @Test
    fun `upsertSessionWithHeartRate replaces HR samples`() = runTest {
        val s = session()
        val hr1 = listOf(hrSample("s1", 0, 120), hrSample("s1", 5, 140))
        dao.upsertSessionWithHeartRate(s, hr1)

        var samples = dao.getHeartRateForSession("s1")
        assertEquals(2, samples.size)
        assertEquals(120, samples[0].bpm)

        // Upsert again with different HR — old samples should be gone
        val hr2 = listOf(hrSample("s1", 0, 130), hrSample("s1", 5, 150), hrSample("s1", 10, 160))
        dao.upsertSessionWithHeartRate(s, hr2)

        samples = dao.getHeartRateForSession("s1")
        assertEquals(3, samples.size)
        assertEquals(130, samples[0].bpm)
        assertEquals(160, samples[2].bpm)
    }

    @Test
    fun `CASCADE delete removes HR samples when session deleted`() = runTest {
        dao.upsertSessionWithHeartRate(
            session(),
            listOf(hrSample("s1", 0, 120), hrSample("s1", 5, 140))
        )

        // Delete via age cutoff (endTime < cutoff)
        dao.deleteSessionsOlderThan(baseTime + 7_200_000) // cutoff after session ends
        val samples = dao.getHeartRateForSession("s1")
        assertEquals(0, samples.size)
    }

    @Test
    fun `deleteSessionsOlderThan only deletes old sessions`() = runTest {
        dao.upsertSession(session(id = "old", endTime = baseTime))
        dao.upsertSession(session(id = "new", endTime = baseTime + 86_400_000))

        dao.deleteSessionsOlderThan(baseTime + 1) // cutoff just after "old" ends
        assertNull(dao.getSessionById("old"))
        assertNotNull(dao.getSessionById("new"))
    }

    @Test
    fun `getAllSessions returns Flow sorted by startTime DESC`() = runTest {
        dao.upsertSession(session(id = "s1", startTime = baseTime))
        dao.upsertSession(session(id = "s2", startTime = baseTime + 86_400_000))

        val sessions = dao.getAllSessions().first()
        assertEquals(2, sessions.size)
        assertEquals("s2", sessions[0].id) // most recent first
        assertEquals("s1", sessions[1].id)
    }

    @Test
    fun `getAllSessions emits empty list when DB is empty`() = runTest {
        val sessions = dao.getAllSessions().first()
        assertTrue(sessions.isEmpty())
    }
}
