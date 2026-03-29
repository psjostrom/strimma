package com.psjostrom.strimma.data.meal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MealTimeSlotTest {

    private val zone = ZoneId.of("Europe/Stockholm")

    private fun tsAt(hour: Int, minute: Int = 0): Long {
        return Instant.parse("2026-03-27T00:00:00Z")
            .atZone(ZoneId.of("UTC"))
            .withHour(hour).withMinute(minute)
            .withZoneSameLocal(zone)
            .toInstant().toEpochMilli()
    }

    @Test fun `breakfast at 07-30`() = assertEquals(MealTimeSlot.BREAKFAST, MealTimeSlot.fromTimestamp(tsAt(7, 30), zone))
    @Test fun `breakfast starts at 06-00`() = assertEquals(MealTimeSlot.BREAKFAST, MealTimeSlot.fromTimestamp(tsAt(6, 0), zone))
    @Test fun `lunch at 12-00`() = assertEquals(MealTimeSlot.LUNCH, MealTimeSlot.fromTimestamp(tsAt(12, 0), zone))
    @Test fun `lunch starts at 11-30`() = assertEquals(MealTimeSlot.LUNCH, MealTimeSlot.fromTimestamp(tsAt(11, 30), zone))
    @Test fun `lunch ends before 14-30`() = assertEquals(MealTimeSlot.LUNCH, MealTimeSlot.fromTimestamp(tsAt(14, 29), zone))
    @Test fun `snack at 14-30`() = assertEquals(MealTimeSlot.SNACK, MealTimeSlot.fromTimestamp(tsAt(14, 30), zone))
    @Test fun `dinner at 19-00`() = assertEquals(MealTimeSlot.DINNER, MealTimeSlot.fromTimestamp(tsAt(19, 0), zone))
    @Test fun `snack between breakfast and lunch`() = assertEquals(MealTimeSlot.SNACK, MealTimeSlot.fromTimestamp(tsAt(10, 30), zone))
    @Test fun `snack at 22-00`() = assertEquals(MealTimeSlot.SNACK, MealTimeSlot.fromTimestamp(tsAt(22, 0), zone))
    @Test fun `snack at 03-00`() = assertEquals(MealTimeSlot.SNACK, MealTimeSlot.fromTimestamp(tsAt(3, 0), zone))
}
