package com.psjostrom.strimma.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AgpCalculatorTest {

    private fun reading(hour: Int, minute: Int, sgv: Int, dayOffset: Int = 0): GlucoseReading {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -dayOffset)
        }
        return GlucoseReading(ts = cal.timeInMillis, sgv = sgv, direction = "Flat", delta = null)
    }

    @Test
    fun `compute returns null for empty readings`() {
        assertNull(AgpCalculator.compute(emptyList()))
    }

    @Test
    fun `compute returns result for single reading`() {
        val result = AgpCalculator.compute(listOf(reading(12, 0, 120)))
        assertNotNull(result)
        assertEquals(1, result!!.buckets.size)
        assertEquals(120.0, result.buckets[0].p50, 0.1)
    }

    @Test
    fun `buckets are assigned by time of day`() {
        val readings = listOf(
            reading(0, 0, 100),
            reading(0, 14, 110),
            reading(0, 15, 120),
            reading(6, 30, 140)
        )
        val result = AgpCalculator.compute(readings)!!
        // 00:00 and 00:14 go in bucket 0 (minute 0), 00:15 in bucket 1 (minute 15), 06:30 in bucket 26 (minute 390)
        val bucket0 = result.buckets.first { it.minuteOfDay == 0 }
        assertEquals(2, bucket0.count)

        val bucket1 = result.buckets.first { it.minuteOfDay == 15 }
        assertEquals(1, bucket1.count)

        val bucket26 = result.buckets.first { it.minuteOfDay == 390 }
        assertEquals(1, bucket26.count)
    }

    @Test
    fun `percentiles are correct for known data`() {
        // 10 values: 100, 110, 120, 130, 140, 150, 160, 170, 180, 190
        val sorted = (100..190 step 10).map { it.toDouble() }
        assertEquals(100.0, AgpCalculator.percentile(sorted, 0.0), 0.1)
        assertEquals(190.0, AgpCalculator.percentile(sorted, 100.0), 0.1)
        assertEquals(145.0, AgpCalculator.percentile(sorted, 50.0), 0.1)
    }

    @Test
    fun `percentile of single value returns that value`() {
        assertEquals(120.0, AgpCalculator.percentile(listOf(120.0), 50.0), 0.1)
    }

    @Test
    fun `five tier TIR uses ADA thresholds`() {
        val readings = listOf(
            reading(8, 0, 50),   // very low (<54)
            reading(8, 15, 60),  // low (54-70)
            reading(8, 30, 120), // in range (70-180)
            reading(8, 45, 200), // high (180-250)
            reading(9, 0, 300)   // very high (>250)
        )
        val result = AgpCalculator.compute(readings)!!
        val m = result.metrics
        assertEquals(20.0, m.veryLowPercent, 0.1)
        assertEquals(20.0, m.lowPercent, 0.1)
        assertEquals(20.0, m.inRangePercent, 0.1)
        assertEquals(20.0, m.highPercent, 0.1)
        assertEquals(20.0, m.veryHighPercent, 0.1)
    }

    @Test
    fun `boundary values classified correctly`() {
        val readings = listOf(
            reading(8, 0, 54),   // low (>=54, <70) — NOT very low
            reading(8, 15, 70),  // in range (>=70, <=180)
            reading(8, 30, 180), // in range
            reading(8, 45, 250)  // high (>180, <=250) — NOT very high
        )
        val result = AgpCalculator.compute(readings)!!
        val m = result.metrics
        assertEquals(0.0, m.veryLowPercent, 0.1)
        assertEquals(25.0, m.lowPercent, 0.1)
        assertEquals(50.0, m.inRangePercent, 0.1)
        assertEquals(25.0, m.highPercent, 0.1)
        assertEquals(0.0, m.veryHighPercent, 0.1)
    }

    @Test
    fun `GMI formula matches expected`() {
        // Average 150 mg/dL → GMI = 3.31 + 0.02392 * 150 = 6.898
        val readings = listOf(reading(8, 0, 150))
        val result = AgpCalculator.compute(readings)!!
        assertEquals(6.898, result.metrics.gmi, 0.01)
    }

    @Test
    fun `CV calculation is correct`() {
        // Two values: 100, 200. Mean=150, stddev=50, CV=33.3%
        val readings = listOf(
            reading(8, 0, 100),
            reading(8, 15, 200)
        )
        val result = AgpCalculator.compute(readings)!!
        assertEquals(33.3, result.metrics.cv, 0.5)
    }

    @Test
    fun `readings from multiple days are overlaid into same buckets`() {
        val readings = listOf(
            reading(8, 0, 100, dayOffset = 0),
            reading(8, 0, 120, dayOffset = 1),
            reading(8, 0, 140, dayOffset = 2)
        )
        val result = AgpCalculator.compute(readings)!!
        val bucket = result.buckets.first { it.minuteOfDay == 480 }
        assertEquals(3, bucket.count)
        assertEquals(120.0, bucket.p50, 0.1)
    }

    @Test
    fun `sensor active percent is 100 for continuous data`() {
        // 15 readings 1 minute apart
        val baseTime = System.currentTimeMillis()
        val readings = (0 until 15).map { i ->
            GlucoseReading(ts = baseTime + i * 60_000L, sgv = 120, direction = "Flat", delta = null)
        }
        val result = AgpCalculator.compute(readings)!!
        assertEquals(100.0, result.metrics.sensorActivePercent, 1.0)
    }
}
