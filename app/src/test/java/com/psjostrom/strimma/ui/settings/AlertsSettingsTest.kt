package com.psjostrom.strimma.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.notification.SnoozeDuration
import com.psjostrom.strimma.data.workout.AlertProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

    private fun regularProtocol(
        lowEnabled: Boolean = true,
        highEnabled: Boolean = true,
        urgentLowEnabled: Boolean = true,
        urgentHighEnabled: Boolean = true,
        staleEnabled: Boolean = true,
        lowSoonEnabled: Boolean = true,
        highSoonEnabled: Boolean = true,
    ) = AlertProtocol(
        urgentLowEnabled = urgentLowEnabled,
        lowEnabled = lowEnabled,
        highEnabled = highEnabled,
        urgentHighEnabled = urgentHighEnabled,
        urgentLowMgdl = 54f,
        lowMgdl = 72f,
        highMgdl = 180f,
        urgentHighMgdl = 234f,
        lowSoonEnabled = lowSoonEnabled,
        highSoonEnabled = highSoonEnabled,
        staleEnabled = staleEnabled,
    )

    private fun exerciseProtocol(
        lowEnabled: Boolean = true,
        highEnabled: Boolean = true,
        urgentLowEnabled: Boolean = true,
        urgentHighEnabled: Boolean = true,
        staleEnabled: Boolean = true,
        lowSoonEnabled: Boolean = true,
        highSoonEnabled: Boolean = true,
    ) = AlertProtocol(
        urgentLowEnabled = urgentLowEnabled,
        lowEnabled = lowEnabled,
        highEnabled = highEnabled,
        urgentHighEnabled = urgentHighEnabled,
        urgentLowMgdl = 90f,
        lowMgdl = 108f,
        highMgdl = 252f,
        urgentHighMgdl = 288f,
        lowSoonEnabled = lowSoonEnabled,
        highSoonEnabled = highSoonEnabled,
        staleEnabled = staleEnabled,
    )

    private fun render(
        glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
        regularAlertProtocol: AlertProtocol = regularProtocol(),
        exerciseAlertProtocol: AlertProtocol = exerciseProtocol(),
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
        onExerciseAlertLowEnabledChange: (Boolean) -> Unit = {},
        onExerciseAlertHighEnabledChange: (Boolean) -> Unit = {},
        onExerciseAlertUrgentLowEnabledChange: (Boolean) -> Unit = {},
        onExerciseAlertUrgentHighEnabledChange: (Boolean) -> Unit = {},
        onExerciseAlertLowChange: (Float) -> Unit = {},
        onExerciseAlertHighChange: (Float) -> Unit = {},
        onExerciseAlertUrgentLowChange: (Float) -> Unit = {},
        onExerciseAlertUrgentHighChange: (Float) -> Unit = {},
        onExerciseAlertStaleEnabledChange: (Boolean) -> Unit = {},
        onExerciseAlertLowSoonEnabledChange: (Boolean) -> Unit = {},
        onExerciseAlertHighSoonEnabledChange: (Boolean) -> Unit = {},
        validationError: Flow<AlertsViewModel.ValidationError> = emptyFlow(),
        onOpenAlertSound: (String) -> Unit = {},
        onBack: () -> Unit = {}
    ) {
        composeRule.setContent {
            AlertsSettings(
                glucoseUnit = glucoseUnit,
                regularAlertProtocol = regularAlertProtocol,
                exerciseAlertProtocol = exerciseAlertProtocol,
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
                onExerciseAlertLowEnabledChange = onExerciseAlertLowEnabledChange,
                onExerciseAlertHighEnabledChange = onExerciseAlertHighEnabledChange,
                onExerciseAlertUrgentLowEnabledChange = onExerciseAlertUrgentLowEnabledChange,
                onExerciseAlertUrgentHighEnabledChange = onExerciseAlertUrgentHighEnabledChange,
                onExerciseAlertLowChange = onExerciseAlertLowChange,
                onExerciseAlertHighChange = onExerciseAlertHighChange,
                onExerciseAlertUrgentLowChange = onExerciseAlertUrgentLowChange,
                onExerciseAlertUrgentHighChange = onExerciseAlertUrgentHighChange,
                onExerciseAlertStaleEnabledChange = onExerciseAlertStaleEnabledChange,
                onExerciseAlertLowSoonEnabledChange = onExerciseAlertLowSoonEnabledChange,
                onExerciseAlertHighSoonEnabledChange = onExerciseAlertHighSoonEnabledChange,
                validationError = validationError,
                onOpenAlertSound = onOpenAlertSound,
                onBack = onBack
            )
        }
    }

    @Test
    fun `displays all alert types`() {
        render()
        assertEquals(2, composeRule.onAllNodesWithText("Urgent Low").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithText("Low").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithText("High").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithText("Urgent High").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithText("Low Soon").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithText("High Soon").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithText("Stale Data (10+ min)").fetchSemanticsNodes().size)
    }

    @Test
    fun `enabled alerts show threshold fields and sound button`() {
        render()
        assertEquals(2, composeRule.onAllNodesWithText("Low Alert (mmol/L)").fetchSemanticsNodes().size)
        // Multiple "Sound" buttons exist when all alerts enabled
        assertTrue(composeRule.onAllNodesWithText("Sound").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `disabled alert hides threshold field`() {
        render(
            regularAlertProtocol = regularProtocol(
                lowEnabled = false,
                highEnabled = false,
                urgentLowEnabled = false,
                urgentHighEnabled = false,
            ),
            exerciseAlertProtocol = exerciseProtocol(
                lowEnabled = false,
                highEnabled = false,
                urgentLowEnabled = false,
                urgentHighEnabled = false,
            ),
        )
        assertEquals(0, composeRule.onAllNodesWithText("Low Alert (mmol/L)").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("High Alert (mmol/L)").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Urgent Low (mmol/L)").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Urgent High (mmol/L)").fetchSemanticsNodes().size)
    }

    @Test
    fun `threshold labels reflect glucose unit`() {
        render(glucoseUnit = GlucoseUnit.MGDL)
        assertEquals(2, composeRule.onAllNodesWithText("Low Alert (mg/dL)").fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodesWithText("High Alert (mg/dL)").fetchSemanticsNodes().size)
    }

    @Test
    fun `alerts screen has three sections and shared controls appear once`() {
        render()

        composeRule.onNodeWithText("ALERTS", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("EXERCISE ALERTS", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("ALERT BEHAVIOR", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Alert Snooze Duration").assertExists()
        composeRule.onNodeWithText("Cooldown").assertExists()
        assertEquals(
            1,
            composeRule.onAllNodesWithText("Cooldown", useUnmergedTree = true)
                .fetchSemanticsNodes().size
        )
    }

    @Test
    fun `exercise alert rows do not add sound buttons`() {
        render()

        // Seven enabled Regular alerts produce seven Sound buttons; Exercise adds none.
        assertEquals(
            7,
            composeRule.onAllNodesWithText("Sound", useUnmergedTree = true)
                .fetchSemanticsNodes().size
        )
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
