package com.psjostrom.strimma.ui.settings

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.psjostrom.strimma.data.GlucoseUnit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DisplaySettingsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
        graphWindowHours: Int = 4,
        bgLow: Float = 4.0f,
        bgHigh: Float = 10.0f,
        themeMode: String = "System",
        onGlucoseUnitChange: (GlucoseUnit) -> Unit = {},
        onGraphWindowChange: (Int) -> Unit = {},
        onBgLowChange: (Float) -> Unit = {},
        onBgHighChange: (Float) -> Unit = {},
        onThemeModeChange: (String) -> Unit = {},
        onBack: () -> Unit = {}
    ) {
        composeRule.setContent {
            DisplaySettings(
                glucoseUnit = glucoseUnit,
                graphWindowHours = graphWindowHours,
                bgLow = bgLow,
                bgHigh = bgHigh,
                themeMode = themeMode,
                onGlucoseUnitChange = onGlucoseUnitChange,
                onGraphWindowChange = onGraphWindowChange,
                onBgLowChange = onBgLowChange,
                onBgHighChange = onBgHighChange,
                onThemeModeChange = onThemeModeChange,
                onBack = onBack
            )
        }
    }

    @Test
    fun `shows unit selector with both options`() {
        render()
        composeRule.onNodeWithText("mmol/L").assertExists()
        composeRule.onNodeWithText("mg/dL").assertExists()
    }

    @Test
    fun `switching unit fires callback`() {
        var selected: GlucoseUnit? = null
        render(onGlucoseUnitChange = { selected = it })
        // SegmentedButton merges semantics — find by text in unmerged tree
        composeRule.onNodeWithText("mg/dL", useUnmergedTree = true).performClick()
        assertEquals(GlucoseUnit.MGDL, selected)
    }

    @Test
    fun `shows graph window hours`() {
        render(graphWindowHours = 6)
        composeRule.onNodeWithText("Graph Window: 6 hours").assertExists()
    }

    @Test
    fun `shows threshold fields in mmol`() {
        render(glucoseUnit = GlucoseUnit.MMOL, bgLow = 4.0f, bgHigh = 10.0f)
        composeRule.onNodeWithText("Low Threshold (mmol/L)").assertExists()
        composeRule.onNodeWithText("High Threshold (mmol/L)").assertExists()
    }

    @Test
    fun `shows threshold fields in mgdl`() {
        render(glucoseUnit = GlucoseUnit.MGDL, bgLow = 4.0f, bgHigh = 10.0f)
        composeRule.onNodeWithText("Low Threshold (mg/dL)").assertExists()
        composeRule.onNodeWithText("High Threshold (mg/dL)").assertExists()
    }

    @Test
    fun `shows all theme options`() {
        render()
        composeRule.onNodeWithText("Light").assertExists()
        composeRule.onNodeWithText("Dark").assertExists()
        composeRule.onNodeWithText("System").assertExists()
    }

    @Test
    fun `switching theme fires callback`() {
        var selected: String? = null
        render(onThemeModeChange = { selected = it })
        composeRule.onNodeWithText("Dark").assertIsNotSelected()
        composeRule.onNodeWithText("Dark").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("Dark", selected)
    }
}
