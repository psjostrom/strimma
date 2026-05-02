package com.psjostrom.strimma.ui.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.story.toMillisRange
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.YearMonth
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class MonthlyStoryEntryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val lastMonth: YearMonth = YearMonth.now().minusMonths(1)
    private val lastMonthKey: String = "%d-%02d".format(lastMonth.year, lastMonth.monthValue)
    private val monthName: String = lastMonth.month.getDisplayName(
        java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH
    )

    /** 10 readings, one per day at noon, all within the last completed month. */
    private fun fullMonthReadings(): List<GlucoseReading> {
        val zone = ZoneId.systemDefault()
        val (start, _) = lastMonth.toMillisRange(zone)
        return (0 until 10).map { dayOffset ->
            GlucoseReading(
                ts = start + dayOffset * 24L * 3_600_000L + 12L * 3_600_000L,
                sgv = 120,
                direction = "Flat",
                delta = 0.0
            )
        }
    }

    @Test
    fun `card stays visible after the user has viewed the story`() {
        composeRule.setContent {
            StrimmaTheme {
                MonthlyStoryEntry(
                    storyViewedMonth = lastMonthKey,
                    onLoadReadings = { fullMonthReadings() },
                    onNavigate = { _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Your $monthName Story").assertExists()
        composeRule.onNodeWithText("Your $monthName Story").assertHasClickAction()
    }

    @Test
    fun `card visible when user has not viewed last month`() {
        composeRule.setContent {
            StrimmaTheme {
                MonthlyStoryEntry(
                    storyViewedMonth = "",
                    onLoadReadings = { fullMonthReadings() },
                    onNavigate = { _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Your $monthName Story").assertExists()
    }

    @Test
    fun `card not rendered while storyViewedMonth is null (loading)`() {
        composeRule.setContent {
            StrimmaTheme {
                MonthlyStoryEntry(
                    storyViewedMonth = null,
                    onLoadReadings = { fullMonthReadings() },
                    onNavigate = { _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Your $monthName Story").assertDoesNotExist()
    }

    @Test
    fun `card hidden when month has fewer than 7 days of data`() {
        composeRule.setContent {
            StrimmaTheme {
                MonthlyStoryEntry(
                    storyViewedMonth = "",
                    onLoadReadings = { emptyList() },
                    onNavigate = { _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Your $monthName Story").assertDoesNotExist()
    }
}
