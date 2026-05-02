package com.psjostrom.strimma.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.psjostrom.strimma.notification.AlertCategory
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class PauseAlertsSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `shows both category rows when no pause active`() {
        composeRule.setContent {
            StrimmaTheme {
                PauseAlertsSheetContent(
                    pauseLowExpiryMs = null,
                    pauseHighExpiryMs = null,
                    onPause = { _, _ -> },
                    onCancel = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Low alerts").assertExists()
        composeRule.onNodeWithText("High alerts").assertExists()
    }

    @Test
    fun `tapping duration chip calls onPause with correct category and duration`() {
        var pausedCategory: AlertCategory? = null
        var pausedDuration: Long? = null

        composeRule.setContent {
            StrimmaTheme {
                PauseAlertsSheetContent(
                    pauseLowExpiryMs = null,
                    pauseHighExpiryMs = null,
                    onPause = { cat, dur ->
                        pausedCategory = cat
                        pausedDuration = dur
                    },
                    onCancel = {}
                )
            }
        }
        // Three "1h" chips render: [0] All alerts, [1] Low, [2] High
        composeRule.onAllNodes(hasText("1h"))[1].performClick()

        assertEquals(AlertCategory.LOW, pausedCategory)
        assertEquals(3600_000L, pausedDuration)
    }

    @Test
    fun `shows cancel button when pause is active`() {
        val futureExpiry = System.currentTimeMillis() + 1800_000L

        composeRule.setContent {
            StrimmaTheme {
                PauseAlertsSheetContent(
                    pauseLowExpiryMs = null,
                    pauseHighExpiryMs = futureExpiry,
                    onPause = { _, _ -> },
                    onCancel = {}
                )
            }
        }
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `cancel button calls onCancel with correct category`() {
        var cancelledCategory: AlertCategory? = null
        val futureExpiry = System.currentTimeMillis() + 1800_000L

        composeRule.setContent {
            StrimmaTheme {
                PauseAlertsSheetContent(
                    pauseLowExpiryMs = futureExpiry,
                    pauseHighExpiryMs = null,
                    onPause = { _, _ -> },
                    onCancel = { cancelledCategory = it }
                )
            }
        }
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(AlertCategory.LOW, cancelledCategory)
    }

    @Test
    fun `urgent low warning is shown on both All and Low rows when LOW is not paused`() {
        composeRule.setContent {
            StrimmaTheme {
                PauseAlertsSheetContent(
                    pauseLowExpiryMs = null,
                    pauseHighExpiryMs = null,
                    onPause = { _, _ -> },
                    onCancel = {}
                )
            }
        }
        // Warning renders unconditionally on the All-alerts row and
        // conditionally on the Low row when LOW is not paused.
        composeRule.onAllNodesWithText("Includes urgent low alerts").assertCountEquals(2)
    }

    @Test
    fun `shows countdown text when pause is active`() {
        val futureExpiry = System.currentTimeMillis() + 5_400_000L // 1.5h from now

        composeRule.setContent {
            StrimmaTheme {
                PauseAlertsSheetContent(
                    pauseLowExpiryMs = futureExpiry,
                    pauseHighExpiryMs = null,
                    onPause = { _, _ -> },
                    onCancel = {}
                )
            }
        }
        composeRule.waitForIdle()
        // Should show "Paused · 1h Xm" (approximately)
        composeRule.onNode(hasText("Paused", substring = true)).assertExists()
    }

    @Test
    fun `renders Pause all alerts section above per-category rows`() {
        composeRule.setContent {
            StrimmaTheme {
                PauseAlertsSheetContent(
                    pauseLowExpiryMs = null,
                    pauseHighExpiryMs = null,
                    onPause = { _, _ -> },
                    onCancel = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("All alerts").assertExists()
        // Sanity: per-category labels are still present below
        composeRule.onNodeWithText("Low alerts").assertExists()
        composeRule.onNodeWithText("High alerts").assertExists()
    }

    @Test
    fun `tapping a Pause all chip invokes onPause for both LOW and HIGH`() {
        var pausedLow: Long? = null
        var pausedHigh: Long? = null
        composeRule.setContent {
            StrimmaTheme {
                PauseAlertsSheetContent(
                    pauseLowExpiryMs = null,
                    pauseHighExpiryMs = null,
                    onPause = { cat, dur ->
                        when (cat) {
                            AlertCategory.LOW -> pausedLow = dur
                            AlertCategory.HIGH -> pausedHigh = dur
                        }
                    },
                    onCancel = {}
                )
            }
        }
        // The first "1h" chip on screen belongs to the new "All alerts" row,
        // which renders above the per-category rows.
        composeRule.onAllNodes(hasText("1h"))[0].performClick()
        assertEquals(3_600_000L, pausedLow)
        assertEquals(3_600_000L, pausedHigh)
    }

    @Test
    fun `low alert warning is still shown when only HIGH is paused`() {
        // After "Pause all" sets both expiries, each row renders independently.
        // This verifies the inverse: when only HIGH is paused (LOW is not),
        // the LOW warning must still be visible (alongside the All-row warning).
        composeRule.setContent {
            StrimmaTheme {
                PauseAlertsSheetContent(
                    pauseLowExpiryMs = null,
                    pauseHighExpiryMs = System.currentTimeMillis() + 1_800_000L,
                    onPause = { _, _ -> },
                    onCancel = {}
                )
            }
        }
        composeRule.onAllNodesWithText("Includes urgent low alerts").assertCountEquals(2)
    }
}
