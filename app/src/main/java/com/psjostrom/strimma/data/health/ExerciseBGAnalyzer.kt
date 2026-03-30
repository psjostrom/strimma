package com.psjostrom.strimma.data.health

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.TimeConstants
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

enum class Trend { RISING, FALLING, STABLE }

data class ExerciseBGContext(
    // Pre-activity (30 min before start)
    val entryBG: Int?,
    val entryTrend: Trend?,
    val entryStability: Double?,

    // During exercise
    val minBG: Int?,
    val maxDropRate: Double?,
    val dropPer10Min: List<Double>,

    // Post-activity (4h after end)
    val lowestBG: Int?,
    val lowestBGTime: Instant?,
    val highestBG: Int?,
    val highestBGTime: Instant?,
    val postExerciseHypo: Boolean,

    // Aggregated from HC
    val avgHR: Int?,
    val maxHR: Int?,
    val totalSteps: Int?,
    val activeCalories: Double?,

    // Coverage
    val bgCoveragePercent: Double
)

@Singleton
class ExerciseBGAnalyzer @Inject constructor() {

    companion object {
        private const val PRE_WINDOW_MINUTES = 30L
        private const val POST_WINDOW_HOURS = 4L
        private const val BUCKET_MINUTES = 10L
        private const val MIN_COVERAGE = 0.50
        private const val TREND_THRESHOLD = 1.0 // mg/dL per minute
        private const val MIN_INTERVAL_MS = 30_000L // 30 seconds
        private const val MAX_INTERVAL_MS = 600_000L // 10 minutes
        private const val PERCENT = 100.0

    }

    fun analyze(
        session: StoredExerciseSession,
        readings: List<GlucoseReading>,
        heartRateSamples: List<HeartRateSample>,
        bgLowMgdl: Double
    ): ExerciseBGContext? {
        val startMs = session.startTime
        val endMs = session.endTime
        val durationMs = endMs - startMs

        if (durationMs <= 0) return null

        val duringReadings = readings
            .filter { it.ts in startMs..endMs }
            .sortedBy { it.ts }

        // Coverage check: infer sensor interval from all readings
        val sensorIntervalMs = inferSensorInterval(readings) ?: return null
        val expectedReadings = (durationMs.toDouble() / sensorIntervalMs).coerceAtLeast(1.0)
        val coverage = duringReadings.size / expectedReadings
        if (coverage < MIN_COVERAGE) return null

        val coveragePercent = (coverage * PERCENT).coerceAtMost(PERCENT)

        // Pre-activity analysis (30 min before start)
        val preWindowStart = startMs - Duration.ofMinutes(PRE_WINDOW_MINUTES).toMillis()
        val preReadings = readings
            .filter { it.ts in preWindowStart until startMs }
            .sortedBy { it.ts }

        val preAnalysis = analyzePreWindow(preReadings)

        // During exercise
        val minBG = duringReadings.minOfOrNull { it.sgv }
        val buckets = computeDropBuckets(duringReadings, startMs)
        val maxDropRate = buckets.maxOfOrNull { it }

        // Post-activity analysis (4h after end, NOT including during)
        val postWindowEnd = endMs + Duration.ofHours(POST_WINDOW_HOURS).toMillis()
        val postReadings = readings
            .filter { it.ts in (endMs + 1)..postWindowEnd }
            .sortedBy { it.ts }

        val postAnalysis = analyzePostWindow(postReadings, bgLowMgdl)

        // HR aggregation
        val avgHR = if (heartRateSamples.isNotEmpty()) {
            heartRateSamples.map { it.bpm }.average().toInt()
        } else null
        val maxHR = heartRateSamples.maxOfOrNull { it.bpm }

        return ExerciseBGContext(
            entryBG = preAnalysis.entryBG,
            entryTrend = preAnalysis.trend,
            entryStability = preAnalysis.stability,
            minBG = minBG,
            maxDropRate = maxDropRate,
            dropPer10Min = buckets,
            lowestBG = postAnalysis.lowestBG,
            lowestBGTime = postAnalysis.lowestBGTime,
            highestBG = postAnalysis.highestBG,
            highestBGTime = postAnalysis.highestBGTime,
            postExerciseHypo = postAnalysis.postExerciseHypo,
            avgHR = avgHR,
            maxHR = maxHR,
            totalSteps = session.totalSteps,
            activeCalories = session.activeCalories,
            bgCoveragePercent = coveragePercent
        )
    }

    internal fun inferSensorInterval(readings: List<GlucoseReading>): Long? {
        val sorted = readings.sortedBy { it.ts }
        if (sorted.size < 2) return null

        val intervals = sorted.zipWithNext { a, b -> b.ts - a.ts }
            .filter { it in MIN_INTERVAL_MS..MAX_INTERVAL_MS }

        if (intervals.isEmpty()) return null

        // Median of valid intervals
        val sortedIntervals = intervals.sorted()
        return sortedIntervals[sortedIntervals.size / 2]
    }

    private data class PreAnalysis(
        val entryBG: Int?,
        val trend: Trend?,
        val stability: Double?
    )

    private fun analyzePreWindow(readings: List<GlucoseReading>): PreAnalysis {
        if (readings.isEmpty()) return PreAnalysis(null, null, null)

        val entryBG = readings.last().sgv

        if (readings.size < 2) return PreAnalysis(entryBG, null, null)

        // Linear regression: x = time in minutes from first reading, y = sgv
        val firstTs = readings.first().ts
        val xs = readings.map { (it.ts - firstTs).toDouble() / TimeConstants.MS_PER_MINUTE_D }
        val ys = readings.map { it.sgv.toDouble() }

        val regression = linearRegression(xs, ys)

        val trend = when {
            regression.slope > TREND_THRESHOLD -> Trend.RISING
            regression.slope < -TREND_THRESHOLD -> Trend.FALLING
            else -> Trend.STABLE
        }

        return PreAnalysis(entryBG, trend, regression.rSquared)
    }

    private fun computeDropBuckets(
        readings: List<GlucoseReading>,
        sessionStartMs: Long
    ): List<Double> {
        if (readings.size < 2) return emptyList()

        val sessionEndMs = readings.last().ts
        val totalDuration = sessionEndMs - sessionStartMs
        val bucketMs = Duration.ofMinutes(BUCKET_MINUTES).toMillis()

        val buckets = mutableListOf<Double>()
        var bucketStart = sessionStartMs

        while (bucketStart < sessionStartMs + totalDuration) {
            val bucketEnd = bucketStart + bucketMs
            val bucketReadings = readings.filter { it.ts in bucketStart..bucketEnd }

            if (bucketReadings.size >= 2) {
                val first = bucketReadings.first().sgv
                val last = bucketReadings.last().sgv
                // Drop = first - last (positive means BG dropped)
                buckets.add((first - last).toDouble())
            }

            bucketStart = bucketEnd
        }

        return buckets
    }

    private data class PostAnalysis(
        val lowestBG: Int?,
        val lowestBGTime: Instant?,
        val highestBG: Int?,
        val highestBGTime: Instant?,
        val postExerciseHypo: Boolean
    )

    private fun analyzePostWindow(
        readings: List<GlucoseReading>,
        bgLowMgdl: Double
    ): PostAnalysis {
        if (readings.isEmpty()) return PostAnalysis(null, null, null, null, false)

        val lowest = readings.minByOrNull { it.sgv }!!
        val highest = readings.maxByOrNull { it.sgv }!!
        val postExerciseHypo = lowest.sgv < bgLowMgdl

        return PostAnalysis(
            lowestBG = lowest.sgv,
            lowestBGTime = Instant.ofEpochMilli(lowest.ts),
            highestBG = highest.sgv,
            highestBGTime = Instant.ofEpochMilli(highest.ts),
            postExerciseHypo = postExerciseHypo
        )
    }

    private data class RegressionResult(val slope: Double, val rSquared: Double)

    private fun linearRegression(xs: List<Double>, ys: List<Double>): RegressionResult {
        val n = xs.size
        if (n < 2) return RegressionResult(0.0, 0.0)

        val xMean = xs.average()
        val yMean = ys.average()

        var ssXY = 0.0
        var ssXX = 0.0
        var ssTot = 0.0

        for (i in 0 until n) {
            val dx = xs[i] - xMean
            val dy = ys[i] - yMean
            ssXY += dx * dy
            ssXX += dx * dx
            ssTot += dy * dy
        }

        if (ssXX == 0.0) return RegressionResult(0.0, 0.0)

        val slope = ssXY / ssXX
        val ssRes = ssTot - (ssXY * ssXY / ssXX)
        val rSquared = if (ssTot == 0.0) 1.0 else 1.0 - (ssRes / ssTot)

        return RegressionResult(slope, rSquared)
    }
}
