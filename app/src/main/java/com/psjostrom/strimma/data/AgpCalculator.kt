package com.psjostrom.strimma.data

import com.psjostrom.strimma.graph.CRITICAL_LOW
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.sqrt

data class AgpBucket(
    val minuteOfDay: Int,
    val p5: Double,
    val p25: Double,
    val p50: Double,
    val p75: Double,
    val p95: Double,
    val count: Int
)

data class AgpMetrics(
    val veryLowPercent: Double,
    val lowPercent: Double,
    val inRangePercent: Double,
    val highPercent: Double,
    val veryHighPercent: Double,
    val averageMgdl: Double,
    val gmi: Double,
    val cv: Double,
    val sensorActivePercent: Double,
    val count: Int
)

data class AgpResult(
    val buckets: List<AgpBucket>,
    val metrics: AgpMetrics
)

object AgpCalculator {

    const val AGP_DAYS = 14
    private const val BUCKET_MINUTES = 15
    private const val BUCKETS_PER_DAY = 24 * 60 / BUCKET_MINUTES

    private const val PERCENT = 100.0

    // ADA fixed thresholds (mg/dL)
    const val VERY_LOW = CRITICAL_LOW
    const val LOW = 70.0
    const val HIGH = 180.0
    const val VERY_HIGH = 250.0

    // AGP percentile levels
    private const val P5 = 5.0
    private const val P25 = 25.0
    private const val P50 = 50.0
    private const val P75 = 75.0
    private const val P95 = 95.0

    private const val MINUTES_PER_HOUR = 60
    private const val MAX_SENSOR_INTERVAL_MINUTES = 5L

    fun compute(readings: List<GlucoseReading>): AgpResult? {
        if (readings.isEmpty()) return null

        val bucketMap = Array(BUCKETS_PER_DAY) { mutableListOf<Double>() }
        val cal = Calendar.getInstance(TimeZone.getDefault())

        for (reading in readings) {
            cal.timeInMillis = reading.ts
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val minute = cal.get(Calendar.MINUTE)
            val minuteOfDay = hour * MINUTES_PER_HOUR + minute
            val bucketIndex = minuteOfDay / BUCKET_MINUTES
            bucketMap[bucketIndex].add(reading.sgv.toDouble())
        }

        val buckets = bucketMap.mapIndexedNotNull { index, values ->
            if (values.isEmpty()) return@mapIndexedNotNull null
            val sorted = values.sorted()
            AgpBucket(
                minuteOfDay = index * BUCKET_MINUTES,
                p5 = percentile(sorted, P5),
                p25 = percentile(sorted, P25),
                p50 = percentile(sorted, P50),
                p75 = percentile(sorted, P75),
                p95 = percentile(sorted, P95),
                count = sorted.size
            )
        }

        val metrics = computeMetrics(readings)
        return AgpResult(buckets = buckets, metrics = metrics)
    }

    private fun computeMetrics(readings: List<GlucoseReading>): AgpMetrics {
        val values = readings.map { it.sgv.toDouble() }
        val count = values.size
        val avg = values.average()
        val variance = values.map { (it - avg) * (it - avg) }.average()
        val stdDev = sqrt(variance)
        val cv = if (avg > 0) (stdDev / avg) * PERCENT else 0.0
        val gmi = GmiCalculator.compute(avg)

        val veryLow = values.count { it < VERY_LOW }
        val low = values.count { it >= VERY_LOW && it < LOW }
        val inRange = values.count { it >= LOW && it <= HIGH }
        val high = values.count { it > HIGH && it <= VERY_HIGH }
        val veryHigh = values.count { it > VERY_HIGH }

        // Sensor active %: estimate expected readings from observed interval
        val sensorActivePercent = estimateSensorActive(readings)

        return AgpMetrics(
            veryLowPercent = (veryLow.toDouble() / count) * PERCENT,
            lowPercent = (low.toDouble() / count) * PERCENT,
            inRangePercent = (inRange.toDouble() / count) * PERCENT,
            highPercent = (high.toDouble() / count) * PERCENT,
            veryHighPercent = (veryHigh.toDouble() / count) * PERCENT,
            averageMgdl = avg,
            gmi = gmi,
            cv = cv,
            sensorActivePercent = sensorActivePercent,
            count = count
        )
    }

    private fun estimateSensorActive(readings: List<GlucoseReading>): Double {
        if (readings.size < 2) return PERCENT
        val sorted = readings.sortedBy { it.ts }
        val spanMs = sorted.last().ts - sorted.first().ts
        if (spanMs <= 0) return PERCENT

        // Find typical interval from median of gaps
        val gaps = (1 until sorted.size).map { sorted[it].ts - sorted[it - 1].ts }
        val sortedGaps = gaps.sorted()
        val mid = sortedGaps.size / 2
        val medianGapMs = if (sortedGaps.size % 2 == 0) {
            (sortedGaps[mid - 1] + sortedGaps[mid]) / 2
        } else {
            sortedGaps[mid]
        }
        // Clamp to reasonable range (1-5 min)
        val intervalMs = medianGapMs.coerceIn(MS_PER_MINUTE, MAX_SENSOR_INTERVAL_MINUTES * MS_PER_MINUTE)

        val expectedReadings = spanMs / intervalMs + 1
        return ((readings.size.toDouble() / expectedReadings) * PERCENT).coerceAtMost(PERCENT)
    }

    /**
     * Linear interpolation percentile (standard statistical method).
     * Input must be sorted ascending.
     */
    internal fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val rank = (p / PERCENT) * (sorted.size - 1)
        val lower = rank.toInt()
        val upper = (lower + 1).coerceAtMost(sorted.size - 1)
        val fraction = rank - lower
        return sorted[lower] + fraction * (sorted[upper] - sorted[lower])
    }
}
