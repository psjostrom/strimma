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

    @Test
    fun `prediction connects seamlessly to last reading`() {
        val readings = listOf(
            reading(5, 7.0), reading(4, 6.5), reading(3, 6.2),
            reading(2, 6.1), reading(1, 6.0), reading(0, 6.3)
        )
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        val firstPoint = prediction.points.first()
        assertEquals(1, firstPoint.minuteOffset)
        assertTrue(
            "First prediction ${firstPoint.mmol} too far from anchor ${prediction.anchorMmol}",
            kotlin.math.abs(firstPoint.mmol - prediction.anchorMmol) < 0.3
        )
    }

    // --- Linear trend ---

    @Test
    fun `linear rising trend predicts higher values`() {
        // 0.1 mmol/min rise over 12 minutes
        val readings = (12 downTo 0).map { reading(it, 6.0 + (12 - it) * 0.1) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        // Last reading is 7.2, should continue rising (dampened, so less than +1.5)
        assertTrue(prediction.points.last().mmol > 7.2)
    }

    @Test
    fun `linear dropping trend predicts lower values`() {
        val readings = (12 downTo 0).map { reading(it, 8.0 - (12 - it) * 0.1) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        assertTrue(prediction.points.last().mmol < 6.8)
    }

    @Test
    fun `dampening reduces prediction vs pure linear extrapolation`() {
        // 0.1 mmol/min rise, pure linear would give 7.2 + 1.5 = 8.7 at t=15
        val readings = (12 downTo 0).map { reading(it, 6.0 + (12 - it) * 0.1) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        val at15 = prediction.points.last().mmol
        assertTrue("Dampened prediction ($at15) should be less than linear (8.7)", at15 < 8.7)
        assertTrue("Dampened prediction ($at15) should still rise above anchor (7.2)", at15 > 7.2)
    }

    // --- Threshold crossing ---

    @Test
    fun `detects low crossing when dropping toward bgLow`() {
        // Currently 5.0, dropping 0.1/min → should cross 4.0 in ~10 min
        val readings = (12 downTo 0).map { reading(it, 6.2 - (12 - it) * 0.1) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        assertNotNull(prediction.crossing)
        assertEquals(CrossingType.LOW, prediction.crossing!!.type)
        assertTrue(prediction.crossing!!.minutesUntil in 8..15)
    }

    @Test
    fun `detects high crossing when rising toward bgHigh`() {
        // Currently 9.0, rising 0.1/min → should cross 10.0 in ~10 min
        val readings = (12 downTo 0).map { reading(it, 7.8 + (12 - it) * 0.1) }
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        assertNotNull(prediction.crossing)
        assertEquals(CrossingType.HIGH, prediction.crossing!!.type)
        assertTrue(prediction.crossing!!.minutesUntil in 8..15)
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

    // --- V-recovery (the bug that prompted this rewrite) ---

    @Test
    fun `no high crossing during V-recovery from low`() {
        // Descent from 5.5 → 3.5, then sharp bounce to 4.2 (Libre 3, 1-min intervals)
        // Old quadratic fit would extrapolate the V-curvature and predict "High in 15"
        val descent = (12 downTo 5).map { reading(it, 5.5 - (12 - it) * 0.286) } // 5.5 → 3.5
        val recovery = listOf(
            reading(3, 3.6), reading(2, 3.8), reading(1, 4.0), reading(0, 4.2)
        )
        val prediction = PredictionComputer.compute(descent + recovery, 15, 3.9, 10.0)!!
        // Must NOT predict high crossing — we're at 4.2 rising moderately
        assertNull(
            "Should not predict high crossing during V-recovery, got: ${prediction.crossing}",
            prediction.crossing?.takeIf { it.type == CrossingType.HIGH }
        )
    }

    @Test
    fun `V-recovery prediction stays reasonable`() {
        // Same V-recovery scenario — prediction should be moderate, not parabolic
        val descent = (12 downTo 5).map { reading(it, 5.5 - (12 - it) * 0.286) }
        val recovery = listOf(
            reading(3, 3.6), reading(2, 3.8), reading(1, 4.0), reading(0, 4.2)
        )
        val prediction = PredictionComputer.compute(descent + recovery, 15, 3.9, 10.0)!!
        val at15 = prediction.points.last().mmol
        // Should predict somewhere reasonable (4-7 range), not shoot to 10+
        assertTrue("Prediction at 15 min ($at15) should be < 7.0", at15 < 7.0)
        assertTrue("Prediction at 15 min ($at15) should be > 4.0", at15 > 4.0)
    }

    // --- Velocity fitting ---

    @Test
    fun `weighted velocity captures slope from 2 points`() {
        val points = listOf(-5.0 to 6.0, 0.0 to 7.0)
        val velocity = PredictionComputer.fitWeightedVelocity(points)!!
        // Slope is 0.2 mmol/min (with weighting it shifts slightly but stays close)
        assertEquals(0.2, velocity, 0.05)
    }

    @Test
    fun `weighted velocity emphasizes recent trend`() {
        // Old stable readings at 6.0, then recent sharp rise to 7.0
        val stable = (12 downTo 4).map { -it.toDouble() to 6.0 }
        val rising = listOf(-3.0 to 6.3, -2.0 to 6.5, -1.0 to 6.8, 0.0 to 7.0)
        val velocity = PredictionComputer.fitWeightedVelocity(stable + rising)!!
        // Unweighted slope over 12 min ≈ 0.08 mmol/min.
        // Weighted should be noticeably higher, closer to the recent 4-min rate (~0.23).
        assertTrue("Velocity ($velocity) should be > 0.12 (emphasizing recent rise)", velocity > 0.12)
    }

    // --- Edge cases ---

    @Test
    fun `ignores readings older than 12 minutes`() {
        val old = (30 downTo 13).map { reading(it, 8.0) }
        val recent = (12 downTo 0).map { reading(it, 8.0 - (12 - it) * 0.1) }
        val prediction = PredictionComputer.compute(old + recent, 15, 4.0, 10.0)!!
        assertTrue(prediction.points.last().mmol < 6.8)
    }

    @Test
    fun `works with 5 minute interval data`() {
        val readings = listOf(reading(10, 6.0), reading(5, 6.5), reading(0, 7.0))
        val prediction = PredictionComputer.compute(readings, 15, 4.0, 10.0)!!
        assertEquals(15, prediction.points.size)
        assertTrue(prediction.points[4].mmol > 7.0)
    }

    @Test
    fun `works with 1 minute interval data`() {
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
        assertTrue(prediction.crossing!!.minutesUntil in 1..5)
    }

    @Test
    fun `weighting makes prediction follow recent trend change`() {
        // 8 min of descent (7.2 → 5.5) then 4 min of uptick (5.5 → 5.9)
        val descending = (12 downTo 5).map { reading(it, 7.2 - (12 - it) * 0.243) }
        val ascending = listOf(
            reading(3, 5.6), reading(2, 5.7), reading(1, 5.8), reading(0, 5.9)
        )

        val prediction = PredictionComputer.compute(descending + ascending, 15, 4.0, 10.0)!!
        val last = prediction.points.last()
        assertTrue(
            "Prediction at 15 min (${last.mmol}) should stay above 5.0 with weighting",
            last.mmol > 5.0
        )
    }

    @Test
    fun `prediction values are clamped to valid range`() {
        val readings = (12 downTo 0).map { reading(it, 5.0 - (12 - it) * 0.5) }
        val prediction = PredictionComputer.compute(readings, 30, 4.0, 10.0)!!
        for (pt in prediction.points) {
            assertTrue("mmol ${pt.mmol} below floor", pt.mmol >= 1.0)
        }
    }
}
