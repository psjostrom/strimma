package com.psjostrom.strimma.data.health

import com.psjostrom.strimma.data.calendar.MetabolicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CategoryStatsCalculatorTest {

    private fun context(
        entryBG: Int? = 150,
        minBG: Int? = 120,
        maxDropRate: Double? = 0.8,
        dropPer10Min: List<Double> = listOf(8.0),
        lowestBG: Int? = 110,
        highestBG: Int? = 160,
        postExerciseHypo: Boolean = false,
        avgHR: Int? = null,
        bgCoverage: Double = 90.0
    ) = ExerciseBGContext(
        entryBG = entryBG,
        entryTrend = Trend.STABLE,
        entryStability = 0.5,
        minBG = minBG,
        maxDropRate = maxDropRate,
        dropPer10Min = dropPer10Min,
        lowestBG = lowestBG,
        lowestBGTime = Instant.now(),
        highestBG = highestBG,
        highestBGTime = Instant.now(),
        postExerciseHypo = postExerciseHypo,
        avgHR = avgHR,
        maxHR = avgHR?.let { it + 20 },
        totalSteps = null,
        activeCalories = null,
        bgCoveragePercent = bgCoverage
    )

    private fun session(
        type: Int = 56, // EXERCISE_TYPE_RUNNING
        durationMin: Int = 30
    ): StoredExerciseSession {
        val start = 1_700_000_000_000L
        val end = start + durationMin * 60_000L
        return StoredExerciseSession("id_${start}_$type", type, start, end, null, null, null)
    }

    @Test
    fun `groups sessions by category`() {
        val data = listOf(
            session(type = 56) to context(),  // RUNNING
            session(type = 56) to context(),
            session(type = 56) to context(),
            session(type = 8) to context(),   // CYCLING (biking)
            session(type = 8) to context(),
            session(type = 8) to context(),
        )
        val results = CategoryStatsCalculator.computeByCategory(data, 72.0)
        assertEquals(2, results.size)
        assertTrue(results.any { it.category == ExerciseCategory.RUNNING && it.sessionCount == 3 })
        assertTrue(results.any { it.category == ExerciseCategory.CYCLING && it.sessionCount == 3 })
    }

    @Test
    fun `categories below threshold are excluded`() {
        val data = listOf(
            session(type = 56) to context(),
        )
        val results = CategoryStatsCalculator.computeByCategory(data, 72.0)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `computes correct averages`() {
        val data = listOf(
            session(type = 56) to context(entryBG = 150, minBG = 120),
            session(type = 56) to context(entryBG = 180, minBG = 100),
            session(type = 56) to context(entryBG = 160, minBG = 130),
        )
        val stats = CategoryStatsCalculator.computeByCategory(data, 72.0).first()
        assertEquals(163.3, stats.avgEntryBG, 1.0)
        assertEquals(116.7, stats.avgMinBG, 1.0)
    }

    @Test
    fun `computes hypo rate`() {
        val data = listOf(
            session(type = 56) to context(minBG = 120, postExerciseHypo = false),
            session(type = 56) to context(minBG = 60, postExerciseHypo = true),
            session(type = 56) to context(minBG = 130, postExerciseHypo = false),
        )
        val stats = CategoryStatsCalculator.computeByCategory(data, 72.0).first()
        assertEquals(1, stats.hypoCount)
        assertEquals(1.0 / 3.0, stats.hypoRate, 0.01)
    }

    @Test
    fun `groups by entry BG band`() {
        val data = listOf(
            session(type = 56) to context(entryBG = 60),   // LOW (< 72)
            session(type = 56) to context(entryBG = 100),  // LOW_RANGE (72-126)
            session(type = 56) to context(entryBG = 110),  // LOW_RANGE
            session(type = 56) to context(entryBG = 115),  // LOW_RANGE — now 3, shows
            session(type = 56) to context(entryBG = 150),  // MID_RANGE (126-180)
        )
        val stats = CategoryStatsCalculator.computeByCategory(data, 72.0).first()
        assertTrue(stats.statsByEntryBand.containsKey(BGBand.LOW_RANGE))
        assertEquals(3, stats.statsByEntryBand[BGBand.LOW_RANGE]!!.sessionCount)
        assertTrue(!stats.statsByEntryBand.containsKey(BGBand.LOW))
    }

    @Test
    fun `sessions with null entryBG are excluded from stats`() {
        val data = listOf(
            session(type = 56) to context(entryBG = null),
            session(type = 56) to context(entryBG = null),
            session(type = 56) to context(entryBG = null),
        )
        val results = CategoryStatsCalculator.computeByCategory(data, 72.0)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `computeByProfile groups across activity types`() {
        val data = listOf(
            session(type = 56) to context(), // RUNNING -> AEROBIC
            session(type = 56) to context(),
            session(type = 56) to context(),
            session(type = 8) to context(),  // CYCLING -> AEROBIC
            session(type = 8) to context(),
            session(type = 8) to context(),
        )
        val results = CategoryStatsCalculator.computeByProfile(data, 72.0, null)
        assertEquals(1, results.size)
        assertEquals(MetabolicProfile.AEROBIC, results[0].metabolicProfile)
        assertEquals(6, results[0].sessionCount)
    }
}
