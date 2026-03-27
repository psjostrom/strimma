package com.psjostrom.strimma.ui

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.calendar.GuidanceState
import com.psjostrom.strimma.data.calendar.ReadinessLevel
import com.psjostrom.strimma.data.calendar.WorkoutCategory
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceStateTest {

    private val now = 1_700_000_000_000L
    private val twoHoursFromNow = now + 2 * 3600_000L
    private val targetLow = 126f
    private val targetHigh = 162f
    private val bgLow = 70.0
    private val bgHigh = 180.0

    private fun event(
        title: String = "Easy Run",
        startTime: Long = twoHoursFromNow,
        category: WorkoutCategory = WorkoutCategory.EASY
    ) = WorkoutEvent(title, startTime, startTime + 3600_000L, category, 1L)

    private fun reading(sgv: Int = 140, direction: String = "FLAT", minutesAgo: Int = 0) =
        GlucoseReading(
            ts = now - minutesAgo * 60_000L,
            sgv = sgv,
            direction = direction,
            delta = null,
            pushed = 1
        )

    private fun compute(
        event: WorkoutEvent? = event(),
        latest: GlucoseReading? = reading(),
        allReadings: List<GlucoseReading> = listOf(reading()),
        iob: Double = 0.0
    ) = MainViewModel.computeGuidance(
        event, latest, allReadings, iob,
        targetLow, targetHigh, bgLow, bgHigh, nowMs = now
    )

    // --- Null guards ---

    @Test
    fun `null event returns NoWorkout`() {
        val result = compute(event = null)
        assertEquals(GuidanceState.NoWorkout, result)
    }

    @Test
    fun `null latest reading returns NoWorkout`() {
        val result = compute(latest = null)
        assertEquals(GuidanceState.NoWorkout, result)
    }

    @Test
    fun `both null returns NoWorkout`() {
        val result = compute(event = null, latest = null)
        assertEquals(GuidanceState.NoWorkout, result)
    }

    // --- Time guards ---

    @Test
    fun `past workout returns NoWorkout`() {
        val pastEvent = event(startTime = now - 60_000L)
        val result = compute(event = pastEvent)
        assertEquals(GuidanceState.NoWorkout, result)
    }

    @Test
    fun `workout at exactly now returns NoWorkout`() {
        val result = compute(event = event(startTime = now))
        assertEquals(GuidanceState.NoWorkout, result)
    }

    // --- Valid state ---

    @Test
    fun `in-range BG with future workout returns WorkoutApproaching READY`() {
        val result = compute()
        assertTrue(result is GuidanceState.WorkoutApproaching)
        val state = result as GuidanceState.WorkoutApproaching
        assertEquals(ReadinessLevel.READY, state.readiness)
        assertEquals(140, state.currentBgMgdl)
        assertEquals(targetLow, state.targetLowMgdl)
        assertEquals(targetHigh, state.targetHighMgdl)
        assertEquals(0.0, state.iob, 0.01)
    }

    @Test
    fun `low BG returns CAUTION`() {
        val result = compute(latest = reading(sgv = 100))
        assertTrue(result is GuidanceState.WorkoutApproaching)
        assertEquals(ReadinessLevel.CAUTION, (result as GuidanceState.WorkoutApproaching).readiness)
    }

    @Test
    fun `very low BG returns WAIT`() {
        val result = compute(latest = reading(sgv = 70))
        assertTrue(result is GuidanceState.WorkoutApproaching)
        assertEquals(ReadinessLevel.WAIT, (result as GuidanceState.WorkoutApproaching).readiness)
    }

    @Test
    fun `event and carb recommendation propagated to state`() {
        val result = compute(latest = reading(sgv = 70))
        assertTrue(result is GuidanceState.WorkoutApproaching)
        val state = result as GuidanceState.WorkoutApproaching
        assertEquals("Easy Run", state.event.title)
        assertNotNull(state.carbRecommendation)
    }

    @Test
    fun `IOB propagated to state`() {
        val result = compute(iob = 2.5)
        assertTrue(result is GuidanceState.WorkoutApproaching)
        assertEquals(2.5, (result as GuidanceState.WorkoutApproaching).iob, 0.01)
    }

    @Test
    fun `direction arrow propagated from reading`() {
        val result = compute(latest = reading(direction = "SingleUp"))
        assertTrue(result is GuidanceState.WorkoutApproaching)
        assertEquals("↑", (result as GuidanceState.WorkoutApproaching).trendArrow)
    }

    @Test
    fun `invalid direction falls back to NONE`() {
        val result = compute(latest = reading(direction = "BOGUS"))
        assertTrue(result is GuidanceState.WorkoutApproaching)
        assertEquals("?", (result as GuidanceState.WorkoutApproaching).trendArrow)
    }

    @Test
    fun `workout category affects readiness thresholds`() {
        val intervalEvent = event(title = "Tempo Run", category = WorkoutCategory.INTERVAL)
        val result = MainViewModel.computeGuidance(
            intervalEvent, reading(sgv = 140), listOf(reading(sgv = 140)), 0.0,
            162f, 198f, bgLow, bgHigh, nowMs = now
        )
        assertTrue(result is GuidanceState.WorkoutApproaching)
        val state = result as GuidanceState.WorkoutApproaching
        assertEquals(ReadinessLevel.CAUTION, state.readiness)
    }
}
