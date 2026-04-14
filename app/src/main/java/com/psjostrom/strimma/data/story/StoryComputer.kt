package com.psjostrom.strimma.data.story

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseStats
import com.psjostrom.strimma.data.StatsCalculator
import com.psjostrom.strimma.data.Treatment
import com.psjostrom.strimma.data.meal.MealAnalysisParams
import com.psjostrom.strimma.data.meal.MealAnalyzer
import com.psjostrom.strimma.data.meal.MealStatsCalculator
import com.psjostrom.strimma.data.meal.MealTimeSlot
import com.psjostrom.strimma.data.meal.MealTimeSlotConfig
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class StoryParams(
    val month: YearMonth,
    val readings: List<GlucoseReading>,
    val previousReadings: List<GlucoseReading>,
    val carbTreatments: List<Treatment>,
    val allTreatments: List<Treatment>,
    val bgLow: Double,
    val bgHigh: Double,
    val tauMinutes: Double,
    val zone: ZoneId,
    val mealAnalyzer: MealAnalyzer? = null,
    val mealTimeSlotConfig: MealTimeSlotConfig = MealTimeSlotConfig()
)

object StoryComputer {

    private const val MIN_DAYS = 7

    fun compute(params: StoryParams): StoryData? {
        val dayCount = countDays(params.readings, params.zone)
        if (dayCount < MIN_DAYS) return null

        val stats = StatsCalculator.compute(
            params.readings, params.bgLow, params.bgHigh, "month"
        ) ?: return null

        val previousStats = computePreviousStats(params)
        val stability = computeStability(params)
        val events = computeEvents(params)

        val timeOfDay = TimeOfDayComputer.compute(
            params.readings, params.bgLow, params.bgHigh, params.zone
        )

        val meals = computeMeals(params)

        val monthLabel = params.month.month.getDisplayName(
            java.time.format.TextStyle.FULL, java.util.Locale.getDefault()
        )
        val narrative = StoryNarrative.generate(
            stats, previousStats, stability, events, timeOfDay, monthLabel
        )

        return StoryData(
            year = params.month.year,
            month = params.month.monthValue,
            readingCount = params.readings.size,
            dayCount = dayCount,
            stats = stats,
            previousStats = previousStats,
            stability = stability,
            events = events,
            timeOfDay = timeOfDay,
            meals = meals,
            narrative = narrative
        )
    }

    private fun countDays(readings: List<GlucoseReading>, zone: ZoneId): Int =
        readings.map { Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() }.distinct().size

    private fun computePreviousStats(params: StoryParams): GlucoseStats? {
        if (params.previousReadings.isEmpty()) return null
        val prevDays = countDays(params.previousReadings, params.zone)
        if (prevDays < MIN_DAYS) return null
        return StatsCalculator.compute(params.previousReadings, params.bgLow, params.bgHigh, "month")
    }

    private fun computeStability(params: StoryParams): StabilityData {
        val flatlines = FlatlineComputer.findFlatlines(params.readings)
        return StabilityData(
            longestFlatline = flatlines.maxByOrNull { it.durationMinutes },
            flatlineCount = flatlines.size,
            longestInRangeStreak = FlatlineComputer.longestInRangeStreak(
                params.readings, params.bgLow, params.bgHigh
            ),
            steadiestDay = FlatlineComputer.steadiestDay(
                params.readings, params.bgLow, params.bgHigh, params.zone
            )
        )
    }

    private fun computeEvents(params: StoryParams): EventData {
        val currentEvents = EventComputer.compute(params.readings)
        val previousEvents = if (params.previousReadings.isNotEmpty()) {
            EventComputer.compute(params.previousReadings)
        } else null
        return EventData(
            lowEvents = currentEvents.lowEvents,
            highEvents = currentEvents.highEvents,
            previousLowEvents = previousEvents?.lowEvents,
            previousHighEvents = previousEvents?.highEvents,
            belowPercent = currentEvents.belowPercent,
            abovePercent = currentEvents.abovePercent,
            avgLowDurationMinutes = currentEvents.avgLowDurationMinutes,
            avgHighDurationMinutes = currentEvents.avgHighDurationMinutes
        )
    }

    private fun computeMeals(params: StoryParams): MealStoryData? {
        if (params.carbTreatments.isEmpty() || params.mealAnalyzer == null) return null

        val sortedCarbs = params.carbTreatments.sortedBy { it.createdAt }
        val results = sortedCarbs.mapIndexedNotNull { i, meal ->
            val nextMealTime = sortedCarbs.getOrNull(i + 1)?.createdAt
            params.mealAnalyzer.analyze(
                meal, params.readings,
                MealAnalysisParams(
                    params.bgLow, params.bgHigh, nextMealTime,
                    params.allTreatments, params.tauMinutes
                )
            )
        }
        if (results.isEmpty()) return null

        val bySlot = MealStatsCalculator.groupByTimeSlot(
            results, params.zone, params.mealTimeSlotConfig
        )
        val slotSummaries = bySlot.mapValues { (slot, meals) ->
            val agg = MealStatsCalculator.aggregate(meals)
            MealSlotSummary(slot, agg.avgTirPercent, agg.mealCount)
        }

        val best = slotSummaries.values.filter { it.mealCount >= 2 }.maxByOrNull { it.tirPercent }
        val worst = slotSummaries.values.filter { it.mealCount >= 2 }.minByOrNull { it.tirPercent }
        val avgExcursion = bySlot.mapValues { (_, meals) ->
            MealStatsCalculator.aggregate(meals).avgExcursionMgdl
        }

        return MealStoryData(
            mealCount = results.size,
            bestSlot = best,
            worstSlot = worst,
            avgExcursionBySlot = avgExcursion
        )
    }
}
