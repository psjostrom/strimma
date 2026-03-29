package com.psjostrom.strimma.data.meal

import java.time.ZoneId

data class MealAggregateStats(
    val mealCount: Int,
    val avgTirPercent: Double,
    val avgExcursionMgdl: Double,
    val avgRecoveryMinutes: Int?
)

object MealStatsCalculator {

    fun groupByTimeSlot(
        results: List<MealPostprandialResult>,
        zone: ZoneId,
        config: MealTimeSlotConfig = MealTimeSlotConfig()
    ): Map<MealTimeSlot, List<MealPostprandialResult>> =
        results.groupBy { MealTimeSlot.fromTimestamp(it.mealTime, zone, config) }

    fun groupByCarbSize(
        results: List<MealPostprandialResult>
    ): Map<CarbSizeBucket, List<MealPostprandialResult>> =
        results.groupBy { CarbSizeBucket.fromGrams(it.carbGrams) }

    fun aggregate(results: List<MealPostprandialResult>): MealAggregateStats {
        if (results.isEmpty()) return MealAggregateStats(0, 0.0, 0.0, null)
        val recoveries = results.mapNotNull { it.recoveryMinutes }
        return MealAggregateStats(
            mealCount = results.size,
            avgTirPercent = results.map { it.tirPercent }.average(),
            avgExcursionMgdl = results.map { it.excursionMgdl }.average(),
            avgRecoveryMinutes = if (recoveries.isEmpty()) null else recoveries.average().toInt()
        )
    }
}
