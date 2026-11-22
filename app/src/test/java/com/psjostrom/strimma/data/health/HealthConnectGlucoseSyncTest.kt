package com.psjostrom.strimma.data.health

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.StrimmaDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthConnectGlucoseSyncTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun database(): StrimmaDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun reading(ts: Long) = GlucoseReading(ts = ts, sgv = 120, direction = "Flat", delta = null)

    /** Replaces Health Connect in the test: keeps every batch it is given and stores it when [succeeds]. */
    private class FakeHealthConnect(var succeeds: Boolean = true) {
        val batches = mutableListOf<List<Long>>()
        suspend fun write(readings: List<GlucoseReading>): List<GlucoseReading> {
            batches.add(readings.map { it.ts })
            return if (succeeds) readings else emptyList()
        }
    }

    @Test
    fun `writes every reading Health Connect does not have, once`() = runTest {
        val db = database()
        val hc = FakeHealthConnect()
        db.readingDao().insert(reading(now - 5 * minute))
        writeUnwrittenToHealthConnect(db.readingDao(), now, hc::write)
        // A follower catch-up stores readings older than one already written.
        db.readingDao().insertBatch(listOf(reading(now - 30 * minute), reading(now - 20 * minute)))

        writeUnwrittenToHealthConnect(db.readingDao(), now, hc::write)
        writeUnwrittenToHealthConnect(db.readingDao(), now, hc::write)

        assertEquals(
            listOf(listOf(now - 5 * minute), listOf(now - 30 * minute, now - 20 * minute)),
            hc.batches
        )
        db.close()
    }

    @Test
    fun `a failed write is retried on the next call`() = runTest {
        val db = database()
        val hc = FakeHealthConnect(succeeds = false)
        db.readingDao().insert(reading(now - 5 * minute))

        writeUnwrittenToHealthConnect(db.readingDao(), now, hc::write)
        hc.succeeds = true
        writeUnwrittenToHealthConnect(db.readingDao(), now, hc::write)
        writeUnwrittenToHealthConnect(db.readingDao(), now, hc::write)

        assertEquals(2, hc.batches.size)
        db.close()
    }

    @Test
    fun `a backlog drains one batch of at most 1000 per call`() = runTest {
        val db = database()
        val hc = FakeHealthConnect()
        db.readingDao().insertBatch((1..2500).map { reading(now - it * minute) })

        repeat(4) { writeUnwrittenToHealthConnect(db.readingDao(), now, hc::write) }

        assertEquals(listOf(1000, 1000, 500), hc.batches.map { it.size })
        db.close()
    }

    @Test
    fun `readings older than the catch-up window are not written`() = runTest {
        val db = database()
        val hc = FakeHealthConnect()
        db.readingDao().insertBatch(listOf(reading(now - HC_CATCH_UP_MS - minute), reading(now - minute)))

        writeUnwrittenToHealthConnect(db.readingDao(), now, hc::write)

        assertEquals(listOf(listOf(now - minute)), hc.batches)
        db.close()
    }
}
