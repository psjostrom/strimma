package com.psjostrom.strimma.data.meal

import com.psjostrom.strimma.data.GlucoseReading

data class MealPostprandialResult(
    val mealTime: Long,
    val carbGrams: Double,
    val baselineMgdl: Double,
    val peakMgdl: Double,
    val excursionMgdl: Double,
    val timeToPeakMinutes: Int,
    val recoveryMinutes: Int?,
    val tirPercent: Double,
    val iAucMgdlMin: Double,
    val iobAtMeal: Double,
    val windowMinutes: Int,
    val readings: List<GlucoseReading>
)

enum class TirRating { GOOD, MODERATE, POOR }

fun MealPostprandialResult.tirRating(): TirRating = when {
    tirPercent >= 80.0 -> TirRating.GOOD
    tirPercent >= 50.0 -> TirRating.MODERATE
    else -> TirRating.POOR
}
