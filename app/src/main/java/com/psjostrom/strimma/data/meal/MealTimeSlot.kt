package com.psjostrom.strimma.data.meal

import java.time.Instant
import java.time.ZoneId

private const val MINUTES_PER_HOUR = 60
private const val BREAKFAST_START_HOUR = 6
private const val BREAKFAST_END_HOUR = 10
private const val LUNCH_START_HOUR = 11
private const val LUNCH_START_MINUTE_OFFSET = 30
private const val LUNCH_END_HOUR = 14
private const val LUNCH_END_MINUTE_OFFSET = 30
private const val DINNER_START_HOUR = 17
private const val DINNER_END_HOUR = 21

data class MealTimeSlotConfig(
    val breakfastStart: Int = BREAKFAST_START_HOUR * MINUTES_PER_HOUR,
    val breakfastEnd: Int = BREAKFAST_END_HOUR * MINUTES_PER_HOUR,
    val lunchStart: Int = LUNCH_START_HOUR * MINUTES_PER_HOUR + LUNCH_START_MINUTE_OFFSET,
    val lunchEnd: Int = LUNCH_END_HOUR * MINUTES_PER_HOUR + LUNCH_END_MINUTE_OFFSET,
    val dinnerStart: Int = DINNER_START_HOUR * MINUTES_PER_HOUR,
    val dinnerEnd: Int = DINNER_END_HOUR * MINUTES_PER_HOUR
)

enum class MealTimeSlot(val label: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    DINNER("Dinner"),
    SNACK("Snack");

    companion object {
        fun fromTimestamp(
            ts: Long,
            zone: ZoneId,
            config: MealTimeSlotConfig = MealTimeSlotConfig()
        ): MealTimeSlot {
            val localTime = Instant.ofEpochMilli(ts).atZone(zone).toLocalTime()
            val minuteOfDay = localTime.hour * MINUTES_PER_HOUR + localTime.minute
            return when {
                minuteOfDay in config.breakfastStart until config.breakfastEnd -> BREAKFAST
                minuteOfDay in config.lunchStart until config.lunchEnd -> LUNCH
                minuteOfDay in config.dinnerStart until config.dinnerEnd -> DINNER
                else -> SNACK
            }
        }
    }
}
