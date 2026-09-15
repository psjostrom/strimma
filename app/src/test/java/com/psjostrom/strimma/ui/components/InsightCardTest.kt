package com.psjostrom.strimma.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.pattern.GlucosePattern
import com.psjostrom.strimma.data.pattern.PatternType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InsightCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val highPattern = GlucosePattern(
        startHour = 14,
        endHour = 16,
        type = PatternType.HIGH,
        daysDetected = 5,
        daysEvaluated = 7,
        avgBgMgdl = 216.0 // 12.0 mmol/L
    )

    private val lowPattern = GlucosePattern(
        startHour = 3,
        endHour = 5,
        type = PatternType.LOW,
        daysDetected = 4,
        daysEvaluated = 7,
        avgBgMgdl = 63.0 // 3.5 mmol/L
    )

    @Test
    fun `single pattern displays badge header and detail`() {
        var clicked = false
        var dismissed = false

        composeRule.setContent {
            InsightCard(
                patterns = listOf(highPattern),
                glucoseUnit = GlucoseUnit.MMOL,
                onClick = { clicked = true },
                onDismiss = { dismissed = true }
            )
        }

        composeRule.onNodeWithText("PATTERN").assertExists()
        composeRule.onNodeWithText("14:00–16:00 · 5 of 7 days").assertExists()
        composeRule.onNodeWithText("High between 14:00–16:00 on recent days", substring = true).assertExists()

        composeRule.onNodeWithText("14:00–16:00 · 5 of 7 days").performClick()
        assertTrue(clicked)

        composeRule.onNodeWithContentDescription("Dismiss").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `multiple patterns displays all patterns and details`() {
        var clicked = false
        var dismissed = false

        composeRule.setContent {
            InsightCard(
                patterns = listOf(highPattern, lowPattern),
                glucoseUnit = GlucoseUnit.MMOL,
                onClick = { clicked = true },
                onDismiss = { dismissed = true }
            )
        }

        composeRule.onNodeWithText("PATTERNS").assertExists()
        composeRule.onNodeWithText("2 recurring patterns").assertExists()

        composeRule.onNodeWithText("HIGH").assertExists()
        composeRule.onNodeWithText("14:00–16:00 · 5 of 7 days · avg 12.0 mmol/L").assertExists()

        composeRule.onNodeWithText("LOW").assertExists()
        composeRule.onNodeWithText("03:00–05:00 · 4 of 7 days · avg 3.5 mmol/L").assertExists()

        composeRule.onNodeWithText("Tap to view stats").assertExists()

        composeRule.onNodeWithText("Tap to view stats").performClick()
        assertTrue(clicked)

        composeRule.onNodeWithContentDescription("Dismiss").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `empty patterns renders nothing`() {
        composeRule.setContent {
            InsightCard(
                patterns = emptyList(),
                glucoseUnit = GlucoseUnit.MMOL,
                onClick = {},
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("PATTERN").assertDoesNotExist()
        composeRule.onNodeWithText("PATTERNS").assertDoesNotExist()
    }
}
