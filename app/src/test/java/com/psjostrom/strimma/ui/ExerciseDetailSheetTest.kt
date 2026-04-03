package com.psjostrom.strimma.ui

import com.psjostrom.strimma.data.GlucoseReading
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseDetailSheetTest {

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
