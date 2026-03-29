package com.psjostrom.strimma.data.meal

import com.psjostrom.strimma.data.AgpCalculator

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
    val maxWindowMinutes: Int
)

object MealAgpCalculator {

    private const val BUCKET_MINUTES = 5
    private const val P5 = 5.0
    private const val P25 = 25.0
    private const val P50 = 50.0
    private const val P75 = 75.0
    private const val P95 = 95.0
    private const val MS_PER_MINUTE = 60_000L
    private const val MIN_READINGS_PER_BUCKET = 2

    fun compute(results: List<MealPostprandialResult>): MealAgpResult? {
        if (results.isEmpty()) return null

        val maxWindow = results.maxOf { it.windowMinutes }
        val bucketCount = maxWindow / BUCKET_MINUTES + 1
        val bucketMap = Array(bucketCount) { mutableListOf<Double>() }

        for (result in results) {
            for (reading in result.readings) {
                val minuteFromMeal = ((reading.ts - result.mealTime) / MS_PER_MINUTE).toInt()
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

        return if (buckets.isEmpty()) null else MealAgpResult(buckets, maxWindow)
    }
}
