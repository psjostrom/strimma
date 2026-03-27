package com.psjostrom.strimma.data.calendar

import com.psjostrom.strimma.data.GlucoseUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreActivityAssessorTest {

    private val targetLow = 126f  // 7 mmol/L
    private val targetHigh = 162f // 9 mmol/L
    private val workoutInMs = 2 * 3600_000L

    private fun assess(
        bgMgdl: Int,
        velocityMgdlPerMin: Double? = 0.0,
        iob: Double = 0.0,
        forecastBgAt30min: Double? = null,
        timeToWorkoutMs: Long = workoutInMs
    ) = PreActivityAssessor.assess(
        currentBgMgdl = bgMgdl,
        velocityMgdlPerMin = velocityMgdlPerMin,
        iob = iob,
        forecastBgAt30minMgdl = forecastBgAt30min,
        timeToWorkoutMs = timeToWorkoutMs,
        targetLowMgdl = targetLow,
        targetHighMgdl = targetHigh
    )

    // --- BG Level ---

    @Test
    fun `bg below 81 is wait`() {
        val result = assess(80)
        assertEquals(ReadinessLevel.WAIT, result.readiness)
        assertTrue(result.reasons.any { it.message.contains("too low") })
    }

    @Test
    fun `bg at 81 is caution when below target`() {
        val result = assess(81)
        assertEquals(ReadinessLevel.CAUTION, result.readiness)
        assertTrue(result.reasons.any { it.message.contains("below target") })
    }

    @Test
    fun `bg in target range is ready`() {
        val result = assess(140)
        assertEquals(ReadinessLevel.READY, result.readiness)
    }

    @Test
    fun `bg above 252 is caution`() {
        val result = assess(253)
        assertEquals(ReadinessLevel.CAUTION, result.readiness)
        assertTrue(result.reasons.any { it.message.contains("high") })
    }

    @Test
    fun `bg above target but below 252 is ready`() {
        val result = assess(200)
        assertEquals(ReadinessLevel.READY, result.readiness)
    }

    // --- Trend Slope ---

    @Test
    fun `fast falling slope is wait`() {
        // BG=150 is above compound threshold (144), so only trend fires
        val result = assess(150, velocityMgdlPerMin = -1.0)
        assertEquals(ReadinessLevel.WAIT, result.readiness)
        assertTrue(result.reasons.any { it.message.contains("dropping fast") })
    }

    @Test
    fun `moderate falling slope is caution`() {
        // BG=150 is above compound threshold (144), so only trend fires
        val result = assess(150, velocityMgdlPerMin = -0.7)
        assertEquals(ReadinessLevel.CAUTION, result.readiness)
        assertTrue(result.reasons.any { it.message.contains("trending down") })
    }

    @Test
    fun `stable slope is ready`() {
        val result = assess(150, velocityMgdlPerMin = -0.3)
        assertEquals(ReadinessLevel.READY, result.readiness)
    }

    @Test
    fun `null velocity treats trend as ready`() {
        val result = assess(150, velocityMgdlPerMin = null)
        assertEquals(ReadinessLevel.READY, result.readiness)
    }

    // --- Compound Rule ---

    @Test
    fun `bg below 144 and falling is wait`() {
        val result = assess(130, velocityMgdlPerMin = -0.6)
        assertEquals(ReadinessLevel.WAIT, result.readiness)
        assertTrue(result.reasons.any { it.message.contains("below 8 and falling") })
    }

    @Test
    fun `bg at 144 and falling is not compound`() {
        val result = assess(144, velocityMgdlPerMin = -0.6)
        assertEquals(ReadinessLevel.CAUTION, result.readiness)
        assertFalse(result.reasons.any { it.message.contains("below 8 and falling") })
    }

    // --- 30-min Forecast ---

    @Test
    fun `forecast below 99 is caution`() {
        val result = assess(140, forecastBgAt30min = 90.0)
        assertEquals(ReadinessLevel.CAUTION, result.readiness)
        assertTrue(result.reasons.any { it.message.contains("Forecast") })
    }

    @Test
    fun `forecast above 99 adds no reason`() {
        val result = assess(140, forecastBgAt30min = 120.0)
        assertEquals(ReadinessLevel.READY, result.readiness)
    }

    // --- IOB Carb Stacking ---

    @Test
    fun `iob below 0_5 adds no carbs`() {
        val result = assess(140, iob = 0.4)
        assertNull(result.carbRecommendation)
    }

    @Test
    fun `iob at 0_5 adds carbs`() {
        val result = assess(140, iob = 0.5)
        assertNotNull(result.carbRecommendation)
        assertEquals(5, result.carbRecommendation!!.totalGrams)
    }

    @Test
    fun `iob at 1_0 adds 10g`() {
        val result = assess(140, iob = 1.0)
        assertEquals(10, result.carbRecommendation!!.totalGrams)
    }

    // --- Base Carbs ---

    @Test
    fun `hypo bg gives 20g base`() {
        val result = assess(75, iob = 0.0)
        assertEquals(20, result.carbRecommendation!!.totalGrams)
    }

    @Test
    fun `bg below target gives 15g base`() {
        val result = assess(110, iob = 0.0)
        assertEquals(15, result.carbRecommendation!!.totalGrams)
    }

    @Test
    fun `compound rule gives 20g base plus iob`() {
        val result = assess(130, velocityMgdlPerMin = -0.6, iob = 1.0)
        assertEquals(30, result.carbRecommendation!!.totalGrams)
    }

    // --- Timing ---

    @Test
    fun `workout more than 45 min away suggests 30 min before`() {
        val result = assess(110, timeToWorkoutMs = 60 * 60_000L)
        assertTrue(result.carbRecommendation!!.timingSuggestion.contains("30 min"))
    }

    @Test
    fun `workout 15 to 45 min away suggests now`() {
        val result = assess(110, timeToWorkoutMs = 30 * 60_000L)
        assertEquals("now", result.carbRecommendation!!.timingSuggestion)
    }

    @Test
    fun `workout less than 15 min away suggests immediately`() {
        val result = assess(110, timeToWorkoutMs = 10 * 60_000L)
        assertEquals("immediately", result.carbRecommendation!!.timingSuggestion)
    }

    // --- Worst-of-all ---

    @Test
    fun `wait from any dimension makes overall wait`() {
        // BG=150 in range but fast-falling — trend alone causes wait
        val result = assess(150, velocityMgdlPerMin = -1.0)
        assertEquals(ReadinessLevel.WAIT, result.readiness)
    }

    @Test
    fun `caution from any dimension makes overall caution`() {
        // BG=150 in range but moderate falling — trend alone causes caution
        val result = assess(150, velocityMgdlPerMin = -0.7)
        assertEquals(ReadinessLevel.CAUTION, result.readiness)
    }

    // --- Suggestions ---

    @Test
    fun `fast falling slope suggests hold off`() {
        val result = assess(150, velocityMgdlPerMin = -1.0)
        assertTrue(result.suggestions.any { it.contains("Hold off") })
    }

    @Test
    fun `compound rule suggests eat carbs and wait for upward trend`() {
        val result = assess(130, velocityMgdlPerMin = -0.6)
        assertTrue(result.suggestions.any { it.contains("Eat") && it.contains("wait for upward trend") })
    }

    @Test
    fun `hypo suggests eat carbs and wait for upward trend`() {
        val result = assess(75)
        assertTrue(result.suggestions.any { it.contains("Eat") && it.contains("wait for upward trend") })
    }

    @Test
    fun `low bg suggests have carbs before starting`() {
        val result = assess(110)
        assertTrue(result.suggestions.any { it.contains("Have") && it.contains("before starting") })
    }

    @Test
    fun `iob included in carb suggestion factor text`() {
        val result = assess(110, iob = 1.0)
        assertTrue(result.suggestions.any { it.contains("IOB") })
    }

    @Test
    fun `forecast below 5_5 adds forecast suggestion in mmol`() {
        val result = assess(140, forecastBgAt30min = 90.0)
        assertTrue(result.suggestions.any { it.contains("Forecast") && it.contains("5.0") && it.contains("30 min") })
    }

    @Test
    fun `forecast suggestion respects mgdl unit`() {
        val result = PreActivityAssessor.assess(
            currentBgMgdl = 140,
            velocityMgdlPerMin = 0.0,
            iob = 0.0,
            forecastBgAt30minMgdl = 90.0,
            timeToWorkoutMs = workoutInMs,
            targetLowMgdl = targetLow,
            targetHighMgdl = targetHigh,
            glucoseUnit = GlucoseUnit.MGDL
        )
        assertTrue(result.suggestions.any { it.contains("Forecast") && it.contains("90") && it.contains("30 min") })
    }

    @Test
    fun `workout more than 1h away adds recheck suggestion`() {
        val result = assess(140, timeToWorkoutMs = 2 * 3600_000L)
        assertTrue(result.suggestions.any { it.contains("Re-check") })
    }

    @Test
    fun `workout less than 1h away has no recheck suggestion`() {
        val result = assess(140, timeToWorkoutMs = 30 * 60_000L)
        assertFalse(result.suggestions.any { it.contains("Re-check") })
    }

    @Test
    fun `in-range stable bg has no suggestions except recheck`() {
        val result = assess(140)
        assertTrue(result.suggestions.all { it.contains("Re-check") })
    }

    @Test
    fun `dropping fast in range gives suggestion but no carbs`() {
        val result = assess(150, velocityMgdlPerMin = -1.0)
        assertNull(result.carbRecommendation)
        assertTrue(result.suggestions.any { it.contains("Hold off") })
    }

    // --- Boundary and edge cases ---

    @Test
    fun `rising slope is ready`() {
        val result = assess(150, velocityMgdlPerMin = 1.0)
        assertEquals(ReadinessLevel.READY, result.readiness)
    }

    @Test
    fun `null forecast adds no reason`() {
        val result = assess(140, forecastBgAt30min = null)
        assertEquals(ReadinessLevel.READY, result.readiness)
    }

    @Test
    fun `forecast at exactly 99 adds no reason`() {
        // FORECAST_LOW uses strict less-than, so 99 is not low
        val result = assess(140, forecastBgAt30min = 99.0)
        assertEquals(ReadinessLevel.READY, result.readiness)
    }

    @Test
    fun `bg below 144 with null velocity is not compound`() {
        // BG=120 is below targetLow (126) but null velocity prevents compound rule
        val result = assess(120, velocityMgdlPerMin = null)
        assertEquals(ReadinessLevel.CAUTION, result.readiness)
        assertTrue(result.reasons.any { it.message.contains("below target") })
        assertFalse(result.reasons.any { it.message.contains("below 8 and falling") })
    }

    @Test
    fun `workout at exactly 45 min suggests 30 min before`() {
        // 45 min is NOT < TIMING_SOON_MS (45 min), so it falls to "~30 min before"
        val result = assess(110, timeToWorkoutMs = 45 * 60_000L)
        assertTrue(result.carbRecommendation!!.timingSuggestion.contains("30 min"))
    }

    @Test
    fun `workout at exactly 15 min suggests now`() {
        // 15 min is NOT < 15 min, so it falls into the 15-45 range
        val result = assess(110, timeToWorkoutMs = 15 * 60_000L)
        assertEquals("now", result.carbRecommendation!!.timingSuggestion)
    }
}
