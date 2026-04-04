package com.psjostrom.strimma.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.psjostrom.strimma.data.GlucoseSource
import com.psjostrom.strimma.network.IntegrationStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataSourceSettingsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        glucoseSource: GlucoseSource = GlucoseSource.COMPANION,
        onGlucoseSourceChange: (GlucoseSource) -> Unit = {},
        onBack: () -> Unit = {}
    ) {
        composeRule.setContent {
            DataSourceSettings(
                glucoseSource = glucoseSource,
                nightscoutUrl = "https://ns.example.com",
                nightscoutSecret = "secret123",
                followerPollSeconds = 60,
                lluEmail = "",
                lluPassword = "",
                pushStatus = IntegrationStatus.Idle,
                nsFollowerStatus = IntegrationStatus.Idle,
                lluFollowerStatus = IntegrationStatus.Idle,
                onGlucoseSourceChange = onGlucoseSourceChange,
                onNightscoutUrlChange = {},
                onNightscoutSecretChange = {},
                onFollowerPollSecondsChange = {},
                onLluEmailChange = {},
                onLluPasswordChange = {},
                isNotificationAccessGranted = true,
                onOpenNotificationAccess = {},
                onPullFromNightscout = {},
                onBack = onBack
            )
        }
    }

    private fun radioButtons() = composeRule.onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    )

    @Test
    fun `displays all data source options`() {
        render()
        composeRule.onNodeWithText("Companion Mode").assertExists()
        composeRule.onNodeWithText("xDrip Broadcast").assertExists()
        composeRule.onNodeWithText("Nightscout Follower").assertExists()
        composeRule.onNodeWithText("LibreLinkUp").assertExists()
    }

    @Test
    fun `companion mode is selected by default`() {
        render(glucoseSource = GlucoseSource.COMPANION)
        composeRule.onNodeWithText("Companion Mode").assertExists()
    }

    @Test
    fun `switching to follower mode fires callback`() {
        var selected: GlucoseSource? = null
        render(onGlucoseSourceChange = { selected = it })
        // NIGHTSCOUT_FOLLOWER is the 3rd radio button (index 2)
        radioButtons()[2].performClick()
        assertEquals(GlucoseSource.NIGHTSCOUT_FOLLOWER, selected)
    }

    @Test
    fun `shows nightscout section for all modes`() {
        render(glucoseSource = GlucoseSource.COMPANION)
        composeRule.onNodeWithText("Nightscout URL").assertExists()
        composeRule.onNodeWithText("API Secret").assertExists()
    }

    @Test
    fun `follower mode shows nightscout section and poll settings`() {
        render(glucoseSource = GlucoseSource.NIGHTSCOUT_FOLLOWER)
        composeRule.onNodeWithText("Nightscout URL").assertExists()
        composeRule.onNodeWithText("API Secret").assertExists()
        composeRule.onNodeWithText("Poll Interval: 60s").assertExists()
    }

    @Test
    fun `librelinkup mode shows credential fields`() {
        render(glucoseSource = GlucoseSource.LIBRELINKUP)
        composeRule.onNodeWithText("Email").assertExists()
        composeRule.onNodeWithText("Password").assertExists()
    }

    @Test
    fun `librelinkup mode shows nightscout section`() {
        render(glucoseSource = GlucoseSource.LIBRELINKUP)
        composeRule.onNodeWithText("Nightscout URL").assertExists()
        composeRule.onNodeWithText("API Secret").assertExists()
    }

    @Test
    fun `librelinkup mode does not show follower poll interval`() {
        render(glucoseSource = GlucoseSource.LIBRELINKUP)
        composeRule.onNodeWithText("Poll Interval: 60s").assertDoesNotExist()
    }

    @Test
    fun `back button fires callback`() {
        var backPressed = false
        render(onBack = { backPressed = true })
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(true, backPressed)
    }
}
