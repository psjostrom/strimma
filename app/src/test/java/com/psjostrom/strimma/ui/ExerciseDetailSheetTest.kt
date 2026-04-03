package com.psjostrom.strimma.ui

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.MS_PER_MINUTE
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseDetailSheetTest {

    // --- timeLabelCount ---

    @Test
    fun `short range under 30 min gives 3 labels`() {
        assertEquals(3, timeLabelCount(15 * MS_PER_MINUTE))
        assertEquals(3, timeLabelCount(29 * MS_PER_MINUTE))
    }

    @Test
    fun `medium range 30 min to 2 hours gives 4 labels`() {
        assertEquals(4, timeLabelCount(30 * MS_PER_MINUTE))
        assertEquals(4, timeLabelCount(90 * MS_PER_MINUTE))
        assertEquals(4, timeLabelCount(119 * MS_PER_MINUTE))
    }

    @Test
    fun `long range over 2 hours gives 5 labels`() {
        assertEquals(5, timeLabelCount(120 * MS_PER_MINUTE))
        assertEquals(5, timeLabelCount(300 * MS_PER_MINUTE))
    }

    // --- Activity filter logic ---

    private fun reading(ts: Long, sgv: Int = 100) =
        GlucoseReading(ts = ts, sgv = sgv, direction = "Flat", delta = null)

    @Test
    fun `activity filter includes readings within session window`() {
        val startTime = 1000L
        val endTime = 5000L
        val readings = listOf(
            reading(500),   // before
            reading(1000),  // at start (inclusive)
            reading(3000),  // during
            reading(5000),  // at end (inclusive)
            reading(6000),  // after
        )
        val filtered = readings.filter { it.ts in startTime..endTime }
        assertEquals(3, filtered.size)
        assertEquals(listOf(1000L, 3000L, 5000L), filtered.map { it.ts })
    }

    @Test
    fun `activity filter returns empty when no readings in session`() {
        val readings = listOf(reading(100), reading(200))
        val filtered = readings.filter { it.ts in 500L..600L }
        assertEquals(0, filtered.size)
    }
}
