package com.psjostrom.strimma.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.meal.MealAnalyzer
import com.psjostrom.strimma.data.meal.MealTimeSlotConfig
import com.psjostrom.strimma.data.pattern.GlucosePattern
import com.psjostrom.strimma.data.pattern.PatternType
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class StatsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleReadings = listOf(
        GlucoseReading(ts = 1000L, sgv = 120, direction = "Flat", delta = null),
        GlucoseReading(ts = 2000L, sgv = 130, direction = "Flat", delta = null),
        GlucoseReading(ts = 3000L, sgv = 140, direction = "Flat", delta = null)
    )

    private val highPattern = GlucosePattern(
        startHour = 14,
        endHour = 16,
        type = PatternType.HIGH,
        daysDetected = 5,
        daysEvaluated = 7,
        avgBgMgdl = 216.0
    )

    private val lowPattern = GlucosePattern(
        startHour = 3,
        endHour = 5,
        type = PatternType.LOW,
        daysDetected = 4,
        daysEvaluated = 7,
        avgBgMgdl = 63.0
    )

    @Test
    fun `shows recurring patterns card when patterns present on 7-day period`() {
        composeRule.setContent {
            StrimmaTheme {
                StatsScreen(
                    bgLow = 72f,
                    bgHigh = 180f,
                    glucoseUnit = GlucoseUnit.MMOL,
                    onLoadReadings = { sampleReadings },
                    onLoadCarbTreatments = { _, _ -> emptyList() },
                    onLoadAllTreatments = { emptyList() },
                    tauMinutes = 45.0,
                    mealAnalyzer = MealAnalyzer(),
                    mealTimeSlotConfig = MealTimeSlotConfig(),
                    onExportCsv = { "" },
                    patterns = listOf(highPattern, lowPattern)
                )
            }
        }

        composeRule.onNodeWithText("Recurring patterns").assertExists()
        composeRule.onNodeWithText("Detected across the last 7 days").assertExists()
        composeRule.onNodeWithText("14:00–16:00").assertExists()
        composeRule.onNodeWithText("5 of 7 days").assertExists()
        composeRule.onNodeWithText("03:00–05:00").assertExists()
        composeRule.onNodeWithText("4 of 7 days").assertExists()
    }

    @Test
    fun `hides recurring patterns card when patterns empty`() {
        composeRule.setContent {
            StrimmaTheme {
                StatsScreen(
                    bgLow = 72f,
                    bgHigh = 180f,
                    glucoseUnit = GlucoseUnit.MMOL,
                    onLoadReadings = { sampleReadings },
                    onLoadCarbTreatments = { _, _ -> emptyList() },
                    onLoadAllTreatments = { emptyList() },
                    tauMinutes = 45.0,
                    mealAnalyzer = MealAnalyzer(),
                    mealTimeSlotConfig = MealTimeSlotConfig(),
                    onExportCsv = { "" },
                    patterns = emptyList()
                )
            }
        }

        composeRule.onNodeWithText("Recurring patterns").assertDoesNotExist()
    }

    @Test
    fun `hides recurring patterns card when switching to 24 hours period`() {
        composeRule.setContent {
            StrimmaTheme {
                StatsScreen(
                    bgLow = 72f,
                    bgHigh = 180f,
                    glucoseUnit = GlucoseUnit.MMOL,
                    onLoadReadings = { sampleReadings },
                    onLoadCarbTreatments = { _, _ -> emptyList() },
                    onLoadAllTreatments = { emptyList() },
                    tauMinutes = 45.0,
                    mealAnalyzer = MealAnalyzer(),
                    mealTimeSlotConfig = MealTimeSlotConfig(),
                    onExportCsv = { "" },
                    patterns = listOf(highPattern)
                )
            }
        }

        composeRule.onNodeWithText("Recurring patterns").assertExists()

        composeRule.onNodeWithText("24h").performClick()

        composeRule.onNodeWithText("Recurring patterns").assertDoesNotExist()
    }
}
