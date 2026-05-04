package com.psjostrom.strimma.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.psjostrom.strimma.notification.AlertCategory
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                    unifiedExpiryMs = null,
                    onPause = { _, _ -> },
                    onPauseAll = {},
                    onCancel = {},
                    onCancelAll = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("All low alerts").assertExists()
        composeRule.onNodeWithText("All high alerts").assertExists()
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
                    unifiedExpiryMs = null,
                    onPause = { cat, dur ->
                        pausedCategory = cat
                        pausedDuration = dur
                    },
                    onPauseAll = {},
                    onCancel = {},
                    onCancelAll = {}
                )
            }
        }
        // Three "1h" chips render in source order: [0] All alerts, [1] All high alerts, [2] All low alerts
        composeRule.onAllNodes(hasText("1h"))[2].performClick()

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
                    unifiedExpiryMs = null,
                    onPause = { _, _ -> },
                    onPauseAll = {},
                    onCancel = {},
                    onCancelAll = {}
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
                    unifiedExpiryMs = null,
                    onPause = { _, _ -> },
                    onPauseAll = {},
                    onCancel = { cancelledCategory = it },
                    onCancelAll = {}
                )
            }
        }
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(AlertCategory.LOW, cancelledCategory)
    }

    @Test
    fun `shows countdown text when pause is active`() {
        val futureExpiry = System.currentTimeMillis() + 5_400_000L // 1.5h from now

        composeRule.setContent {
            StrimmaTheme {
                PauseAlertsSheetContent(
                    pauseLowExpiryMs = futureExpiry,
                    pauseHighExpiryMs = null,
                    unifiedExpiryMs = null,
                    onPause = { _, _ -> },
                    onPauseAll = {},
                    onCancel = {},
                    onCancelAll = {}
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
                    unifiedExpiryMs = null,
                    onPause = { _, _ -> },
                    onPauseAll = {},
                    onCancel = {},
                    onCancelAll = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("All alerts").assertExists()
        // Sanity: per-category labels are still present below
        composeRule.onNodeWithText("All high alerts").assertExists()
        composeRule.onNodeWithText("All low alerts").assertExists()
    }

    @Test
    fun `tapping a Pause all chip invokes onPauseAll once and skips per-category onPause`() {
        var allDuration: Long? = null
        var perCategoryCalls = 0
        composeRule.setContent {
            StrimmaTheme {
                PauseAlertsSheetContent(
                    pauseLowExpiryMs = null,
                    pauseHighExpiryMs = null,
                    unifiedExpiryMs = null,
                    onPause = { _, _ -> perCategoryCalls += 1 },
                    onPauseAll = { dur -> allDuration = dur },
                    onCancel = {},
                    onCancelAll = {}
                )
            }
        }
        // The first "1h" chip on screen belongs to the "All alerts" row,
        // which renders above the per-category rows.
        composeRule.onAllNodes(hasText("1h"))[0].performClick()
        assertEquals(3_600_000L, allDuration)
        assertEquals(0, perCategoryCalls)
    }

    @Test
    fun `unified pause shows single Cancel on All row and hides per-category rows`() {
        val sharedExpiry = System.currentTimeMillis() + 1_800_000L
        composeRule.setContent {
            StrimmaTheme {
                PauseAlertsSheetContent(
                    pauseLowExpiryMs = sharedExpiry,
                    pauseHighExpiryMs = sharedExpiry,
                    unifiedExpiryMs = sharedExpiry,
                    onPause = { _, _ -> },
                    onPauseAll = {},
                    onCancel = {},
                    onCancelAll = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("All alerts").assertExists()
        composeRule.onNodeWithText("All high alerts").assertDoesNotExist()
        composeRule.onNodeWithText("All low alerts").assertDoesNotExist()
        composeRule.onAllNodes(hasText("Cancel")).assertCountEquals(1)
    }

    @Test
    fun `unified pause Cancel invokes onCancelAll, not per-category onCancel`() {
        // Cancel-all is now atomic on the AlertManager side; the sheet just fires the callback
        // and lets the VM clear both flows in one step. Per-category onCancel must NOT fire.
        val sharedExpiry = System.currentTimeMillis() + 1_800_000L
        var cancelAllCalls = 0
        val cancelled = mutableListOf<AlertCategory>()
        composeRule.setContent {
            StrimmaTheme {
                PauseAlertsSheetContent(
                    pauseLowExpiryMs = sharedExpiry,
                    pauseHighExpiryMs = sharedExpiry,
                    unifiedExpiryMs = sharedExpiry,
                    onPause = { _, _ -> },
                    onPauseAll = {},
                    onCancel = { cancelled.add(it) },
                    onCancelAll = { cancelAllCalls += 1 }
                )
            }
        }
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelAllCalls)
        assertTrue("per-category onCancel must not fire from the unified Cancel", cancelled.isEmpty())
    }

    @Test
    fun `mismatched expiries keep per-category rows visible`() {
        // Two independent pauses at different times -> still split, no unified row.
        val now = System.currentTimeMillis()
        composeRule.setContent {
            StrimmaTheme {
                PauseAlertsSheetContent(
                    pauseLowExpiryMs = now + 1_800_000L,
                    pauseHighExpiryMs = now + 3_600_000L,
                    unifiedExpiryMs = null,
                    onPause = { _, _ -> },
                    onPauseAll = {},
                    onCancel = {},
                    onCancelAll = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("All high alerts").assertExists()
        composeRule.onNodeWithText("All low alerts").assertExists()
        // Two cancel buttons (one per category), zero on the All row (which shows chips).
        composeRule.onAllNodes(hasText("Cancel")).assertCountEquals(2)
    }

    @Test
    fun `cancelling LOW under a unified pause splits the sheet back into per-category rows`() {
        // Reactive flow: unified state collapses when the VM-derived unifiedExpiryMs goes to
        // null. Per-category rows must reappear so the user can manage the remaining HIGH pause.
        val sharedExpiry = System.currentTimeMillis() + 1_800_000L
        var lowExpiry by mutableStateOf<Long?>(sharedExpiry)
        var unifiedExpiry by mutableStateOf<Long?>(sharedExpiry)
        composeRule.setContent {
            StrimmaTheme {
                PauseAlertsSheetContent(
                    pauseLowExpiryMs = lowExpiry,
                    pauseHighExpiryMs = sharedExpiry,
                    unifiedExpiryMs = unifiedExpiry,
                    onPause = { _, _ -> },
                    onPauseAll = {},
                    onCancel = {
                        if (it == AlertCategory.LOW) {
                            lowExpiry = null
                            unifiedExpiry = null
                        }
                    },
                    onCancelAll = {}
                )
            }
        }
        // Unified state hides per-category rows.
        composeRule.onNodeWithText("All low alerts").assertDoesNotExist()
        composeRule.onNodeWithText("All high alerts").assertDoesNotExist()

        // Simulate a LOW cancel coming in via some other surface (notification action, etc).
        // The sheet recomposes from the new props; the unified state collapses and the
        // per-category rows reappear so the user can still see/cancel the HIGH pause.
        composeRule.runOnIdle {
            lowExpiry = null
            unifiedExpiry = null
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("All low alerts").assertExists()
        composeRule.onNodeWithText("All high alerts").assertExists()
    }
}
