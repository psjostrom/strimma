package com.psjostrom.strimma.data.meal

import java.time.Instant
import java.time.ZoneId

enum class MealTimeSlot(val label: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    DINNER("Dinner"),
    SNACK("Snack");

    companion object {
        private const val BREAKFAST_START = 6 * 60       // 06:00
        private const val BREAKFAST_END = 10 * 60         // 10:00
        private const val LUNCH_START = 11 * 60 + 30      // 11:30
        private const val LUNCH_END = 14 * 60 + 30        // 14:30
        private const val DINNER_START = 17 * 60          // 17:00
        private const val DINNER_END = 21 * 60            // 21:00

        fun fromTimestamp(ts: Long, zone: ZoneId): MealTimeSlot {
            val localTime = Instant.ofEpochMilli(ts).atZone(zone).toLocalTime()
            val minuteOfDay = localTime.hour * 60 + localTime.minute
            return when {
                minuteOfDay in BREAKFAST_START until BREAKFAST_END -> BREAKFAST
                minuteOfDay in LUNCH_START until LUNCH_END -> LUNCH
                minuteOfDay in DINNER_START until DINNER_END -> DINNER
                else -> SNACK
            }
        }
    }
}
