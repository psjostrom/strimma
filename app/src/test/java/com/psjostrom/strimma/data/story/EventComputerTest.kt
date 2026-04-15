package com.psjostrom.strimma.data.story

import com.psjostrom.strimma.data.GlucoseReading
import org.junit.Assert.*
import org.junit.Test

class EventComputerTest {

    private fun reading(sgv: Int, minutesFromStart: Int): GlucoseReading {
        val ts = 1_700_000_000_000L + minutesFromStart * 60_000L
        return GlucoseReading(ts = ts, sgv = sgv, direction = "Flat", delta = null)
    }

    @Test
    fun `compute returns zeros for empty readings`() {
        val result = EventComputer.compute(emptyList())
        assertEquals(0, result.lowEvents)
        assertEquals(0, result.highEvents)
        assertEquals(0.0, result.belowPercent, 0.01)
        assertEquals(0.0, result.abovePercent, 0.01)
    }

    @Test
    fun `compute returns zeros for all-in-range readings`() {
        val readings = (0..60).map { reading(120, it) }
        val result = EventComputer.compute(readings)
        assertEquals(0, result.lowEvents)
        assertEquals(0, result.highEvents)
    }

    @Test
    fun `compute counts single low event`() {
        val readings = (0..10).map { reading(120, it) } +
            (11..30).map { reading(60, it) } +
            (31..60).map { reading(120, it) }
        val result = EventComputer.compute(readings)
        assertEquals(1, result.lowEvents)
        assertEquals(0, result.highEvents)
    }

    @Test
    fun `compute counts single high event`() {
        val readings = (0..10).map { reading(120, it) } +
            (11..30).map { reading(200, it) } +
            (31..60).map { reading(120, it) }
        val result = EventComputer.compute(readings)
        assertEquals(0, result.lowEvents)
        assertEquals(1, result.highEvents)
    }

    @Test
    fun `compute merges events separated by less than 15 min`() {
        val readings = (0..10).map { reading(60, it) } +
            (11..20).map { reading(100, it) } +
            (21..40).map { reading(60, it) }
        val result = EventComputer.compute(readings)
        assertEquals(1, result.lowEvents)
    }

    @Test
    fun `compute splits events separated by 15+ min`() {
        val readings = (0..10).map { reading(60, it) } +
            (11..30).map { reading(100, it) } +
            (31..50).map { reading(60, it) }
        val result = EventComputer.compute(readings)
        assertEquals(2, result.lowEvents)
    }

    @Test
    fun `compute returns correct below and above percent`() {
        val readings = listOf(
            reading(50, 0), reading(100, 1),
            reading(120, 2), reading(200, 3)
        )
        val result = EventComputer.compute(readings)
        assertEquals(25.0, result.belowPercent, 0.01)
        assertEquals(25.0, result.abovePercent, 0.01)
    }

    @Test
    fun `compute returns average duration for low events`() {
        val readings = (0..20).map { reading(60, it) } +
            (21..50).map { reading(100, it) } +
            (51..60).map { reading(60, it) }
        val result = EventComputer.compute(readings)
        assertEquals(2, result.lowEvents)
        assertNotNull(result.avgLowDurationMinutes)
        assertEquals(14, result.avgLowDurationMinutes)
    }

    @Test
    fun `boundary value 70 is in range not low`() {
        val readings = listOf(reading(70, 0), reading(70, 30))
        val result = EventComputer.compute(readings)
        assertEquals(0, result.lowEvents)
        assertEquals(0.0, result.belowPercent, 0.01)
    }

    @Test
    fun `boundary value 180 is in range not high`() {
        val readings = listOf(reading(180, 0), reading(180, 30))
        val result = EventComputer.compute(readings)
        assertEquals(0, result.highEvents)
        assertEquals(0.0, result.abovePercent, 0.01)
    }
}
