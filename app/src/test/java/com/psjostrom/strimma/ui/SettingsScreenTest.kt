package com.psjostrom.strimma.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `does not show Statistics entry`() {
        composeRule.setContent {
            StrimmaTheme {
                SettingsScreen(
                    onNavigate = {},
                    nightscoutConfigured = true
                )
            }
        }
        composeRule.onNodeWithText("Statistics").assertDoesNotExist()
    }

    @Test
    fun `shows all settings groups`() {
        composeRule.setContent {
            StrimmaTheme {
                SettingsScreen(
                    onNavigate = {},
                    nightscoutConfigured = true
                )
            }
        }
        composeRule.onNodeWithText("Data Source").assertExists()
        composeRule.onNodeWithText("Treatments").assertExists()
        composeRule.onNodeWithText("Exercise").assertExists()
        composeRule.onNodeWithText("Display").assertExists()
        composeRule.onNodeWithText("Notifications").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Alerts").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("General").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `hides back button when used as tab`() {
        composeRule.setContent {
            StrimmaTheme {
                SettingsScreen(
                    onNavigate = {},
                    nightscoutConfigured = false
                )
            }
        }
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `shows back button when onBack is provided`() {
        composeRule.setContent {
            StrimmaTheme {
                SettingsScreen(
                    onNavigate = {},
                    onBack = {},
                    nightscoutConfigured = false
                )
            }
        }
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }
}
