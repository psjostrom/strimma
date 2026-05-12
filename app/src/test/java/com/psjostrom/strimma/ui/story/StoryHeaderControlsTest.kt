package com.psjostrom.strimma.ui.story

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose-level tests for the Story screen's header controls. The composable
 * is hoisted out of `StoryScreen` so it can be tested without spinning up a
 * StoryViewModel + DataStore + Room — a previous round dropped a VM-driven
 * test because of init-time coroutine timeouts; this stateless surface is
 * deterministic and fast.
 *
 * Asserts the user-visible click contract: disabled buttons must NOT fire
 * their callbacks. The Material 3 disabled alpha is the visual cue, but
 * what actually matters medically/UX-wise is that a tap at a boundary is
 * a no-op end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class StoryHeaderControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        canGoBack: Boolean,
        canGoForward: Boolean,
        onBack: () -> Unit = {},
        onPreviousMonth: () -> Unit = {},
        onNextMonth: () -> Unit = {},
    ) {
        composeRule.setContent {
            StrimmaTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    StoryHeaderControls(
                        canGoBack = canGoBack,
                        canGoForward = canGoForward,
                        onBack = onBack,
                        onPreviousMonth = onPreviousMonth,
                        onNextMonth = onNextMonth,
                        boxScope = this
                    )
                }
            }
        }
    }

    @Test
    fun `previous button is disabled when canGoBack is false`() {
        render(canGoBack = false, canGoForward = true)
        composeRule.onNodeWithContentDescription("Previous month").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Next month").assertIsEnabled()
    }

    @Test
    fun `next button is disabled when canGoForward is false`() {
        render(canGoBack = true, canGoForward = false)
        composeRule.onNodeWithContentDescription("Previous month").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Next month").assertIsNotEnabled()
    }

    @Test
    fun `tapping disabled previous button does not fire callback`() {
        var clicks = 0
        render(canGoBack = false, canGoForward = true, onPreviousMonth = { clicks++ })

        composeRule.onNodeWithContentDescription("Previous month").performClick()

        assertEquals("Disabled tap must be a no-op end-to-end", 0, clicks)
    }

    @Test
    fun `tapping disabled next button does not fire callback`() {
        var clicks = 0
        render(canGoBack = true, canGoForward = false, onNextMonth = { clicks++ })

        composeRule.onNodeWithContentDescription("Next month").performClick()

        assertEquals("Disabled tap must be a no-op end-to-end", 0, clicks)
    }

    @Test
    fun `tapping enabled previous button fires callback`() {
        var clicks = 0
        render(canGoBack = true, canGoForward = true, onPreviousMonth = { clicks++ })

        composeRule.onNodeWithContentDescription("Previous month").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `tapping enabled next button fires callback`() {
        var clicks = 0
        render(canGoBack = true, canGoForward = true, onNextMonth = { clicks++ })

        composeRule.onNodeWithContentDescription("Next month").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `back button is always enabled regardless of nav state`() {
        var clicks = 0
        render(canGoBack = false, canGoForward = false, onBack = { clicks++ })

        composeRule.onNodeWithContentDescription("Go back").assertIsEnabled().performClick()

        assertEquals(1, clicks)
    }
}
