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

private const val TIR_GOOD_THRESHOLD = 80.0
private const val TIR_MODERATE_THRESHOLD = 50.0

fun MealPostprandialResult.tirRating(): TirRating = when {
    tirPercent >= TIR_GOOD_THRESHOLD -> TirRating.GOOD
    tirPercent >= TIR_MODERATE_THRESHOLD -> TirRating.MODERATE
    else -> TirRating.POOR
}
