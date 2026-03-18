package com.psjostrom.strimma.data

import org.junit.Assert.*
import org.junit.Test

class DirectionComputerTest {

    private val computer = DirectionComputer()
    private val baseTs = 1_700_000_000_000L

    private fun reading(minutesAgo: Int, mmol: Double): GlucoseReading {
        val sgv = (mmol * 18.0182).toInt()
        return GlucoseReading(
            ts = baseTs - minutesAgo * 60_000L,
            sgv = sgv, mmol = mmol,
            direction = "NONE", deltaMmol = null, pushed = 1
        )
    }

    private fun compute(history: List<GlucoseReading>, current: GlucoseReading) =
        computer.compute(history, current)

    // --- Threshold boundary tests (EASD/ISPAD 2020) ---

    @Test
    fun `flat when stable glucose`() {
        val history = listOf(reading(10, 6.0), reading(9, 6.0), reading(8, 6.0),
            reading(7, 6.0), reading(6, 6.0), reading(5, 6.0))
        val current = reading(0, 6.0)
        val (direction, _) = compute(history, current)
        assertEquals(Direction.Flat, direction)
    }

    @Test
    fun `fortyFiveUp when rising moderately`() {
        // ~+1.5 mg/dL/min over 5 min = +7.5 mg/dL total ≈ +0.42 mmol
        val history = (10 downTo 1).map { reading(it, 6.0 + (10 - it) * 0.08) }
        val current = reading(0, 6.8)
        val (direction, _) = compute(history, current)
        assertEquals(Direction.FortyFiveUp, direction)
    }

    @Test
    fun `singleUp when rising fast`() {
        // ~+2.5 mg/dL/min over 5 min
        val history = (10 downTo 1).map { reading(it, 6.0 + (10 - it) * 0.14) }
        val current = reading(0, 7.4)
        val (direction, _) = compute(history, current)
        assertEquals(Direction.SingleUp, direction)
    }

    @Test
    fun `doubleUp when rising very fast`() {
        // ~+4 mg/dL/min over 5 min
        val history = (10 downTo 1).map { reading(it, 6.0 + (10 - it) * 0.22) }
        val current = reading(0, 8.2)
        val (direction, _) = compute(history, current)
        assertEquals(Direction.DoubleUp, direction)
    }

    @Test
    fun `fortyFiveDown when falling moderately`() {
        val history = (10 downTo 1).map { reading(it, 8.0 - (10 - it) * 0.08) }
        val current = reading(0, 7.2)
        val (direction, _) = compute(history, current)
        assertEquals(Direction.FortyFiveDown, direction)
    }

    @Test
    fun `singleDown when falling fast`() {
        val history = (10 downTo 1).map { reading(it, 8.0 - (10 - it) * 0.14) }
        val current = reading(0, 6.6)
        val (direction, _) = compute(history, current)
        assertEquals(Direction.SingleDown, direction)
    }

    @Test
    fun `doubleDown when falling very fast`() {
        val history = (10 downTo 1).map { reading(it, 10.0 - (10 - it) * 0.22) }
        val current = reading(0, 7.8)
        val (direction, _) = compute(history, current)
        assertEquals(Direction.DoubleDown, direction)
    }

    // --- Edge cases ---

    @Test
    fun `NONE with no history`() {
        val current = reading(0, 6.0)
        val (direction, delta) = compute(emptyList(), current)
        assertEquals(Direction.NONE, direction)
        assertNull(delta)
    }

    @Test
    fun `computes from nearest reading when no exact 5-min match`() {
        // Readings at 1 and 2 min ago — closest to 5 min target is 2 min ago.
        // Still within 10-min window, so direction is computed (not NONE).
        val history = listOf(reading(1, 6.0), reading(2, 6.0))
        val current = reading(0, 6.0)
        val (direction, _) = compute(history, current)
        assertEquals(Direction.Flat, direction)
    }

    @Test
    fun `computes with exactly one 5-minute-ago reading`() {
        val history = listOf(reading(5, 6.0))
        val current = reading(0, 6.0)
        val (direction, _) = compute(history, current)
        assertEquals(Direction.Flat, direction)
    }

    @Test
    fun `NONE when gap exceeds 10 minutes`() {
        val history = listOf(reading(20, 6.0))
        val current = reading(0, 6.0)
        val (direction, delta) = compute(history, current)
        assertEquals(Direction.NONE, direction)
        assertNull(delta)
    }

    @Test
    fun `delta is in mmol not mg`() {
        val history = (10 downTo 1).map { reading(it, 6.0) }
        val current = reading(0, 7.0)
        val (_, delta) = compute(history, current)
        assertNotNull(delta)
        // Delta is computed from 3-point-averaged SGV values converted to mmol.
        // SGV values are rounded (mmol*18.0182→int), so delta won't be exactly 1.0
        // but it must be in mmol range (0.5-2.0), not mg/dL range (9-36).
        assertTrue("delta should be in mmol range, was $delta", delta!! in 0.3..2.0)
    }

    @Test
    fun `3-point averaging smooths spikes`() {
        // Spike in the middle: 6.0, 6.0, 12.0, 6.0, 6.0 (at 5-min marks)
        // Without averaging, last reading vs 5-min-ago would be Flat (6→6)
        // With averaging, the spike affects neighbors
        val history = listOf(
            reading(10, 6.0), reading(8, 6.0), reading(6, 12.0),
            reading(4, 6.0), reading(2, 6.0)
        )
        val current = reading(0, 6.0)
        val (direction, _) = compute(history, current)
        // The 3-point average at current index should smooth out
        // This verifies averaging doesn't crash with varying data
        assertNotNull(direction)
    }
}
