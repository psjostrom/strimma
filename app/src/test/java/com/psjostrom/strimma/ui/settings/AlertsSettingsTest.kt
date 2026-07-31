package com.psjostrom.strimma.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.notification.SnoozeDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlertsSettingsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
        alertLowEnabled: Boolean = true,
        alertHighEnabled: Boolean = true,
        alertUrgentLowEnabled: Boolean = true,
        alertUrgentHighEnabled: Boolean = true,
        alertStaleEnabled: Boolean = true,
        alertLowSoonEnabled: Boolean = true,
        alertHighSoonEnabled: Boolean = true,
        alertCooldownMinutes: Int = 0,
        alertSnoozeDuration: SnoozeDuration = SnoozeDuration.M30,
        onAlertLowEnabledChange: (Boolean) -> Unit = {},
        onAlertHighEnabledChange: (Boolean) -> Unit = {},
        onAlertUrgentLowEnabledChange: (Boolean) -> Unit = {},
        onAlertUrgentHighEnabledChange: (Boolean) -> Unit = {},
        onAlertStaleEnabledChange: (Boolean) -> Unit = {},
        onAlertLowSoonEnabledChange: (Boolean) -> Unit = {},
        onAlertHighSoonEnabledChange: (Boolean) -> Unit = {},
        onAlertCooldownChange: (Int) -> Unit = {},
        onAlertSnoozeDurationChange: (SnoozeDuration) -> Unit = {},
        onOpenAlertSound: (String) -> Unit = {},
        onBack: () -> Unit = {}
    ) {
        composeRule.setContent {
            AlertsSettings(
                glucoseUnit = glucoseUnit,
                alertLowEnabled = alertLowEnabled,
                alertHighEnabled = alertHighEnabled,
                alertUrgentLowEnabled = alertUrgentLowEnabled,
                alertUrgentHighEnabled = alertUrgentHighEnabled,
                alertLow = 4.0f,
                alertHigh = 10.0f,
                alertUrgentLow = 3.0f,
                alertUrgentHigh = 13.0f,
                alertStaleEnabled = alertStaleEnabled,
                alertLowSoonEnabled = alertLowSoonEnabled,
                alertHighSoonEnabled = alertHighSoonEnabled,
                alertCooldownMinutes = alertCooldownMinutes,
                alertSnoozeDuration = alertSnoozeDuration,
                onAlertLowEnabledChange = onAlertLowEnabledChange,
                onAlertHighEnabledChange = onAlertHighEnabledChange,
                onAlertUrgentLowEnabledChange = onAlertUrgentLowEnabledChange,
                onAlertUrgentHighEnabledChange = onAlertUrgentHighEnabledChange,
                onAlertLowChange = {},
                onAlertHighChange = {},
                onAlertUrgentLowChange = {},
                onAlertUrgentHighChange = {},
                onAlertStaleEnabledChange = onAlertStaleEnabledChange,
                onAlertLowSoonEnabledChange = onAlertLowSoonEnabledChange,
                onAlertHighSoonEnabledChange = onAlertHighSoonEnabledChange,
                onAlertCooldownChange = onAlertCooldownChange,
                onAlertSnoozeDurationChange = onAlertSnoozeDurationChange,
                onOpenAlertSound = onOpenAlertSound,
                onBack = onBack
            )
        }
    }

    @Test
    fun `displays all alert types`() {
        render()
        composeRule.onNodeWithText("Urgent Low").assertExists()
        composeRule.onNodeWithText("Low").assertExists()
        composeRule.onNodeWithText("High").assertExists()
        composeRule.onNodeWithText("Urgent High").assertExists()
        composeRule.onNodeWithText("Low Soon").assertExists()
        composeRule.onNodeWithText("High Soon").assertExists()
        composeRule.onNodeWithText("Stale Data (10+ min)").assertExists()
    }

    @Test
    fun `enabled alerts show threshold fields and sound button`() {
        render(alertLowEnabled = true)
        composeRule.onNodeWithText("Low Alert (mmol/L)").assertExists()
        // Multiple "Sound" buttons exist when all alerts enabled
        assertTrue(composeRule.onAllNodesWithText("Sound").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `disabled alert hides threshold field`() {
        render(
            alertLowEnabled = false,
            alertHighEnabled = false,
            alertUrgentLowEnabled = false,
            alertUrgentHighEnabled = false
        )
        composeRule.onNodeWithText("Low Alert (mmol/L)").assertDoesNotExist()
        composeRule.onNodeWithText("High Alert (mmol/L)").assertDoesNotExist()
        composeRule.onNodeWithText("Urgent Low (mmol/L)").assertDoesNotExist()
        composeRule.onNodeWithText("Urgent High (mmol/L)").assertDoesNotExist()
    }

    @Test
    fun `threshold labels reflect glucose unit`() {
        render(glucoseUnit = GlucoseUnit.MGDL)
        composeRule.onNodeWithText("Low Alert (mg/dL)").assertExists()
        composeRule.onNodeWithText("High Alert (mg/dL)").assertExists()
    }

    @Test
    fun `cooldown picker shows selected value and triggers callback`() {
        var receivedMinutes = -1
        render(
            alertCooldownMinutes = 10,
            onAlertCooldownChange = { receivedMinutes = it }
        )
        composeRule.onNodeWithText("10m", useUnmergedTree = true).assertExists()

        // SegmentedButton merges semantics — find by text in unmerged tree
        composeRule.onNodeWithText("Off", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(0, receivedMinutes)
    }

    @Test
    fun `cooldown picker can select non-default value`() {
        var receivedMinutes = -1
        render(
            alertCooldownMinutes = 0,
            onAlertCooldownChange = { receivedMinutes = it }
        )
        composeRule.onNodeWithText("Off", useUnmergedTree = true).assertExists()

        // "15m" also appears on Alert Snooze Duration — cooldown is the last match.
        composeRule.onAllNodesWithText("15m", useUnmergedTree = true).onLast()
            .performScrollTo().performClick()
        assertEquals(15, receivedMinutes)
    }

    @Test
    fun `alert snooze duration picker selects 1h`() {
        var selected: SnoozeDuration? = null
        render(
            alertSnoozeDuration = SnoozeDuration.M30,
            onAlertSnoozeDurationChange = { selected = it },
        )
        composeRule.onNodeWithText("1h", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(SnoozeDuration.H1, selected)
    }
}
