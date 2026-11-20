package com.psjostrom.strimma.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class CompressionLowDetectorTest {

    // 02:00 UTC. The tests pass UTC as the zone, so every trace lies inside the night window.
    private val start = Instant.parse("2026-11-20T02:00:00Z").toEpochMilli()
    private val minute = 60_000L
    private val utc = ZoneOffset.UTC

    /** One reading every [stepMinutes] minutes, starting at [start]. */
    private fun trace(vararg sgvs: Int, stepMinutes: Int = 1): List<GlucoseReading> =
        sgvs.mapIndexed { i, sgv ->
            GlucoseReading(ts = start + i * stepMinutes * minute, sgv = sgv, direction = "Flat", delta = null)
        }

    @Test
    fun `fast fall and fast rebound is a compression low`() {
        val readings = trace(
            120, 120, 120, 120, 120, 120,
            110, 95, 80, 65, 55, // falls 65 mg/dL in 5 minutes
            60, 75, 90, 105, 115, // rebounds 60 mg/dL in 5 minutes
            118, 118, 118, 118
        )

        val spans = CompressionLowDetector.detect(readings, zone = utc)

        assertEquals(listOf((start + 5 * minute)..(start + 16 * minute)), spans)
        val kept = CompressionLowDetector.withoutCompressionLows(readings, zone = utc)
        assertTrue(kept.none { it.sgv < 70 })
        assertEquals(readings.size - 12, kept.size)
    }

    private val vShape = intArrayOf(
        120, 120, 120, 120, 120, 120,
        110, 95, 80, 65, 55,
        60, 75, 90, 105, 115,
        118, 118, 118, 118
    )
    private val nadirTs = start + 10 * minute

    @Test
    fun `low with carbs logged near the nadir is a treated low`() {
        val readings = trace(*vShape)

        assertTrue(CompressionLowDetector.detect(readings, carbTimes = listOf(nadirTs + 5 * minute), zone = utc).isEmpty())
    }

    @Test
    fun `low during exercise is not flagged`() {
        val readings = trace(*vShape)
        val run = (start - 20 * minute)..(start + 8 * minute)

        assertTrue(CompressionLowDetector.detect(readings, exercisePeriods = listOf(run), zone = utc).isEmpty())
    }

    @Test
    fun `slow drift before the fall and slow overshoot after the rebound stay in the stats`() {
        val drift = (135 downTo 126).toList()
        val fall = listOf(100, 80, 60, 55)
        val rebound = listOf(75, 95, 115, 125)
        val overshoot = (126..135).toList()
        val readings = trace(*(drift + fall + rebound + overshoot).toIntArray())

        val kept = CompressionLowDetector.withoutCompressionLows(readings, zone = utc)

        assertEquals((135 downTo 127).toList() + overshoot, kept.map { it.sgv })
    }

    @Test
    fun `second dip of the same episode is left out too`() {
        val readings = trace(
            120, 120, 120, 120, 120, 120,
            100, 75, 50, // fast fall
            80, 110, 125, // fast rebound
            110, 90, 65, 68, 90, // a smaller second dip within 15 minutes
            120, 120, 120, 120, 120
        )

        val kept = CompressionLowDetector.withoutCompressionLows(readings, zone = utc)

        assertTrue(kept.none { it.sgv < 70 })
    }

    @Test
    fun `same shape in the daytime is not flagged`() {
        // 15:00 UTC: awake people treat lows with sweets they rarely log, and the curve looks the same.
        val afternoon = Instant.parse("2026-11-20T15:00:00Z").toEpochMilli()
        val readings = vShape.mapIndexed { i, sgv ->
            GlucoseReading(ts = afternoon + i * minute, sgv = sgv, direction = "Flat", delta = null)
        }

        assertTrue(CompressionLowDetector.detect(readings, zone = utc).isEmpty())
    }

    @Test
    fun `compression low is found in 5-minute sensor data`() {
        val readings = trace(120, 120, 120, 120, 120, 120, 120, 55, 115, 120, 120, 120, 120, 120, stepMinutes = 5)

        assertEquals(1, CompressionLowDetector.detect(readings, zone = utc).size)
    }

    @Test
    fun `slow insulin-driven low with carb recovery is a real low`() {
        // Falls 1.5 mg/dL per minute, then rises 2 mg/dL per minute after carbs.
        val fall = (0..40).map { 120 - (it * 1.5).toInt() }
        val rise = (1..25).map { 60 + it * 2 }
        val readings = trace(*(fall + rise).toIntArray())

        assertTrue(CompressionLowDetector.detect(readings, zone = utc).isEmpty())
        assertEquals(readings, CompressionLowDetector.withoutCompressionLows(readings, zone = utc))
    }

    @Test
    fun `compression low that sits on the sensor floor for 25 minutes is found`() {
        // 5-minute readings: a fall to the 40 mg/dL floor, 25 minutes below 70, and a rebound without carbs.
        val readings = trace(
            76, 94, 94, 92, 81, 79, 79, 58, 40, 40, 40, 40, 40, 70, 95, 106, 112, 112, 114,
            stepMinutes = 5
        )

        val kept = CompressionLowDetector.withoutCompressionLows(readings, zone = utc)

        assertTrue(kept.none { it.sgv < 70 })
    }

    @Test
    fun `low that stays below 70 for more than 30 minutes is a real low`() {
        val readings = trace(
            *(List(6) { 120 } + listOf(90, 60) + List(35) { 55 } + listOf(90, 120) + List(5) { 120 }).toIntArray()
        )

        assertTrue(CompressionLowDetector.detect(readings, zone = utc).isEmpty())
    }

    @Test
    fun `low without a rebound yet is not flagged`() {
        val readings = trace(120, 120, 120, 120, 120, 120, 100, 80, 60, 55, 56)

        assertTrue(CompressionLowDetector.detect(readings, zone = utc).isEmpty())
    }
}
