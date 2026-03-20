package com.psjostrom.strimma.graph

import com.psjostrom.strimma.data.GlucoseReading
import org.junit.Assert.*
import org.junit.Test

class PredictionComputerTest {

    private val baseTs = 1_700_000_000_000L

    // All values in mg/dL. Thresholds: bgLow=72 (4.0 mmol), bgHigh=180 (10.0 mmol)
    private fun reading(minutesAgo: Int, sgv: Int) = GlucoseReading(
        ts = baseTs - minutesAgo * 60_000L,
        sgv = sgv,
        direction = "NONE",
        delta = null,
        pushed = 1
    )

    // --- Basic prediction ---

    @Test
    fun `returns null with fewer than 2 readings`() {
        val result = PredictionComputer.compute(listOf(reading(0, 108)), 15, 72.0, 180.0)
        assertNull(result)
    }

    @Test
    fun `returns null with empty readings`() {
        assertNull(PredictionComputer.compute(emptyList(), 15, 72.0, 180.0))
    }

    @Test
    fun `produces prediction points for stable glucose`() {
        val readings = (12 downTo 0).map { reading(it, 108) }
        val prediction = PredictionComputer.compute(readings, 15, 72.0, 180.0)!!
        assertEquals(15, prediction.points.size)
        for (pt in prediction.points) {
            assertEquals(108.0, pt.mgdl, 4.0)
        }
    }

    @Test
    fun `anchor is the latest reading`() {
        val readings = (5 downTo 0).map { reading(it, 108) }
        val prediction = PredictionComputer.compute(readings, 15, 72.0, 180.0)!!
        assertEquals(baseTs, prediction.anchorTs)
        assertEquals(108.0, prediction.anchorMgdl, 0.1)
    }

    @Test
    fun `prediction connects seamlessly to last reading`() {
        val readings = listOf(
            reading(5, 126), reading(4, 117), reading(3, 112),
            reading(2, 110), reading(1, 108), reading(0, 113)
        )
        val prediction = PredictionComputer.compute(readings, 15, 72.0, 180.0)!!
        val firstPoint = prediction.points.first()
        assertEquals(1, firstPoint.minuteOffset)
        assertTrue(
            "First prediction ${firstPoint.mgdl} too far from anchor ${prediction.anchorMgdl}",
            kotlin.math.abs(firstPoint.mgdl - prediction.anchorMgdl) < 5.0
        )
    }

    // --- Linear trend ---

    @Test
    fun `linear rising trend predicts higher values`() {
        // ~1.8 mg/dL/min rise over 12 minutes
        val readings = (12 downTo 0).map { reading(it, 108 + (12 - it) * 2) }
        val prediction = PredictionComputer.compute(readings, 15, 72.0, 180.0)!!
        // Last reading is 132, should continue rising (dampened)
        assertTrue(prediction.points.last().mgdl > 132.0)
    }

    @Test
    fun `linear dropping trend predicts lower values`() {
        val readings = (12 downTo 0).map { reading(it, 144 - (12 - it) * 2) }
        val prediction = PredictionComputer.compute(readings, 15, 72.0, 180.0)!!
        assertTrue(prediction.points.last().mgdl < 120.0)
    }

    @Test
    fun `dampening reduces prediction vs pure linear extrapolation`() {
        // ~1.8 mg/dL/min rise, pure linear would give 132 + 27 = 159 at t=15
        val readings = (12 downTo 0).map { reading(it, 108 + (12 - it) * 2) }
        val prediction = PredictionComputer.compute(readings, 15, 72.0, 180.0)!!
        val at15 = prediction.points.last().mgdl
        assertTrue("Dampened prediction ($at15) should be less than linear (159)", at15 < 159.0)
        assertTrue("Dampened prediction ($at15) should still rise above anchor (132)", at15 > 132.0)
    }

    // --- Threshold crossing ---

    @Test
    fun `detects low crossing when dropping toward bgLow`() {
        // Currently ~90, dropping ~1.8 mg/dL/min → should cross 72 in ~10 min
        val readings = (12 downTo 0).map { reading(it, 112 - (12 - it) * 2) }
        val prediction = PredictionComputer.compute(readings, 15, 72.0, 180.0)!!
        assertNotNull(prediction.crossing)
        assertEquals(CrossingType.LOW, prediction.crossing!!.type)
        assertTrue(prediction.crossing!!.minutesUntil in 8..15)
    }

    @Test
    fun `detects high crossing when rising toward bgHigh`() {
        // Currently ~162, rising ~1.8 mg/dL/min → should cross 180 in ~10 min
        val readings = (12 downTo 0).map { reading(it, 140 + (12 - it) * 2) }
        val prediction = PredictionComputer.compute(readings, 15, 72.0, 180.0)!!
        assertNotNull(prediction.crossing)
        assertEquals(CrossingType.HIGH, prediction.crossing!!.type)
        assertTrue(prediction.crossing!!.minutesUntil in 8..15)
    }

    @Test
    fun `no crossing when stable in range`() {
        val readings = (12 downTo 0).map { reading(it, 108) }
        val prediction = PredictionComputer.compute(readings, 15, 72.0, 180.0)!!
        assertNull(prediction.crossing)
    }

    @Test
    fun `no crossing when already below bgLow`() {
        val readings = (12 downTo 0).map { reading(it, 63 - (12 - it)) }
        val prediction = PredictionComputer.compute(readings, 15, 72.0, 180.0)!!
        assertNull(prediction.crossing)
    }

    @Test
    fun `no crossing when already above bgHigh`() {
        val readings = (12 downTo 0).map { reading(it, 198 + (12 - it)) }
        val prediction = PredictionComputer.compute(readings, 15, 72.0, 180.0)!!
        assertNull(prediction.crossing)
    }

    @Test
    fun `no crossing when drop is too slow to reach threshold`() {
        // At 126, dropping ~0.9 mg/dL/min → needs 60 min to reach 72, horizon is 15
        val readings = (12 downTo 0).map { reading(it, 137 - (12 - it)) }
        val prediction = PredictionComputer.compute(readings, 15, 72.0, 180.0)!!
        assertNull(prediction.crossing)
    }

    // --- V-recovery (the bug that prompted this rewrite) ---

    @Test
    fun `no high crossing during V-recovery from low`() {
        // Descent from 99 → 63, then sharp bounce to 76 (Libre 3, 1-min intervals)
        val descent = (12 downTo 5).map { reading(it, 99 - (12 - it) * 5) }
        val recovery = listOf(
            reading(3, 65), reading(2, 68), reading(1, 72), reading(0, 76)
        )
        val prediction = PredictionComputer.compute(descent + recovery, 15, 70.0, 180.0)!!
        assertNull(
            "Should not predict high crossing during V-recovery, got: ${prediction.crossing}",
            prediction.crossing?.takeIf { it.type == CrossingType.HIGH }
        )
    }

    @Test
    fun `V-recovery prediction stays reasonable`() {
        val descent = (12 downTo 5).map { reading(it, 99 - (12 - it) * 5) }
        val recovery = listOf(
            reading(3, 65), reading(2, 68), reading(1, 72), reading(0, 76)
        )
        val prediction = PredictionComputer.compute(descent + recovery, 15, 70.0, 180.0)!!
        val at15 = prediction.points.last().mgdl
        assertTrue("Prediction at 15 min ($at15) should be < 126", at15 < 126.0)
        assertTrue("Prediction at 15 min ($at15) should be > 72", at15 > 72.0)
    }

    // --- Velocity fitting ---

    @Test
    fun `weighted velocity captures slope from 2 points`() {
        // 5 min apart, 18 mg/dL rise → 3.6 mg/dL/min
        val points = listOf(-5.0 to 108.0, 0.0 to 126.0)
        val velocity = PredictionComputer.fitWeightedVelocity(points)!!
        assertEquals(3.6, velocity, 0.9)
    }

    @Test
    fun `weighted velocity emphasizes recent trend`() {
        // Old stable at 108, then recent sharp rise to 126
        val stable = (12 downTo 4).map { -it.toDouble() to 108.0 }
        val rising = listOf(-3.0 to 113.0, -2.0 to 117.0, -1.0 to 122.0, 0.0 to 126.0)
        val velocity = PredictionComputer.fitWeightedVelocity(stable + rising)!!
        // Unweighted slope over 12 min ≈ 1.5 mg/dL/min.
        // Weighted should be noticeably higher, closer to recent 4-min rate (~4.3).
        assertTrue("Velocity ($velocity) should be > 2.0 (emphasizing recent rise)", velocity > 2.0)
    }

    // --- Edge cases ---

    @Test
    fun `ignores readings older than 12 minutes`() {
        val old = (30 downTo 13).map { reading(it, 144) }
        val recent = (12 downTo 0).map { reading(it, 144 - (12 - it) * 2) }
        val prediction = PredictionComputer.compute(old + recent, 15, 72.0, 180.0)!!
        assertTrue(prediction.points.last().mgdl < 120.0)
    }

    @Test
    fun `works with 5 minute interval data`() {
        val readings = listOf(reading(10, 108), reading(5, 117), reading(0, 126))
        val prediction = PredictionComputer.compute(readings, 15, 72.0, 180.0)!!
        assertEquals(15, prediction.points.size)
        assertTrue(prediction.points[4].mgdl > 126.0)
    }

    @Test
    fun `works with 1 minute interval data`() {
        val readings = (12 downTo 0).map { reading(it, 108 + (12 - it)) }
        val prediction = PredictionComputer.compute(readings, 15, 72.0, 180.0)!!
        assertEquals(15, prediction.points.size)
    }

    @Test
    fun `crossing minutesUntil is relative to anchor`() {
        // Dropping ~3.6 mg/dL/min from 81 → should cross 72 in ~2-3 min
        val readings = (12 downTo 0).map { reading(it, 124 - (12 - it) * 4) }
        val prediction = PredictionComputer.compute(readings, 15, 72.0, 180.0)!!
        assertNotNull(prediction.crossing)
        assertEquals(CrossingType.LOW, prediction.crossing!!.type)
        assertTrue(prediction.crossing!!.minutesUntil in 1..5)
    }

    @Test
    fun `weighting makes prediction follow recent trend change`() {
        // 8 min of descent (130 → 99) then 4 min of uptick (99 → 106)
        val descending = (12 downTo 5).map { reading(it, 130 - (12 - it) * 4) }
        val ascending = listOf(
            reading(3, 101), reading(2, 103), reading(1, 104), reading(0, 106)
        )

        val prediction = PredictionComputer.compute(descending + ascending, 15, 72.0, 180.0)!!
        val last = prediction.points.last()
        assertTrue(
            "Prediction at 15 min (${last.mgdl}) should stay above 90 with weighting",
            last.mgdl > 90.0
        )
    }

    @Test
    fun `prediction values are clamped to valid range`() {
        // Drop ~7 mg/dL/min (under MAX_VELOCITY=9) from 90
        val readings = (12 downTo 0).map { reading(it, 174 - (12 - it) * 7) }
        val prediction = PredictionComputer.compute(readings, 30, 72.0, 180.0)!!
        for (pt in prediction.points) {
            assertTrue("mgdl ${pt.mgdl} below floor", pt.mgdl >= 18.0)
        }
    }
}
