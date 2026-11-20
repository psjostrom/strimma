package com.psjostrom.strimma.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.meal.MealAnalyzer
import com.psjostrom.strimma.data.meal.MealTimeSlotConfig
import com.psjostrom.strimma.data.pattern.GlucosePattern
import com.psjostrom.strimma.data.pattern.PatternType
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        avgBgMgdl = 216.0,
        minBgMgdl = 198.0,
        maxBgMgdl = 234.0,
        flaggedDates = listOf(
            LocalDate.of(2026, 3, 10),
            LocalDate.of(2026, 3, 11),
            LocalDate.of(2026, 3, 12),
            LocalDate.of(2026, 3, 13),
            LocalDate.of(2026, 3, 14)
        ),
        datesWithData = (9..15).map { LocalDate.of(2026, 3, it) }
    )

    private val lowPattern = GlucosePattern(
        startHour = 3,
        endHour = 5,
        type = PatternType.LOW,
        daysDetected = 4,
        avgBgMgdl = 63.0,
        minBgMgdl = 54.0,
        maxBgMgdl = 68.0,
        flaggedDates = listOf(
            LocalDate.of(2026, 3, 10),
            LocalDate.of(2026, 3, 11),
            LocalDate.of(2026, 3, 13),
            LocalDate.of(2026, 3, 14)
        ),
        datesWithData = (9..15).map { LocalDate.of(2026, 3, it) }
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
        composeRule.onNodeWithText("Avg 12.0 mmol/L · Range 11.0 – 13.0 mmol/L").assertExists()
        composeRule.onNodeWithText("Afternoon highs — review lunch carb counting or meal bolus timing").assertExists()
        composeRule.onNodeWithText("03:00–05:00").assertExists()
        composeRule.onNodeWithText("4 of 7 days").assertExists()
        composeRule.onNodeWithText("Nighttime lows — consider reviewing evening basal or dinner bolus").assertExists()
    }

    @Test
    fun `marks a day without data in the pattern hours as no data`() {
        val patternWithGap = highPattern.copy(datesWithData = (9..14).map { LocalDate.of(2026, 3, it) })
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
                    patterns = listOf(patternWithGap)
                )
            }
        }

        composeRule.onNodeWithText("5 of 7 days").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Sunday, No data").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Tuesday, High").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Monday").assertIsDisplayed()
        composeRule.onNodeWithText("No data").assertIsDisplayed()
    }

    @Test
    fun `hides no data caption when every day has data`() {
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

        composeRule.onNodeWithText("5 of 7 days").assertIsDisplayed()
        composeRule.onNodeWithText("No data").assertDoesNotExist()
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

    @Test
    @Config(qualifiers = "sv")
    fun `shows recurring patterns with Swedish locale starting on Monday`() {
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

        composeRule.onNodeWithText("Återkommande mönster").assertExists()
        composeRule.onNodeWithText("Upptäckta under de senaste 7 dagarna").assertExists()
        composeRule.onNodeWithText("O").assertExists()
        composeRule.onNodeWithText("F").assertExists()
        composeRule.onNodeWithText("L").assertExists()
    }

    @Test
    @Config(qualifiers = "en-rSE")
    fun `shows recurring patterns with English language in Sweden region starting on Monday`() {
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
        composeRule.onNodeWithText("Detected across the last 7 days").assertExists()
        composeRule.onNodeWithText("W").assertExists()

        val mLeft = composeRule.onAllNodesWithText("M").onFirst().getBoundsInRoot().left
        val sLeft = composeRule.onAllNodesWithText("S").onFirst().getBoundsInRoot().left
        assertTrue("Monday must appear before Saturday/Sunday", mLeft < sLeft)
    }

    @Test
    fun `resolveFirstDayOfWeek resolves Monday for Sweden region regardless of language`() {
        assertEquals(DayOfWeek.MONDAY, resolveFirstDayOfWeek(Locale("en", "SE")))
        assertEquals(DayOfWeek.MONDAY, resolveFirstDayOfWeek(Locale("sv", "SE")))
        assertEquals(DayOfWeek.SUNDAY, resolveFirstDayOfWeek(Locale("en", "US")))
    }
}
