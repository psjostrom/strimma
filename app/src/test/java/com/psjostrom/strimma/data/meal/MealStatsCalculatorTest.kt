package com.psjostrom.strimma.data.meal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MealStatsCalculatorTest {

    private val zone = ZoneId.of("Europe/Stockholm")

    private fun resultAt(hour: Int, carbs: Double, tir: Double, excursion: Double, recovery: Int?) =
        MealPostprandialResult(
            mealTime = Instant.parse("2026-03-27T00:00:00Z")
                .atZone(ZoneId.of("UTC")).withHour(hour)
                .withZoneSameLocal(zone).toInstant().toEpochMilli(),
            carbGrams = carbs, baselineMgdl = 108.0, peakMgdl = 108.0 + excursion,
            excursionMgdl = excursion, timeToPeakMinutes = 45,
            recoveryMinutes = recovery, tirPercent = tir, iAucMgdlMin = 500.0,
            iobAtMeal = 1.5, windowMinutes = 180, readings = emptyList()
        )

    @Test fun `groups by time slot`() {
        val results = listOf(
            resultAt(7, 30.0, 90.0, 40.0, 120),
            resultAt(8, 25.0, 85.0, 35.0, 110),
            resultAt(12, 45.0, 70.0, 60.0, 150),
            resultAt(19, 50.0, 55.0, 80.0, null),
        )
        val grouped = MealStatsCalculator.groupByTimeSlot(results, zone)
        assertEquals(3, grouped.size)
        assertEquals(2, grouped[MealTimeSlot.BREAKFAST]?.size)
        assertEquals(1, grouped[MealTimeSlot.LUNCH]?.size)
        assertEquals(1, grouped[MealTimeSlot.DINNER]?.size)
    }

    @Test fun `aggregate computes averages`() {
        val results = listOf(resultAt(7, 30.0, 90.0, 40.0, 120), resultAt(8, 25.0, 80.0, 50.0, 100))
        val agg = MealStatsCalculator.aggregate(results)
        assertEquals(2, agg.mealCount)
        assertEquals(85.0, agg.avgTirPercent, 0.1)
        assertEquals(45.0, agg.avgExcursionMgdl, 0.1)
        assertEquals(110, agg.avgRecoveryMinutes)
    }

    @Test fun `aggregate recovery averages non-null only`() {
        val results = listOf(resultAt(7, 30.0, 90.0, 40.0, 120), resultAt(8, 25.0, 80.0, 50.0, null))
        val agg = MealStatsCalculator.aggregate(results)
        assertEquals(120, agg.avgRecoveryMinutes)
    }

    @Test fun `groups by carb size`() {
        val results = listOf(
            resultAt(7, 10.0, 90.0, 20.0, 60),
            resultAt(8, 35.0, 75.0, 50.0, 120),
            resultAt(9, 65.0, 55.0, 80.0, null),
        )
        val grouped = MealStatsCalculator.groupByCarbSize(results)
        assertEquals(1, grouped[CarbSizeBucket.SMALL]?.size)
        assertEquals(1, grouped[CarbSizeBucket.MEDIUM]?.size)
        assertEquals(1, grouped[CarbSizeBucket.LARGE]?.size)
    }

    @Test fun `empty results produce empty aggregates`() {
        val agg = MealStatsCalculator.aggregate(emptyList())
        assertEquals(0, agg.mealCount)
        assertNull(agg.avgRecoveryMinutes)
    }
}
