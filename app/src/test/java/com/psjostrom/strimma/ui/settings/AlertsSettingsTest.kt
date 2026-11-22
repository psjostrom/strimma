package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.notification.SnoozeDuration
import com.psjostrom.strimma.data.workout.AlertProtocol
import com.psjostrom.strimma.data.workout.AlertSet
import com.psjostrom.strimma.data.workout.NightWindow
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun callbacks(
        onHighEnabledChange: (Boolean) -> Unit = {},
    ) = AlertProtocolCallbacks(
        onUrgentLowEnabledChange = {},
        onLowEnabledChange = {},
        onHighEnabledChange = onHighEnabledChange,
        onUrgentHighEnabledChange = {},
        onUrgentLowChange = {},
        onLowChange = {},
        onHighChange = {},
        onUrgentHighChange = {},
        onLowSoonEnabledChange = {},
        onHighSoonEnabledChange = {},
        onStaleEnabledChange = {},
    )

    private fun render(
        glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
        activeSet: AlertSet = AlertSet.DAY,
        dayProtocol: AlertProtocol = regularProtocol(),
        nightProtocol: AlertProtocol = regularProtocol(),
        exerciseProtocol: AlertProtocol = exerciseProtocol(),
        nightAlertsEnabled: Boolean = true,
        dayCallbacks: AlertProtocolCallbacks = callbacks(),
        nightCallbacks: AlertProtocolCallbacks = callbacks(),
        exerciseCallbacks: AlertProtocolCallbacks = callbacks(),
        alertCooldownMinutes: Int = 0,
        alertSnoozeDuration: SnoozeDuration = SnoozeDuration.M30,
        onAlertCooldownChange: (Int) -> Unit = {},
        onAlertSnoozeDurationChange: (SnoozeDuration) -> Unit = {},
        patternAlertsEnabled: Boolean = false,
        onPatternAlertsEnabledChange: (Boolean) -> Unit = {},
        onOpenAlertSound: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            AlertsSettings(
                glucoseUnit = glucoseUnit,
                activeSet = activeSet,
                dayProtocol = dayProtocol,
                nightProtocol = nightProtocol,
                exerciseProtocol = exerciseProtocol,
                nightAlertsEnabled = nightAlertsEnabled,
                nightWindow = NightWindow.DEFAULT,
                dayCallbacks = dayCallbacks,
                nightCallbacks = nightCallbacks,
                exerciseCallbacks = exerciseCallbacks,
                onNightAlertsEnabledChange = {},
                onNightWindowStartChange = {},
                onNightWindowEndChange = {},
                alertCooldownMinutes = alertCooldownMinutes,
                alertSnoozeDuration = alertSnoozeDuration,
                onAlertCooldownChange = onAlertCooldownChange,
                onAlertSnoozeDurationChange = onAlertSnoozeDurationChange,
                patternAlertsEnabled = patternAlertsEnabled,
                onPatternAlertsEnabledChange = onPatternAlertsEnabledChange,
                onOpenAlertSound = onOpenAlertSound,
                onBack = {},
            )
        }
    }

    @Test
    fun `displays all alert types in sounds and the selected set`() {
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
    fun `enabled alerts show threshold fields`() {
        render()
        assertEquals(1, composeRule.onAllNodesWithText("Low Alert (mmol/L)").fetchSemanticsNodes().size)
    }

    @Test
    fun `disabled alert hides threshold field`() {
        render(
            dayProtocol = regularProtocol(
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
        assertEquals(1, composeRule.onAllNodesWithText("Low Alert (mg/dL)").fetchSemanticsNodes().size)
        assertEquals(1, composeRule.onAllNodesWithText("High Alert (mg/dL)").fetchSemanticsNodes().size)
    }

    @Test
    fun `alerts screen has four sections and shared controls appear once`() {
        render()

        composeRule.onNodeWithText("SOUNDS", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("ALERT BEHAVIOR", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("PATTERN INSIGHTS", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("ALERT SETS", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Alert Snooze Duration").assertExists()
        val setsTop = composeRule.onNodeWithText("ALERT SETS", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        val soundsTop = composeRule.onNodeWithText("SOUNDS", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        assertTrue("Alert sets must come before Sounds", setsTop < soundsTop)
        assertEquals(
            1,
            composeRule.onAllNodesWithText("Cooldown", useUnmergedTree = true)
                .fetchSemanticsNodes().size
        )
    }

    @Test
    fun `screen opens on the active set tab`() {
        render(activeSet = AlertSet.NIGHT)

        composeRule.onNodeWithText("Use night alerts").assertExists()
        composeRule.onNode(hasText("Night") and hasContentDescription("Active now")).assertExists()
    }

    @Test
    fun `rejected threshold edit shows the stored value again after leaving the field`() {
        render()

        composeRule.onNodeWithText("Low Alert (mmol/L)").performScrollTo().performTextReplacement("9.9")
        composeRule.onNodeWithText("Urgent Low (mmol/L)").performScrollTo().performClick()

        composeRule.onNodeWithText("Low Alert (mmol/L)").assert(hasText("4.0"))
    }

    // Hosts the field with a stored value that follows each save, as the screen does through the ViewModel.
    private fun renderLowField(saved: MutableList<Float>) {
        composeRule.setContent {
            var low by remember { mutableFloatStateOf(72f) }
            StrimmaTheme {
                Column {
                    AlertBlock(
                        label = "Low",
                        enabled = true,
                        onToggle = {},
                        threshold = low,
                        onThresholdChange = {
                            saved += it
                            low = it
                        },
                        thresholdLabel = "Low Alert (mmol/L)",
                        textColor = Color.Black,
                    )
                    AlertBlock(
                        label = "High",
                        enabled = true,
                        onToggle = {},
                        threshold = 180f,
                        onThresholdChange = {},
                        thresholdLabel = "High Alert (mmol/L)",
                        textColor = Color.Black,
                    )
                }
            }
        }
    }

    @Test
    fun `typing a threshold keeps the typed text and saves it once on Done`() {
        val saved = mutableListOf<Float>()
        renderLowField(saved)

        val field = composeRule.onNodeWithText("Low Alert (mmol/L)")
        field.performTextClearance()
        "4.5".forEach { field.performTextInput(it.toString()) }
        field.assert(hasText("4.5"))
        assertEquals(emptyList<Float>(), saved)

        field.performImeAction()
        assertEquals(listOf(GlucoseUnit.MMOL.parseThreshold("4.5")), saved)
        field.assert(hasText("4.5"))
    }

    @Test
    fun `retyping the shown threshold does not save`() {
        val saved = mutableListOf<Float>()
        renderLowField(saved)

        val field = composeRule.onNodeWithText("Low Alert (mmol/L)")
        field.performTextReplacement("4.0")
        field.performImeAction()

        assertEquals(emptyList<Float>(), saved)
    }

    @Test
    fun `leaving the field saves the typed threshold`() {
        val saved = mutableListOf<Float>()
        renderLowField(saved)

        composeRule.onNodeWithText("Low Alert (mmol/L)").performTextReplacement("3.9")
        composeRule.onNodeWithText("High Alert (mmol/L)").performClick()

        assertEquals(listOf(GlucoseUnit.MMOL.parseThreshold("3.9")), saved)
    }

    @Test
    fun `switching tab while editing saves the typed threshold`() {
        var saved: Float? = null
        render(dayCallbacks = callbacks().copy(onLowChange = { saved = it }))

        composeRule.onNodeWithText("Low Alert (mmol/L)").performScrollTo().performTextReplacement("3.9")
        composeRule.onNodeWithText("Exercise").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(GlucoseUnit.MMOL.parseThreshold("3.9"), saved)
    }

    @Test
    fun `night From field is one button with its time and opens the picker`() {
        render(activeSet = AlertSet.NIGHT, nightAlertsEnabled = true)

        composeRule.onNodeWithContentDescription("From, 23:00").performScrollTo().performClick()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun `night rows hidden while night alerts off`() {
        render(activeSet = AlertSet.NIGHT, nightAlertsEnabled = false)

        composeRule.onNodeWithText("Use night alerts").assertExists()
        assertEquals(0, composeRule.onAllNodesWithText("Urgent Low (mmol/L)").fetchSemanticsNodes().size)
    }

    @Test
    fun `sound row opens that channel`() {
        var opened: String? = null
        render(onOpenAlertSound = { opened = it })

        // The Sounds row is the only clickable "Low" node; the alert-set row has a separate switch.
        composeRule.onNode(hasText("Low") and hasClickAction()).performScrollTo().performClick()
        assertEquals(AlertManager.CHANNEL_LOW, opened)
    }

    @Test
    fun `exercise tab edits exercise set`() {
        var dayHigh: Boolean? = null
        var exerciseHigh: Boolean? = null
        render(
            dayCallbacks = callbacks(onHighEnabledChange = { dayHigh = it }),
            exerciseCallbacks = callbacks(onHighEnabledChange = { exerciseHigh = it }),
        )

        composeRule.onNodeWithText("Exercise").performScrollTo().performClick()
        composeRule.onNode(isToggleable() and hasContentDescription("High")).performScrollTo().performClick()

        assertEquals(false, exerciseHigh)
        assertNull(dayHigh)
    }

    @Test
    fun `night tab shows rows when night alerts on and edits night set`() {
        var dayHigh: Boolean? = null
        var nightHigh: Boolean? = null
        var exerciseHigh: Boolean? = null
        render(
            activeSet = AlertSet.NIGHT,
            nightAlertsEnabled = true,
            dayCallbacks = callbacks(onHighEnabledChange = { dayHigh = it }),
            nightCallbacks = callbacks(onHighEnabledChange = { nightHigh = it }),
            exerciseCallbacks = callbacks(onHighEnabledChange = { exerciseHigh = it }),
        )

        composeRule.onNodeWithText("Urgent Low (mmol/L)").performScrollTo().assertIsDisplayed()
        composeRule.onNode(isToggleable() and hasContentDescription("High")).performScrollTo().performClick()

        assertEquals(false, nightHigh)
        assertNull(dayHigh)
        assertNull(exerciseHigh)
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

    @Test
    fun `pattern insights section renders toggle and toggles state`() {
        var toggledValue: Boolean? = null
        render(
            patternAlertsEnabled = true,
            onPatternAlertsEnabledChange = { toggledValue = it },
        )

        composeRule.onNodeWithText("PATTERN INSIGHTS", useUnmergedTree = true).performScrollTo().assertExists()
        composeRule.onNodeWithText("Recurring patterns", useUnmergedTree = true).performScrollTo().assertExists()
        composeRule.onNode(isToggleable() and hasContentDescription("Recurring patterns"))
            .performScrollTo().performClick()
        assertEquals(false, toggledValue)
    }
}
