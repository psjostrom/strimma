package com.psjostrom.strimma.graph

import com.psjostrom.strimma.data.GlucoseReading
import org.junit.Assert.*
import org.junit.Test

class PredictionComputerTest {

    private val baseTs = 1_700_000_000_000L

    private fun reading(minutesAgo: Int, mmol: Double) = GlucoseReading(
        ts = baseTs - minutesAgo * 60_000L,
        sgv = (mmol * 18.0182).toInt(),
        mmol = mmol,
        direction = "NONE",
        deltaMmol = null,
        pushed = 1
    )

    // --- Basic prediction ---

    @Test
    fun `returns null with fewer than 2 readings`() {
        val result = PredictionComputer.compute(listOf(reading(0, 6.0)), 15, 4.0, 10.0)
        assertNull(result)
    }

    @Test
    fun `returns null with empty readings`() {
        assertNull(PredictionComputer.compute(emptyList(), 15, 4.0, 10.0))
    }

    @Test
    fun `produces prediction points for stable glucose`() {
        val readings = (12 downTo 0).map { reading(it, 6.0) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        assertEquals(15, prediction.points.size)
        // Stable glucose should predict ~6.0 for all points
        for (pt in prediction.points) {
            assertEquals(6.0, pt.mmol, 0.2)
        }
    }

    @Test
    fun `anchor is the latest reading`() {
        val readings = (5 downTo 0).map { reading(it, 6.0) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        assertEquals(baseTs, prediction.anchorTs)
        assertEquals(6.0, prediction.anchorMmol, 0.001)
    }

    // --- Linear trend ---

    @Test
    fun `linear rising trend predicts higher values`() {
        // 0.1 mmol/min rise over 12 minutes
        val readings = (12 downTo 0).map { reading(it, 6.0 + (12 - it) * 0.1) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        // Last reading is 7.2, should continue rising
        assertTrue(prediction.points.last().mmol > 7.2)
    }

    @Test
    fun `linear dropping trend predicts lower values`() {
        val readings = (12 downTo 0).map { reading(it, 8.0 - (12 - it) * 0.1) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        assertTrue(prediction.points.last().mmol < 6.8)
    }

    // --- Threshold crossing ---

    @Test
    fun `detects low crossing when dropping toward bgLow`() {
        // Currently 5.0, dropping 0.1/min → should cross 4.0 in ~10 min
        val readings = (12 downTo 0).map { reading(it, 6.2 - (12 - it) * 0.1) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        assertNotNull(prediction.crossing)
        assertEquals(CrossingType.LOW, prediction.crossing!!.type)
        assertTrue(prediction.crossing!!.minutesUntil in 8..12)
    }

    @Test
    fun `detects high crossing when rising toward bgHigh`() {
        // Currently 9.0, rising 0.1/min → should cross 10.0 in ~10 min
        val readings = (12 downTo 0).map { reading(it, 7.8 + (12 - it) * 0.1) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        assertNotNull(prediction.crossing)
        assertEquals(CrossingType.HIGH, prediction.crossing!!.type)
        assertTrue(prediction.crossing!!.minutesUntil in 8..12)
    }

    @Test
    fun `no crossing when stable in range`() {
        val readings = (12 downTo 0).map { reading(it, 6.0) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        assertNull(prediction.crossing)
    }

    @Test
    fun `no crossing when already below bgLow`() {
        val readings = (12 downTo 0).map { reading(it, 3.5 - (12 - it) * 0.05) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        // Already below range, don't show "low in X"
        assertNull(prediction.crossing)
    }

    @Test
    fun `no crossing when already above bgHigh`() {
        val readings = (12 downTo 0).map { reading(it, 11.0 + (12 - it) * 0.05) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        assertNull(prediction.crossing)
    }

    @Test
    fun `no crossing when drop is too slow to reach threshold`() {
        // At 7.0, dropping 0.05/min → needs 60 min to reach 4.0, horizon is 15
        val readings = (12 downTo 0).map { reading(it, 7.6 - (12 - it) * 0.05) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        assertNull(prediction.crossing)
    }

    // --- Model fitting ---

    @Test
    fun `with 2 points uses linear model`() {
        val points = listOf(0.0 to 6.0, 5.0 to 7.0)
        val model = PredictionComputer.fitBestModel(points)!!
        // Linear: should predict 8.0 at t=10
        assertEquals(8.0, model(10.0), 0.01)
    }

    @Test
    fun `quadratic fit captures deceleration`() {
        // Glucose rising but decelerating: 6.0, 6.8, 7.4, 7.8, 8.0
        // (rate slowing down: 0.8, 0.6, 0.4, 0.2 per interval)
        val points = listOf(
            0.0 to 6.0, 3.0 to 6.8, 6.0 to 7.4, 9.0 to 7.8, 12.0 to 8.0
        )
        val model = PredictionComputer.fitBestModel(points)!!
        // With deceleration, prediction at t=15 should be less than linear (8.0 + 0.2*3 = 8.6)
        val predicted = model(15.0)
        assertTrue("Quadratic should predict lower than linear: $predicted", predicted < 8.5)
    }

    @Test
    fun `prediction values are clamped to valid range`() {
        // Rapidly dropping glucose
        val readings = (12 downTo 0).map { reading(it, 5.0 - (12 - it) * 0.5) }
        val prediction = PredictionComputer.compute(readings, 30, 4.0, 10.0)!!
        // All points should be >= 1.0 (floor)
        for (pt in prediction.points) {
            assertTrue("mmol ${pt.mmol} below floor", pt.mmol >= 1.0)
        }
    }

    // --- Edge cases ---

    @Test
    fun `ignores readings older than 12 minutes`() {
        // Old stable readings + recent drop
        val old = (30 downTo 13).map { reading(it, 8.0) }
        val recent = (12 downTo 0).map { reading(it, 8.0 - (12 - it) * 0.1) }
        val prediction = PredictionComputer.compute(old + recent, 15, 4.0, 10.0)!!
        // Should follow the recent downward trend, not be pulled toward old stable readings
        assertTrue(prediction.points.last().mmol < 6.8)
    }

    @Test
    fun `works with 5 minute interval data`() {
        // Dexcom-like: readings every 5 min, 3 points in 12 min window
        val readings = listOf(reading(10, 6.0), reading(5, 6.5), reading(0, 7.0))
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        assertEquals(15, prediction.points.size)
        // Should continue rising
        assertTrue(prediction.points[4].mmol > 7.0)
    }

    @Test
    fun `works with 1 minute interval data`() {
        // Libre 3-like: readings every 1 min
        val readings = (12 downTo 0).map { reading(it, 6.0 + (12 - it) * 0.05) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        assertEquals(15, prediction.points.size)
    }

    @Test
    fun `crossing minutesUntil is relative to anchor`() {
        // Dropping 0.2/min from 4.5 → should cross 4.0 in ~2-3 min
        val readings = (12 downTo 0).map { reading(it, 6.9 - (12 - it) * 0.2) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        assertNotNull(prediction.crossing)
        assertEquals(CrossingType.LOW, prediction.crossing!!.type)
        assertTrue(prediction.crossing!!.minutesUntil in 1..4)
    }
}
