package com.psjostrom.strimma.data.story

import com.psjostrom.strimma.data.GlucoseReading
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TimeOfDayComputerTest {

    private val zone = ZoneId.of("Europe/Stockholm")

    private fun readingAt(sgv: Int, hour: Int, minute: Int = 0): GlucoseReading {
        val dt = LocalDateTime.of(2026, 3, 15, hour, minute)
        val ts = dt.atZone(zone).toInstant().toEpochMilli()
        return GlucoseReading(ts = ts, sgv = sgv, direction = "Flat", delta = null)
    }

    @Test
    fun `compute returns 4 blocks with zero readings for empty input`() {
        val result = TimeOfDayComputer.compute(emptyList(), 70.0, 180.0, zone)
        assertEquals(4, result.blocks.size)
        assertTrue(result.blocks.all { it.readingCount == 0 })
    }

    @Test
    fun `compute assigns readings to correct time blocks`() {
        val readings = listOf(
            readingAt(100, 2), readingAt(100, 8),
            readingAt(100, 14), readingAt(100, 20)
        )
        val result = TimeOfDayComputer.compute(readings, 70.0, 180.0, zone)
        assertEquals(1, result.blocks[0].readingCount)
        assertEquals(1, result.blocks[1].readingCount)
        assertEquals(1, result.blocks[2].readingCount)
        assertEquals(1, result.blocks[3].readingCount)
    }

    @Test
    fun `compute calculates TIR per block`() {
        val readings = listOf(
            readingAt(100, 2), readingAt(50, 3),
            readingAt(200, 8), readingAt(100, 9)
        )
        val result = TimeOfDayComputer.compute(readings, 70.0, 180.0, zone)
        assertEquals(50.0, result.blocks[0].tirPercent, 0.01)
        assertEquals(50.0, result.blocks[1].tirPercent, 0.01)
    }

    @Test
    fun `hour 6 belongs to Morning not Night`() {
        val readings = listOf(readingAt(100, 6))
        val result = TimeOfDayComputer.compute(readings, 70.0, 180.0, zone)
        assertEquals(1, result.blocks[1].readingCount)
        assertEquals(0, result.blocks[0].readingCount)
    }

    @Test
    fun `compute uses provided thresholds not hardcoded 70-180`() {
        val readings = listOf(readingAt(75, 2), readingAt(65, 3))
        val wide = TimeOfDayComputer.compute(readings, 60.0, 190.0, zone)
        val narrow = TimeOfDayComputer.compute(readings, 70.0, 180.0, zone)
        assertEquals(100.0, wide.blocks[0].tirPercent, 0.01)
        assertEquals(50.0, narrow.blocks[0].tirPercent, 0.01)
    }

    @Test
    fun `block names are Night Morning Afternoon Evening`() {
        val result = TimeOfDayComputer.compute(emptyList(), 70.0, 180.0, zone)
        assertEquals(listOf("Night", "Morning", "Afternoon", "Evening"), result.blocks.map { it.name })
    }
}
