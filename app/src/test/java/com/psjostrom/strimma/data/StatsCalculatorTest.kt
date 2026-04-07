package com.psjostrom.strimma.data

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class StatsCalculatorTest {

    private fun reading(sgv: Int, minutesAgo: Int = 0): GlucoseReading {
        val ts = 1_700_000_000_000L - minutesAgo * 60_000L
        return GlucoseReading(ts = ts, sgv = sgv, direction = "Flat", delta = null)
    }

    @Test
    fun `compute returns null for empty readings`() {
        assertNull(StatsCalculator.compute(emptyList(), 70.0, 180.0, "24h"))
    }

    @Test
    fun `compute returns correct TIR for all-in-range readings`() {
        val readings = listOf(reading(100), reading(120), reading(150), reading(170))
        val stats = StatsCalculator.compute(readings, 70.0, 180.0, "24h")!!

        assertEquals(100.0, stats.tirPercent, 0.01)
        assertEquals(0.0, stats.belowPercent, 0.01)
        assertEquals(0.0, stats.abovePercent, 0.01)
    }

    @Test
    fun `compute returns correct TIR for mixed readings`() {
        val readings = listOf(
            reading(50),   // below
            reading(100),  // in range
            reading(120),  // in range
            reading(200)   // above
        )
        val stats = StatsCalculator.compute(readings, 70.0, 180.0, "24h")!!

        assertEquals(50.0, stats.tirPercent, 0.01)
        assertEquals(25.0, stats.belowPercent, 0.01)
        assertEquals(25.0, stats.abovePercent, 0.01)
    }

    @Test
    fun `compute returns correct average`() {
        val readings = listOf(reading(100), reading(200))
        val stats = StatsCalculator.compute(readings, 70.0, 180.0, "7d")!!

        assertEquals(150.0, stats.averageMgdl, 0.01)
    }

    @Test
    fun `compute returns correct standard deviation`() {
        val readings = listOf(reading(100), reading(200))
        val stats = StatsCalculator.compute(readings, 70.0, 180.0, "7d")!!

        // Population std dev: sqrt(((100-150)^2 + (200-150)^2) / 2) = sqrt(2500) = 50
        assertEquals(50.0, stats.stdDevMgdl, 0.01)
    }

    @Test
    fun `compute returns zero CV for identical readings`() {
        val readings = listOf(reading(120), reading(120), reading(120))
        val stats = StatsCalculator.compute(readings, 70.0, 180.0, "24h")!!

        assertEquals(0.0, stats.cv, 0.01)
        assertEquals(0.0, stats.stdDevMgdl, 0.01)
    }

    @Test
    fun `compute returns correct CV`() {
        val readings = listOf(reading(100), reading(200))
        val stats = StatsCalculator.compute(readings, 70.0, 180.0, "7d")!!

        // CV = (stdDev / mean) * 100 = (50 / 150) * 100 = 33.33%
        assertEquals(33.33, stats.cv, 0.01)
    }

    @Test
    fun `compute returns correct GMI`() {
        val readings = listOf(reading(154)) // 154 mg/dL → GMI ≈ 7.0%
        val stats = StatsCalculator.compute(readings, 70.0, 180.0, "14d")!!

        // GMI = 3.31 + 0.02392 * 154 = 3.31 + 3.684 = 6.994
        assertEquals(GmiCalculator.compute(154.0), stats.gmi, 0.001)
    }

    @Test
    fun `compute returns correct count`() {
        val readings = listOf(reading(100), reading(120), reading(140))
        val stats = StatsCalculator.compute(readings, 70.0, 180.0, "24h")!!

        assertEquals(3, stats.count)
    }

    @Test
    fun `compute preserves period label`() {
        val readings = listOf(reading(120))
        val stats = StatsCalculator.compute(readings, 70.0, 180.0, "30d")!!

        assertEquals("30d", stats.periodLabel)
    }

    @Test
    fun `boundary values are in range`() {
        val readings = listOf(reading(70), reading(180)) // exactly at thresholds
        val stats = StatsCalculator.compute(readings, 70.0, 180.0, "24h")!!

        assertEquals(100.0, stats.tirPercent, 0.01)
    }

    @Test
    fun `single reading produces valid stats`() {
        val stats = StatsCalculator.compute(listOf(reading(120)), 70.0, 180.0, "24h")!!

        assertEquals(1, stats.count)
        assertEquals(120.0, stats.averageMgdl, 0.01)
        assertEquals(0.0, stats.stdDevMgdl, 0.01)
        assertEquals(0.0, stats.cv, 0.01)
        assertEquals(100.0, stats.tirPercent, 0.01)
    }
}
