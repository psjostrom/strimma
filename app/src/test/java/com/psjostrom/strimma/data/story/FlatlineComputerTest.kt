package com.psjostrom.strimma.data.story

import com.psjostrom.strimma.data.GlucoseReading
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class FlatlineComputerTest {

    private val zone = ZoneId.of("Europe/Stockholm")

    private fun reading(sgv: Int, minutesFromStart: Int): GlucoseReading {
        val ts = 1_700_000_000_000L + minutesFromStart * 60_000L
        return GlucoseReading(ts = ts, sgv = sgv, direction = "Flat", delta = null)
    }

    @Test
    fun `findFlatlines returns empty for fewer than 2 readings`() {
        assertEquals(emptyList<FlatlineStretch>(), FlatlineComputer.findFlatlines(listOf(reading(100, 0))))
    }

    @Test
    fun `findFlatlines detects 30-min stretch with no variation`() {
        val readings = (0..30).map { reading(100, it) }
        val flatlines = FlatlineComputer.findFlatlines(readings)
        assertEquals(1, flatlines.size)
        assertEquals(30, flatlines[0].durationMinutes)
        assertEquals(0.0, flatlines[0].maxVariationMgdl, 0.01)
    }

    @Test
    fun `findFlatlines allows up to 2 mgdl consecutive diff`() {
        val readings = listOf(
            reading(100, 0), reading(101, 1), reading(103, 2),
            reading(102, 3), reading(100, 4),
        ) + (5..35).map { reading(100, it) }
        val flatlines = FlatlineComputer.findFlatlines(readings)
        assertEquals(1, flatlines.size)
    }

    @Test
    fun `findFlatlines breaks on diff exceeding 2 mgdl`() {
        val readings = (0..15).map { reading(100, it) } +
            listOf(reading(110, 16)) +
            (17..50).map { reading(110, it) }
        val flatlines = FlatlineComputer.findFlatlines(readings)
        assertEquals(1, flatlines.size)
        assertEquals(34, flatlines[0].durationMinutes)
    }

    @Test
    fun `findFlatlines ignores stretches under 30 minutes`() {
        val readings = (0..20).map { reading(100, it) }
        assertTrue(FlatlineComputer.findFlatlines(readings).isEmpty())
    }

    @Test
    fun `longestInRangeStreak returns null for empty readings`() {
        assertNull(FlatlineComputer.longestInRangeStreak(emptyList(), 70.0, 180.0))
    }

    @Test
    fun `longestInRangeStreak finds continuous in-range stretch`() {
        val readings = listOf(
            reading(50, 0), reading(100, 1), reading(120, 2),
            reading(150, 3), reading(170, 4), reading(200, 5)
        )
        val streak = FlatlineComputer.longestInRangeStreak(readings, 70.0, 180.0)!!
        assertEquals(3, streak.durationMinutes)
    }

    @Test
    fun `longestInRangeStreak picks longest when multiple exist`() {
        val readings = listOf(
            reading(100, 0), reading(120, 10),
            reading(50, 11),
            reading(100, 12), reading(120, 30),
            reading(200, 31)
        )
        val streak = FlatlineComputer.longestInRangeStreak(readings, 70.0, 180.0)!!
        assertEquals(18, streak.durationMinutes)
    }

    @Test
    fun `longestInRangeStreak boundary values are in range`() {
        val readings = listOf(reading(70, 0), reading(180, 60))
        val streak = FlatlineComputer.longestInRangeStreak(readings, 70.0, 180.0)!!
        assertEquals(60, streak.durationMinutes)
    }

    @Test
    fun `steadiestDay returns null for empty readings`() {
        assertNull(FlatlineComputer.steadiestDay(emptyList(), 70.0, 180.0, zone))
    }

    @Test
    fun `steadiestDay returns null when no day has enough readings`() {
        val readings = (0..10).map { reading(100, it) }
        assertNull(FlatlineComputer.steadiestDay(readings, 70.0, 180.0, zone))
    }

    @Test
    fun `steadiestDay picks day with lowest CV`() {
        val day1 = (0..29).map { reading(100, it) }
        val day2Base = 24 * 60
        val day2 = (0..29).map { reading(if (it % 2 == 0) 80 else 120, day2Base + it) }
        val result = FlatlineComputer.steadiestDay(day1 + day2, 70.0, 180.0, zone)!!
        assertEquals(0.0, result.cv, 0.01)
    }
}
