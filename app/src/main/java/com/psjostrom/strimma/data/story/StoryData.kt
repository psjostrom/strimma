package com.psjostrom.strimma.data.story

import com.psjostrom.strimma.data.GlucoseStats
import com.psjostrom.strimma.data.meal.MealTimeSlot
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class StoryData(
    val year: Int,
    val month: Int,
    val readingCount: Int,
    val dayCount: Int,
    val stats: GlucoseStats,
    val previousStats: GlucoseStats?,
    val stability: StabilityData,
    val events: EventData,
    val timeOfDay: TimeOfDayData,
    val meals: MealStoryData?,
    val narrative: String
) {
    val monthLabel: String
        get() = YearMonth.of(year, month).month
            .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
}

data class StabilityData(
    val longestFlatline: FlatlineStretch?,
    val flatlineCount: Int,
    val longestInRangeStreak: InRangeStreak?,
    val steadiestDay: SteadiestDay?
)

data class FlatlineStretch(
    val startTs: Long,
    val endTs: Long,
    val durationMinutes: Int,
    val maxVariationMgdl: Double
)

data class InRangeStreak(
    val startTs: Long,
    val endTs: Long,
    val durationMinutes: Int
)

data class SteadiestDay(
    val date: LocalDate,
    val cv: Double,
    val tirPercent: Double
)

data class EventData(
    val lowEvents: Int,
    val highEvents: Int,
    val previousLowEvents: Int?,
    val previousHighEvents: Int?,
    val belowPercent: Double,
    val abovePercent: Double,
    val avgLowDurationMinutes: Int?,
    val avgHighDurationMinutes: Int?
)

data class TimeOfDayData(
    val blocks: List<TimeBlockStats>
)

data class TimeBlockStats(
    val name: String,
    val startHour: Int,
    val endHour: Int,
    val tirPercent: Double,
    val readingCount: Int
)

data class MealStoryData(
    val mealCount: Int,
    val bestSlot: MealSlotSummary?,
    val worstSlot: MealSlotSummary?,
    val avgExcursionBySlot: Map<MealTimeSlot, Double>
)

data class MealSlotSummary(
    val slot: MealTimeSlot,
    val tirPercent: Double,
    val mealCount: Int
)

fun YearMonth.toMillisRange(zone: ZoneId): Pair<Long, Long> {
    val start = atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val endExclusive = plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to (endExclusive - 1)
}
