package com.psjostrom.strimma.data.pattern

import com.psjostrom.strimma.data.GlucoseReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PatternDetectorTest {

    private val zone = ZoneId.of("Europe/Stockholm")
    private val baseDate = LocalDate.of(2026, 3, 15)
    private val now = LocalDateTime.of(2026, 3, 15, 22, 0)
        .atZone(zone).toInstant()

    private fun createReading(
        date: LocalDate,
        hour: Int,
        minute: Int,
        sgv: Int
    ): GlucoseReading {
        val dt = LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, minute)
        val ts = dt.atZone(zone).toInstant().toEpochMilli()
        return GlucoseReading(ts = ts, sgv = sgv, direction = "Flat", delta = null)
    }

    private fun generateHourReadings(
        date: LocalDate,
        hour: Int,
        sgvValues: List<Int>
    ): List<GlucoseReading> {
        val step = 60 / sgvValues.size
        return sgvValues.mapIndexed { index, sgv ->
            createReading(date, hour, index * step, sgv)
        }
    }

    @Test
    fun `detect returns empty on empty readings`() {
        val result = PatternDetector.detect(
            readings = emptyList(),
            bgLowMgdl = 72.0,
            bgHighMgdl = 180.0,
            zone = zone,
            now = now
        )
        assertTrue(result.patterns.isEmpty())
        assertEquals("", result.stableHash())
    }

    @Test
    fun `detect returns empty when all readings in range`() {
        val readings = mutableListOf<GlucoseReading>()
        for (dayOffset in 1..7) {
            val date = baseDate.minusDays(dayOffset.toLong())
            for (hour in 0..23) {
                readings.addAll(generateHourReadings(date, hour, listOf(110, 115, 120, 110)))
            }
        }

        val result = PatternDetector.detect(
            readings = readings,
            bgLowMgdl = 72.0,
            bgHighMgdl = 180.0,
            zone = zone,
            now = now
        )
        assertTrue(result.patterns.isEmpty())
    }

    @Test
    fun `detect detects high pattern when 5 of 7 days high at 15-00`() {
        val readings = mutableListOf<GlucoseReading>()
        for (dayOffset in 1..7) {
            val date = baseDate.minusDays(dayOffset.toLong())
            val sgv = if (dayOffset <= 5) listOf(210, 220, 205) else listOf(110, 120, 115)
            readings.addAll(generateHourReadings(date, 15, sgv))
        }

        val result = PatternDetector.detect(
            readings = readings,
            bgLowMgdl = 72.0,
            bgHighMgdl = 180.0,
            zone = zone,
            now = now
        )

        assertEquals(1, result.patterns.size)
        val pattern = result.patterns.first()
        assertEquals(PatternType.HIGH, pattern.type)
        assertEquals(15, pattern.startHour)
        assertEquals(16, pattern.endHour)
        assertEquals(5, pattern.daysDetected)
        assertEquals(7, pattern.daysEvaluated)
        assertEquals(220, pattern.worstBgMgdl)
        assertTrue(pattern.avgBgMgdl > 200.0)
    }

    @Test
    fun `detect ignores pattern when only 3 of 7 days flagged`() {
        val readings = mutableListOf<GlucoseReading>()
        for (dayOffset in 1..7) {
            val date = baseDate.minusDays(dayOffset.toLong())
            val sgv = if (dayOffset <= 3) listOf(210, 220, 205) else listOf(110, 120, 115)
            readings.addAll(generateHourReadings(date, 15, sgv))
        }

        val result = PatternDetector.detect(
            readings = readings,
            bgLowMgdl = 72.0,
            bgHighMgdl = 180.0,
            zone = zone,
            now = now
        )

        assertTrue(result.patterns.isEmpty())
    }

    @Test
    fun `detect ignores day-hour with fewer than 3 readings`() {
        val readings = mutableListOf<GlucoseReading>()
        for (dayOffset in 1..7) {
            val date = baseDate.minusDays(dayOffset.toLong())
            // Only 2 readings -> insufficient coverage
            readings.addAll(generateHourReadings(date, 15, listOf(210, 220)))
        }

        val result = PatternDetector.detect(
            readings = readings,
            bgLowMgdl = 72.0,
            bgHighMgdl = 180.0,
            zone = zone,
            now = now
        )

        assertTrue(result.patterns.isEmpty())
    }

    @Test
    fun `detect detects low pattern when 4 of 6 evaluated days low at 06-00`() {
        val readings = mutableListOf<GlucoseReading>()
        for (dayOffset in 1..6) {
            val date = baseDate.minusDays(dayOffset.toLong())
            val sgv = if (dayOffset <= 4) listOf(55, 60, 58) else listOf(95, 100, 105)
            readings.addAll(generateHourReadings(date, 6, sgv))
        }

        val result = PatternDetector.detect(
            readings = readings,
            bgLowMgdl = 72.0,
            bgHighMgdl = 180.0,
            zone = zone,
            now = now
        )

        assertEquals(1, result.patterns.size)
        val pattern = result.patterns.first()
        assertEquals(PatternType.LOW, pattern.type)
        assertEquals(6, pattern.startHour)
        assertEquals(7, pattern.endHour)
        assertEquals(4, pattern.daysDetected)
        assertEquals(6, pattern.daysEvaluated)
        assertEquals(55, pattern.worstBgMgdl)
    }

    @Test
    fun `detect merges adjacent same-type patterns`() {
        val readings = mutableListOf<GlucoseReading>()
        for (dayOffset in 1..7) {
            val date = baseDate.minusDays(dayOffset.toLong())
            if (dayOffset <= 5) {
                // High from 14:00 to 17:00 (hours 14, 15, 16)
                readings.addAll(generateHourReadings(date, 14, listOf(200, 210, 220)))
                readings.addAll(generateHourReadings(date, 15, listOf(210, 230, 240)))
                readings.addAll(generateHourReadings(date, 16, listOf(205, 215, 225)))
            } else {
                readings.addAll(generateHourReadings(date, 14, listOf(110, 120, 115)))
                readings.addAll(generateHourReadings(date, 15, listOf(110, 120, 115)))
                readings.addAll(generateHourReadings(date, 16, listOf(110, 120, 115)))
            }
        }

        val result = PatternDetector.detect(
            readings = readings,
            bgLowMgdl = 72.0,
            bgHighMgdl = 180.0,
            zone = zone,
            now = now
        )

        assertEquals(1, result.patterns.size)
        val merged = result.patterns.first()
        assertEquals(PatternType.HIGH, merged.type)
        assertEquals(14, merged.startHour)
        assertEquals(17, merged.endHour)
        assertEquals(5, merged.daysDetected)
        assertEquals(240, merged.worstBgMgdl)
    }

    @Test
    fun `mergeAdjacent preserves sample counts and calculates accurate average for three adjacent hours`() {
        val p1 = GlucosePattern(
            startHour = 14, endHour = 15, type = PatternType.HIGH,
            daysDetected = 5, daysEvaluated = 7, avgBgMgdl = 200.0, worstBgMgdl = 210,
            sampleCount = 10, bgSum = 2000.0
        )
        val p2 = GlucosePattern(
            startHour = 15, endHour = 16, type = PatternType.HIGH,
            daysDetected = 4, daysEvaluated = 7, avgBgMgdl = 240.0, worstBgMgdl = 260,
            sampleCount = 20, bgSum = 4800.0
        )
        val p3 = GlucosePattern(
            startHour = 16, endHour = 17, type = PatternType.HIGH,
            daysDetected = 6, daysEvaluated = 7, avgBgMgdl = 210.0, worstBgMgdl = 230,
            sampleCount = 10, bgSum = 2100.0
        )

        // Total sum = 2000 + 4800 + 2100 = 8900, count = 40, avg = 222.5
        val forward = PatternDetector.mergeAdjacent(listOf(p1, p2, p3))
        assertEquals(1, forward.size)
        val mergedForward = forward.first()
        assertEquals(14, mergedForward.startHour)
        assertEquals(17, mergedForward.endHour)
        assertEquals(222.5, mergedForward.avgBgMgdl, 0.001)
        assertEquals(260, mergedForward.worstBgMgdl)
        assertEquals(40, mergedForward.sampleCount)
        assertEquals(8900.0, mergedForward.bgSum, 0.001)

        val reverse = PatternDetector.mergeAdjacent(listOf(p3, p1, p2))
        assertEquals(1, reverse.size)
        assertEquals(mergedForward, reverse.first())
    }

    @Test
    fun `detect excludes readings inside workout periods`() {
        val readings = mutableListOf<GlucoseReading>()
        val workouts = mutableListOf<LongRange>()

        for (dayOffset in 1..7) {
            val date = baseDate.minusDays(dayOffset.toLong())
            val hour15 = generateHourReadings(date, 15, listOf(210, 220, 205))
            readings.addAll(hour15)

            // Every day had a workout at 15:00
            val startTs = hour15.first().ts
            val endTs = hour15.last().ts
            workouts.add(startTs..endTs)
        }

        val result = PatternDetector.detect(
            readings = readings,
            bgLowMgdl = 72.0,
            bgHighMgdl = 180.0,
            zone = zone,
            now = now,
            workoutPeriods = workouts
        )

        // All 15:00 readings were during workouts -> excluded -> empty result
        assertTrue(result.patterns.isEmpty())
    }

    @Test
    fun `stableKey and stableHash are deterministic`() {
        val p1 = GlucosePattern(
            startHour = 14,
            endHour = 16,
            type = PatternType.HIGH,
            daysDetected = 5,
            daysEvaluated = 7,
            avgBgMgdl = 220.5,
            worstBgMgdl = 250
        )
        assertEquals("HIGH:14-16", p1.stableKey())

        val result = PatternResult(
            patterns = listOf(p1),
            analysisDate = baseDate,
            lookbackDays = 7
        )
        assertEquals("HIGH:14-16", result.stableHash())
    }
}
