package com.psjostrom.strimma.data.story

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.Treatment
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class StoryComputerTest {

    private val zone = ZoneId.of("Europe/Stockholm")
    private val march2026 = YearMonth.of(2026, 3)

    private fun readingAt(sgv: Int, day: Int, hour: Int, minute: Int = 0): GlucoseReading {
        val dt = LocalDateTime.of(2026, 3, day, hour, minute)
        val ts = dt.atZone(zone).toInstant().toEpochMilli()
        return GlucoseReading(ts = ts, sgv = sgv, direction = "Flat", delta = null)
    }

    private fun fullDay(sgv: Int, day: Int): List<GlucoseReading> =
        (0 until 24 * 60).map { min -> readingAt(sgv, day, min / 60, min % 60) }

    @Test
    fun `compute returns null for fewer than 7 days of data`() {
        val readings = (1..6).flatMap { fullDay(120, it) }
        val result = StoryComputer.compute(
            month = march2026, readings = readings, previousReadings = emptyList(),
            carbTreatments = emptyList(), allTreatments = emptyList(),
            bgLow = 70.0, bgHigh = 180.0, tauMinutes = 55.0, zone = zone
        )
        assertNull(result)
    }

    @Test
    fun `compute returns StoryData for 7+ days of data`() {
        val readings = (1..10).flatMap { fullDay(120, it) }
        val result = StoryComputer.compute(
            month = march2026, readings = readings, previousReadings = emptyList(),
            carbTreatments = emptyList(), allTreatments = emptyList(),
            bgLow = 70.0, bgHigh = 180.0, tauMinutes = 55.0, zone = zone
        )
        assertNotNull(result)
        assertEquals(2026, result!!.year)
        assertEquals(3, result.month)
        assertEquals(10, result.dayCount)
    }

    @Test
    fun `compute includes previous month stats when readings provided`() {
        val current = (1..10).flatMap { fullDay(120, it) }
        val prevReadings = (1..10).flatMap { day ->
            (0 until 24 * 60).map { min ->
                val dt = LocalDateTime.of(2026, 2, day, min / 60, min % 60)
                val ts = dt.atZone(zone).toInstant().toEpochMilli()
                GlucoseReading(ts = ts, sgv = 150, direction = "Flat", delta = null)
            }
        }
        val result = StoryComputer.compute(
            month = march2026, readings = current, previousReadings = prevReadings,
            carbTreatments = emptyList(), allTreatments = emptyList(),
            bgLow = 70.0, bgHigh = 180.0, tauMinutes = 55.0, zone = zone
        )
        assertNotNull(result!!.previousStats)
    }

    @Test
    fun `compute sets previousStats null when previous readings insufficient`() {
        val current = (1..10).flatMap { fullDay(120, it) }
        val result = StoryComputer.compute(
            month = march2026, readings = current, previousReadings = emptyList(),
            carbTreatments = emptyList(), allTreatments = emptyList(),
            bgLow = 70.0, bgHigh = 180.0, tauMinutes = 55.0, zone = zone
        )
        assertNull(result!!.previousStats)
    }

    @Test
    fun `compute sets meals null when no carb treatments`() {
        val current = (1..10).flatMap { fullDay(120, it) }
        val result = StoryComputer.compute(
            month = march2026, readings = current, previousReadings = emptyList(),
            carbTreatments = emptyList(), allTreatments = emptyList(),
            bgLow = 70.0, bgHigh = 180.0, tauMinutes = 55.0, zone = zone
        )
        assertNull(result!!.meals)
    }

    @Test
    fun `compute generates non-empty narrative`() {
        val readings = (1..10).flatMap { fullDay(120, it) }
        val result = StoryComputer.compute(
            month = march2026, readings = readings, previousReadings = emptyList(),
            carbTreatments = emptyList(), allTreatments = emptyList(),
            bgLow = 70.0, bgHigh = 180.0, tauMinutes = 55.0, zone = zone
        )
        assertTrue(result!!.narrative.isNotBlank())
    }
}
