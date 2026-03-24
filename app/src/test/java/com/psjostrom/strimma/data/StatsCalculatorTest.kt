package com.psjostrom.strimma.data

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class StatsCalculatorTest {

    private val baseTs = 1_700_000_000_000L

    private fun reading(minutesAgo: Int, sgvMgdl: Int): GlucoseReading =
        GlucoseReading(
            ts = baseTs - minutesAgo * 60_000L,
            sgv = sgvMgdl,
            direction = "NONE", delta = null, pushed = 1
        )

    // --- GVP: edge cases ---

    @Test
    fun `gvp returns null for fewer than 3 readings`() {
        val readings = listOf(reading(1, 100), reading(0, 105))
        assertNull(StatsCalculator.computeGvp(readings))
    }

    @Test
    fun `gvp returns null for empty list`() {
        assertNull(StatsCalculator.computeGvp(emptyList()))
    }

    @Test
    fun `gvp is zero for perfectly flat glucose`() {
        val readings = (10 downTo 0).map { reading(it, 100) }
        val gvp = StatsCalculator.computeGvp(readings)!!
        assertEquals(0.0, gvp, 0.001)
    }

    // --- GVP: known values ---

    @Test
    fun `gvp for steady rise`() {
        // 3 readings, 5 min apart, rising 5 mg/dL per interval
        val readings = listOf(reading(10, 100), reading(5, 105), reading(0, 110))
        val gvp = StatsCalculator.computeGvp(readings)!!

        // Each segment: sqrt(5² + 5²) = sqrt(50) ≈ 7.071
        // Trace = 2 × 7.071 = 14.142, Ideal = 10 min
        // GVP = (14.142/10 - 1) × 100 = 41.42%
        val expected = (2 * sqrt(50.0) / 10.0 - 1.0) * 100.0
        assertEquals(expected, gvp, 0.01)
    }

    @Test
    fun `gvp for oscillating glucose`() {
        // Sawtooth: 100 → 110 → 100 → 110 (5-min intervals)
        val readings = listOf(
            reading(15, 100), reading(10, 110),
            reading(5, 100), reading(0, 110)
        )
        val gvp = StatsCalculator.computeGvp(readings)!!

        // Each segment: sqrt(5² + 10²) = sqrt(125) ≈ 11.18
        // Trace = 3 × 11.18 = 33.54, Ideal = 15 min
        // GVP = (33.54/15 - 1) × 100 = 123.6%
        val expected = (3 * sqrt(125.0) / 15.0 - 1.0) * 100.0
        assertEquals(expected, gvp, 0.01)
    }

    // --- GVP: gap handling ---

    @Test
    fun `gvp skips segments with gaps over 15 minutes`() {
        // Two clusters with a 20-min gap between them
        val readings = listOf(
            reading(30, 100), reading(25, 105),  // cluster 1
            reading(5, 120), reading(0, 125)     // cluster 2 (20-min gap from cluster 1)
        )
        val gvp = StatsCalculator.computeGvp(readings)!!

        // Only 2 valid segments (within each cluster), gap segment excluded
        val seg = sqrt(25.0 + 25.0) // sqrt(5² + 5²)
        val idealMinutes = 10.0      // 5 + 5 (gap excluded)
        val expected = (2 * seg / idealMinutes - 1.0) * 100.0
        assertEquals(expected, gvp, 0.01)
    }

    // --- GVP: sensor interval independence ---

    @Test
    fun `gvp is consistent across sampling intervals for linear rise`() {
        // Same linear rise (100 → 130 over 30 min) at 1-min vs 5-min intervals
        val oneMin = (30 downTo 0).map { reading(it, 100 + (30 - it)) }
        val fiveMin = (6 downTo 0).map { reading(it * 5, 100 + (6 - it) * 5) }

        val gvp1 = StatsCalculator.computeGvp(oneMin)!!
        val gvp5 = StatsCalculator.computeGvp(fiveMin)!!

        assertEquals(gvp5, gvp1, 0.01)
    }

    // --- Full compute: GVP integrated ---

    @Test
    fun `compute includes gvp in stats`() {
        val readings = (10 downTo 0).map { reading(it, 100) }
        val stats = StatsCalculator.compute(readings, 70.0, 180.0, "test")!!
        assertNotNull(stats.gvp)
        assertEquals(0.0, stats.gvp!!, 0.001)
    }
}
