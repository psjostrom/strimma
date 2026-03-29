package com.psjostrom.strimma.data.meal

import com.psjostrom.strimma.data.GlucoseReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class MealAgpCalculatorTest {

    private val baseTime = Instant.parse("2026-03-27T08:00:00Z").toEpochMilli()

    private fun minutes(n: Long) = Duration.ofMinutes(n).toMillis()

    private fun reading(mealTime: Long, minutesFromMeal: Long, sgv: Int) = GlucoseReading(
        ts = mealTime + minutes(minutesFromMeal),
        sgv = sgv,
        direction = "Flat",
        delta = null,
        pushed = 1
    )

    private fun mealResult(
        mealTime: Long,
        windowMinutes: Int,
        readingPairs: List<Pair<Long, Int>>
    ) = MealPostprandialResult(
        mealTime = mealTime,
        carbGrams = 50.0,
        baselineMgdl = 108.0,
        peakMgdl = readingPairs.maxOf { it.second }.toDouble(),
        excursionMgdl = readingPairs.maxOf { it.second } - 108.0,
        timeToPeakMinutes = 45,
        recoveryMinutes = null,
        tirPercent = 80.0,
        iAucMgdlMin = 500.0,
        iobAtMeal = 0.0,
        windowMinutes = windowMinutes,
        readings = readingPairs.map { (min, sgv) -> reading(mealTime, min, sgv) }
    )

    @Test
    fun `empty input returns null`() {
        assertNull(MealAgpCalculator.compute(emptyList()))
    }

    @Test
    fun `single meal produces buckets`() {
        val readings = (0L..180L step 5).map { it to 120 }
        // Need 2 meals so each bucket has >= MIN_READINGS_PER_BUCKET
        val results = listOf(
            mealResult(baseTime, 180, readings),
            mealResult(baseTime + minutes(1440), 180, readings)
        )
        val result = MealAgpCalculator.compute(results)

        assertNotNull(result)
        result!!
        assertTrue("Should have buckets", result.buckets.isNotEmpty())
        assertEquals(180, result.windowMinutes)
    }

    @Test
    fun `agp window capped at 180 min regardless of meal window`() {
        // Meal 1: recovered, 180 min window, stable at 120
        val recovered = (0L..180L step 5).map { it to 120 }
        // Meal 2: not recovered, 240 min window, rises to 200 after 180 min
        val elevated = (0L..240L step 5).map { min ->
            val sgv = if (min <= 180) 130 else 200
            min to sgv
        }

        val results = listOf(
            mealResult(baseTime, 180, recovered),
            mealResult(baseTime + minutes(1440), 240, elevated)
        )

        val agp = MealAgpCalculator.compute(results)
        assertNotNull(agp)
        agp!!

        assertEquals(180, agp.windowMinutes)

        val lastBucket = agp.buckets.last()
        assertTrue(
            "Last bucket should be at or before 180 min, was ${lastBucket.minuteFromMeal}",
            lastBucket.minuteFromMeal <= 180
        )
    }

    @Test
    fun `no survivor bias - extended meal readings beyond 180 are excluded`() {
        // Meal 1: stable at 120 for 180 min (recovered)
        val stable = (0L..180L step 5).map { it to 120 }
        // Meal 2: stable at 130 for 180 min, then spikes to 250 at 181-240 min (not recovered)
        val spikeAfter180 = (0L..180L step 5).map { it to 130 } +
            (185L..240L step 5).map { it to 250 }

        val results = listOf(
            mealResult(baseTime, 180, stable),
            mealResult(baseTime + minutes(1440), 240, spikeAfter180)
        )

        val agp = MealAgpCalculator.compute(results)
        assertNotNull(agp)
        agp!!

        // The 250 spike readings should NOT appear in any bucket
        val maxMedian = agp.buckets.maxOf { it.p50 }
        assertTrue(
            "Median should never reach 250 (spike data excluded), was $maxMedian",
            maxMedian < 200
        )
    }

    @Test
    fun `returns null when all buckets have fewer than 2 readings`() {
        // Single meal with one reading — no bucket meets MIN_READINGS_PER_BUCKET
        val sparse = listOf(0L to 120)
        val result = MealAgpCalculator.compute(listOf(mealResult(baseTime, 180, sparse)))

        assertNull("All buckets under threshold should yield null", result)
    }

    @Test
    fun `percentiles computed across multiple meals`() {
        // 3 meals with different BG at t=0: 100, 120, 140
        // Each has readings every 5 min for 30 min at a constant value
        val meals = listOf(100, 120, 140).mapIndexed { i, sgv ->
            val readings = (0L..30L step 5).map { it to sgv }
            mealResult(baseTime + minutes(i * 1440L), 180, readings)
        }

        val agp = MealAgpCalculator.compute(meals)
        assertNotNull(agp)
        agp!!

        val firstBucket = agp.buckets.first()
        assertEquals(0, firstBucket.minuteFromMeal)
        // Median of [100, 120, 140] = 120
        assertEquals(120.0, firstBucket.p50, 1.0)
        // p25 should be closer to 100, p75 closer to 140
        assertTrue("p25 should be <= median", firstBucket.p25 <= firstBucket.p50)
        assertTrue("p75 should be >= median", firstBucket.p75 >= firstBucket.p50)
    }
}
