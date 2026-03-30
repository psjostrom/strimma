package com.psjostrom.strimma.data.meal

import com.psjostrom.strimma.data.AgpCalculator
import com.psjostrom.strimma.data.TimeConstants

data class MealAgpBucket(
    val minuteFromMeal: Int,
    val p5: Double,
    val p25: Double,
    val p50: Double,
    val p75: Double,
    val p95: Double,
    val count: Int
)

data class MealAgpResult(
    val buckets: List<MealAgpBucket>,
    val windowMinutes: Int
)

object MealAgpCalculator {

    private const val BUCKET_MINUTES = 5
    private const val AGP_WINDOW_MINUTES = 180
    private const val P5 = 5.0
    private const val P25 = 25.0
    private const val P50 = 50.0
    private const val P75 = 75.0
    private const val P95 = 95.0

    private const val MIN_READINGS_PER_BUCKET = 2

    fun compute(results: List<MealPostprandialResult>): MealAgpResult? {
        if (results.isEmpty()) return null

        val bucketCount = AGP_WINDOW_MINUTES / BUCKET_MINUTES + 1
        val bucketMap = Array(bucketCount) { mutableListOf<Double>() }

        for (result in results) {
            for (reading in result.readings) {
                val minuteFromMeal = ((reading.ts - result.mealTime) / TimeConstants.MS_PER_MINUTE_L).toInt()
                if (minuteFromMeal < 0) continue
                val bucketIndex = minuteFromMeal / BUCKET_MINUTES
                if (bucketIndex < bucketCount) {
                    bucketMap[bucketIndex].add(reading.sgv.toDouble())
                }
            }
        }

        val buckets = bucketMap.mapIndexedNotNull { index, values ->
            if (values.size < MIN_READINGS_PER_BUCKET) return@mapIndexedNotNull null
            val sorted = values.sorted()
            MealAgpBucket(
                minuteFromMeal = index * BUCKET_MINUTES,
                p5 = AgpCalculator.percentile(sorted, P5),
                p25 = AgpCalculator.percentile(sorted, P25),
                p50 = AgpCalculator.percentile(sorted, P50),
                p75 = AgpCalculator.percentile(sorted, P75),
                p95 = AgpCalculator.percentile(sorted, P95),
                count = sorted.size
            )
        }

        return if (buckets.isEmpty()) null else MealAgpResult(buckets, AGP_WINDOW_MINUTES)
    }
}
