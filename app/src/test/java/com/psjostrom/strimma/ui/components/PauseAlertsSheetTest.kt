package com.psjostrom.strimma.ui.components

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
        // There are two "1h" chips — the first is for Low
        composeRule.onAllNodes(hasText("1h"))[0].performClick()

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
}
