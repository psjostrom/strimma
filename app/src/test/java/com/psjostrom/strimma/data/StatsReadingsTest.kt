package com.psjostrom.strimma.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class StatsReadingsTest {

    private val minute = 60_000L

    private fun at(hour: Int, min: Int): Long =
        LocalDateTime.of(2026, 3, 11, hour, min).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `treated low is kept when its carbs were logged before the period starts`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java).allowMainThreadQueries().build()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        val statsReadings = StatsReadings(db.readingDao(), db.treatmentDao(), db.exerciseDao(), settings)
        try {
            // A fast fall to 55 at 02:45 and a rebound that climbs 4 mg/dL per minute past 03:00.
            val flat = List(15) { 120 }
            val fall = listOf(110, 95, 80, 65, 55)
            val rise = (1..45).map { minOf(55 + it * 4, 140) }
            val readings = (flat + fall + rise).mapIndexed { i, sgv ->
                GlucoseReading(ts = at(2, 26) + i * minute, sgv = sgv, direction = "Flat", delta = null)
            }
            db.readingDao().insertBatch(readings)
            // Hypo carbs logged at 02:29, 16 minutes before the nadir and 31 minutes before the period.
            val carbs = Treatment(
                id = "hypo", createdAt = at(2, 29), eventType = "Carb Correction", insulin = null,
                carbs = 15.0, basalRate = null, duration = null, enteredBy = null, fetchedAt = at(2, 29)
            )
            db.treatmentDao().upsert(listOf(carbs))

            val result = statsReadings.inRange(at(3, 0), at(3, 30))

            assertEquals(readings.count { it.ts in at(3, 0)..at(3, 30) }, result.size)
        } finally {
            db.close()
        }
    }
}
