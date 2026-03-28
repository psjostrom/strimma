package com.psjostrom.strimma.data.meal

import java.time.Instant
import java.time.ZoneId

data class MealTimeSlotConfig(
    val breakfastStart: Int = 6 * 60,
    val breakfastEnd: Int = 10 * 60,
    val lunchStart: Int = 11 * 60 + 30,
    val lunchEnd: Int = 14 * 60 + 30,
    val dinnerStart: Int = 17 * 60,
    val dinnerEnd: Int = 21 * 60
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
            val minuteOfDay = localTime.hour * 60 + localTime.minute
            return when {
                minuteOfDay in config.breakfastStart until config.breakfastEnd -> BREAKFAST
                minuteOfDay in config.lunchStart until config.lunchEnd -> LUNCH
                minuteOfDay in config.dinnerStart until config.dinnerEnd -> DINNER
                else -> SNACK
            }
        }
    }
}
