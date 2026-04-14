package com.psjostrom.strimma.data.story

import com.psjostrom.strimma.data.GlucoseReading
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

object StoryComputer {

    private const val MIN_DAYS = 7

    fun compute(
        month: YearMonth,
        readings: List<GlucoseReading>,
        previousReadings: List<GlucoseReading>,
        carbTreatments: List<Treatment>,
        allTreatments: List<Treatment>,
        bgLow: Double,
        bgHigh: Double,
        tauMinutes: Double,
        zone: ZoneId,
        mealAnalyzer: MealAnalyzer? = null,
        mealTimeSlotConfig: MealTimeSlotConfig = MealTimeSlotConfig()
    ): StoryData? {
        val dayCount = readings.map { Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() }
            .distinct().size
        if (dayCount < MIN_DAYS) return null

        val stats = StatsCalculator.compute(readings, bgLow, bgHigh, "month") ?: return null

        val previousStats = if (previousReadings.isNotEmpty()) {
            val prevDays = previousReadings.map {
                Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate()
            }.distinct().size
            if (prevDays >= MIN_DAYS) StatsCalculator.compute(previousReadings, bgLow, bgHigh, "month")
            else null
        } else null

        val flatlines = FlatlineComputer.findFlatlines(readings)
        val stability = StabilityData(
            longestFlatline = flatlines.maxByOrNull { it.durationMinutes },
            flatlineCount = flatlines.size,
            longestInRangeStreak = FlatlineComputer.longestInRangeStreak(readings, bgLow, bgHigh),
            steadiestDay = FlatlineComputer.steadiestDay(readings, bgLow, bgHigh, zone)
        )

        val currentEvents = EventComputer.compute(readings)
        val previousEvents = if (previousReadings.isNotEmpty()) {
            EventComputer.compute(previousReadings)
        } else null
        val events = EventData(
            lowEvents = currentEvents.lowEvents,
            highEvents = currentEvents.highEvents,
            previousLowEvents = previousEvents?.lowEvents,
            previousHighEvents = previousEvents?.highEvents,
            belowPercent = currentEvents.belowPercent,
            abovePercent = currentEvents.abovePercent,
            avgLowDurationMinutes = currentEvents.avgLowDurationMinutes,
            avgHighDurationMinutes = currentEvents.avgHighDurationMinutes
        )

        val timeOfDay = TimeOfDayComputer.compute(readings, bgLow, bgHigh, zone)

        val meals = computeMeals(
            carbTreatments, readings, allTreatments, bgLow, bgHigh, tauMinutes, zone, mealAnalyzer,
            mealTimeSlotConfig
        )

        val monthLabel = month.month.getDisplayName(
            java.time.format.TextStyle.FULL, java.util.Locale.getDefault()
        )
        val narrative = StoryNarrative.generate(
            stats, previousStats, stability, events, timeOfDay, monthLabel
        )

        return StoryData(
            year = month.year,
            month = month.monthValue,
            readingCount = readings.size,
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

    private fun computeMeals(
        carbTreatments: List<Treatment>,
        readings: List<GlucoseReading>,
        allTreatments: List<Treatment>,
        bgLow: Double,
        bgHigh: Double,
        tauMinutes: Double,
        zone: ZoneId,
        mealAnalyzer: MealAnalyzer?,
        mealTimeSlotConfig: MealTimeSlotConfig
    ): MealStoryData? {
        if (carbTreatments.isEmpty() || mealAnalyzer == null) return null

        val sortedCarbs = carbTreatments.sortedBy { it.createdAt }
        android.util.Log.w("StoryMeals", "Carb treatments: ${sortedCarbs.size}, readings: ${readings.size}")
        val results = sortedCarbs.mapIndexedNotNull { i, meal ->
            val nextMealTime = sortedCarbs.getOrNull(i + 1)?.createdAt
            val result = mealAnalyzer.analyze(
                meal, readings,
                MealAnalysisParams(bgLow, bgHigh, nextMealTime, allTreatments, tauMinutes)
            )
            if (result == null) {
                val mealTs = meal.createdAt
                val dt = java.time.Instant.ofEpochMilli(mealTs).atZone(zone)
                val preStart = mealTs - 15 * 60 * 1000L
                val preCount = readings.count { it.ts in preStart until mealTs }
                val postEnd = mealTs + 180 * 60 * 1000L
                val postCount = readings.count { it.ts in (mealTs + 1)..postEnd }
                android.util.Log.w("StoryMeals", "FILTERED: ${dt.toLocalDateTime()} carbs=${meal.carbs}g " +
                    "preReadings=$preCount postReadings=$postCount")
            }
            result
        }
        android.util.Log.w("StoryMeals", "Analyzed: ${results.size} of ${sortedCarbs.size}")

        // Log time slot distribution
        val bySlotDebug = MealStatsCalculator.groupByTimeSlot(results, zone, mealTimeSlotConfig)
        bySlotDebug.forEach { (slot, meals) ->
            android.util.Log.w("StoryMeals", "Slot $slot: ${meals.size} meals, " +
                "hours=${meals.map { java.time.Instant.ofEpochMilli(it.mealTime).atZone(zone).hour }}")
        }

        if (results.isEmpty()) return null

        val bySlot = MealStatsCalculator.groupByTimeSlot(results, zone, mealTimeSlotConfig)
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
