package com.psjostrom.strimma.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.calendar.AssessmentReason
import com.psjostrom.strimma.data.calendar.GuidanceState
import com.psjostrom.strimma.data.calendar.MetabolicProfile
import com.psjostrom.strimma.data.calendar.ReadinessLevel
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import com.psjostrom.strimma.data.health.ExerciseCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreActivityCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val twoHoursFromNow = System.currentTimeMillis() + 2 * 3600_000L

    private fun event(title: String = "Easy Run", startTime: Long = twoHoursFromNow) =
        WorkoutEvent(title, startTime, startTime + 3600_000L, ExerciseCategory.RUNNING, MetabolicProfile.AEROBIC, 1L)

    private fun state(
        readiness: ReadinessLevel = ReadinessLevel.READY,
        title: String = "Easy Run",
        suggestions: List<String> = emptyList(),
        reasons: List<AssessmentReason> = emptyList()
    ) = GuidanceState.WorkoutApproaching(
        event = event(title),
        readiness = readiness,
        reasons = reasons,
        suggestions = suggestions,
        carbRecommendation = null,
        targetLowMgdl = 126f,
        targetHighMgdl = 162f,
        currentBgMgdl = 140,
        trendArrow = "→",
        iob = 0.0
    )

    @Test
    fun `displays badge and title with time`() {
        composeRule.setContent {
            PreActivityCard(state = state(), glucoseUnit = GlucoseUnit.MMOL, onClick = {})
        }
        composeRule.onNodeWithText("READY").assertExists()
        composeRule.onNodeWithText("Easy Run in 1h 59min", substring = true).assertExists()
    }

    @Test
    fun `displays HOLD ON badge for WAIT readiness`() {
        composeRule.setContent {
            PreActivityCard(state = state(readiness = ReadinessLevel.WAIT), glucoseUnit = GlucoseUnit.MMOL, onClick = {})
        }
        composeRule.onNodeWithText("HOLD ON").assertExists()
    }

    @Test
    fun `displays HEADS UP badge for CAUTION readiness`() {
        composeRule.setContent {
            PreActivityCard(state = state(readiness = ReadinessLevel.CAUTION), glucoseUnit = GlucoseUnit.MMOL, onClick = {})
        }
        composeRule.onNodeWithText("HEADS UP").assertExists()
    }

    @Test
    fun `shows suggestion as action text`() {
        composeRule.setContent {
            PreActivityCard(
                state = state(suggestions = listOf("Eat 20g carbs")),
                glucoseUnit = GlucoseUnit.MMOL,
                onClick = {}
            )
        }
        composeRule.onNodeWithText("Eat 20g carbs").assertExists()
    }

    @Test
    fun `shows reason when no suggestion`() {
        composeRule.setContent {
            PreActivityCard(
                state = state(reasons = listOf(AssessmentReason(ReadinessLevel.CAUTION, "BG is falling"))),
                glucoseUnit = GlucoseUnit.MMOL,
                onClick = {}
            )
        }
        composeRule.onNodeWithText("BG is falling").assertExists()
    }

    @Test
    fun `prefers suggestion over reason`() {
        composeRule.setContent {
            PreActivityCard(
                state = state(
                    suggestions = listOf("Eat 20g carbs"),
                    reasons = listOf(AssessmentReason(ReadinessLevel.CAUTION, "BG is falling"))
                ),
                glucoseUnit = GlucoseUnit.MMOL,
                onClick = {}
            )
        }
        composeRule.onNodeWithText("Eat 20g carbs").assertExists()
    }

    @Test
    fun `click triggers callback`() {
        var clicked = false
        composeRule.setContent {
            PreActivityCard(state = state(), glucoseUnit = GlucoseUnit.MMOL, onClick = { clicked = true })
        }
        composeRule.onNodeWithText("READY").performClick()
        assertTrue("onClick should fire", clicked)
    }

    // --- formatTimeUntil ---

    @Test
    fun `formatTimeUntil returns now for zero`() {
        assertEquals("now", formatTimeUntil(0))
    }

    @Test
    fun `formatTimeUntil returns now for negative`() {
        assertEquals("now", formatTimeUntil(-5000))
    }

    @Test
    fun `formatTimeUntil returns minutes only`() {
        assertEquals("45min", formatTimeUntil(45 * 60_000L))
    }

    @Test
    fun `formatTimeUntil returns hours and minutes`() {
        assertEquals("1h 30min", formatTimeUntil(90 * 60_000L))
    }

    @Test
    fun `formatTimeUntil returns hours only when even`() {
        assertEquals("2h", formatTimeUntil(120 * 60_000L))
    }
}
