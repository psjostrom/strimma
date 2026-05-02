package com.psjostrom.strimma.ui.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class StoryEntryCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders title with month name when not viewed`() {
        composeRule.setContent {
            StrimmaTheme {
                StoryEntryCard(monthName = "April", viewed = false, onClick = {})
            }
        }
        composeRule.onNodeWithText("Your April Story").assertExists()
        composeRule.onNodeWithText("Your April Story").assertHasClickAction()
    }

    @Test
    fun `renders title with month name when viewed`() {
        composeRule.setContent {
            StrimmaTheme {
                StoryEntryCard(monthName = "April", viewed = true, onClick = {})
            }
        }
        composeRule.onNodeWithText("Your April Story").assertExists()
        composeRule.onNodeWithText("Your April Story").assertHasClickAction()
    }

    @Test
    fun `tap fires onClick`() {
        var clicks = 0
        composeRule.setContent {
            StrimmaTheme {
                StoryEntryCard(monthName = "April", viewed = true, onClick = { clicks++ })
            }
        }
        composeRule.onNodeWithText("Your April Story").performClick()
        assertEquals(1, clicks)
    }
}
