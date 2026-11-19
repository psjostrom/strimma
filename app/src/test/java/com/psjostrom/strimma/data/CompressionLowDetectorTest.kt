package com.psjostrom.strimma.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionLowDetectorTest {

    private val start = 1_800_000_000_000L
    private val minute = 60_000L

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

        val spans = CompressionLowDetector.detect(readings)

        assertEquals(listOf((start + 5 * minute)..(start + 16 * minute)), spans)
        val kept = CompressionLowDetector.withoutCompressionLows(readings)
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

        assertTrue(CompressionLowDetector.detect(readings, carbTimes = listOf(nadirTs + 5 * minute)).isEmpty())
    }

    @Test
    fun `low during exercise is not flagged`() {
        val readings = trace(*vShape)
        val run = (start - 20 * minute)..(start + 8 * minute)

        assertTrue(CompressionLowDetector.detect(readings, exercisePeriods = listOf(run)).isEmpty())
    }

    @Test
    fun `slow drift before the fall and slow overshoot after the rebound stay in the stats`() {
        val drift = (135 downTo 126).toList()
        val fall = listOf(100, 80, 60, 55)
        val rebound = listOf(75, 95, 115, 125)
        val overshoot = (126..135).toList()
        val readings = trace(*(drift + fall + rebound + overshoot).toIntArray())

        val kept = CompressionLowDetector.withoutCompressionLows(readings)

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

        val kept = CompressionLowDetector.withoutCompressionLows(readings)

        assertTrue(kept.none { it.sgv < 70 })
    }

    @Test
    fun `compression low is found in 5-minute sensor data`() {
        val readings = trace(120, 120, 120, 120, 120, 120, 120, 55, 115, 120, 120, 120, 120, 120, stepMinutes = 5)

        assertEquals(1, CompressionLowDetector.detect(readings).size)
    }

    @Test
    fun `slow insulin-driven low with carb recovery is a real low`() {
        // Falls 1.5 mg/dL per minute, then rises 2 mg/dL per minute after carbs.
        val fall = (0..40).map { 120 - (it * 1.5).toInt() }
        val rise = (1..25).map { 60 + it * 2 }
        val readings = trace(*(fall + rise).toIntArray())

        assertTrue(CompressionLowDetector.detect(readings).isEmpty())
        assertEquals(readings, CompressionLowDetector.withoutCompressionLows(readings))
    }

    @Test
    fun `low that stays below 70 for more than 20 minutes is a real low`() {
        val readings = trace(
            *(List(6) { 120 } + listOf(90, 60) + List(25) { 55 } + listOf(90, 120) + List(5) { 120 }).toIntArray()
        )

        assertTrue(CompressionLowDetector.detect(readings).isEmpty())
    }

    @Test
    fun `low without a rebound yet is not flagged`() {
        val readings = trace(120, 120, 120, 120, 120, 120, 100, 80, 60, 55, 56)

        assertTrue(CompressionLowDetector.detect(readings).isEmpty())
    }
}
