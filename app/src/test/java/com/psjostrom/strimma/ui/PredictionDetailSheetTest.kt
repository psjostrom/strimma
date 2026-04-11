package com.psjostrom.strimma.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.psjostrom.strimma.graph.CrossingType
import com.psjostrom.strimma.graph.Prediction
import com.psjostrom.strimma.graph.PredictionPoint
import com.psjostrom.strimma.graph.ThresholdCrossing
import com.psjostrom.strimma.data.GlucoseUnit
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
class PredictionDetailSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun predictionWith15Points(
        baseMgdl: Double = 100.0,
        slopePerMin: Double = 2.0,
        crossingType: CrossingType = CrossingType.HIGH,
        crossingMinutes: Int = 8
    ): Prediction {
        val points = (1..15).map { m -> PredictionPoint(m, baseMgdl + m * slopePerMin) }
        val crossing = ThresholdCrossing(crossingType, crossingMinutes, points[crossingMinutes - 1].mgdl)
        return Prediction(points, crossing, System.currentTimeMillis(), baseMgdl)
    }

    @Test
    fun `displays projected values at 5, 10, and 15 minutes`() {
        val prediction = predictionWith15Points(baseMgdl = 100.0, slopePerMin = 2.0)
        // index 4 (minute 5) = 110, index 9 (minute 10) = 120, index 14 (minute 15) = 130

        composeRule.setContent {
            StrimmaTheme {
                PredictionDetailSheet(
                    prediction = prediction,
                    crossing = prediction.crossing!!,
                    readings = emptyList(),
                    glucoseUnit = GlucoseUnit.MGDL,
                    bgLow = 72f,
                    bgHigh = 180f,
                    onPauseAlerts = { _, _ -> },
                    onDismiss = {}
                )
            }
        }
        composeRule.onNode(hasText("110", substring = true)).assertExists()
        composeRule.onNode(hasText("120", substring = true)).assertExists()
        composeRule.onNode(hasText("130", substring = true)).assertExists()
    }

    @Test
    fun `shows pause button when not paused`() {
        val prediction = predictionWith15Points(crossingType = CrossingType.LOW)

        composeRule.setContent {
            StrimmaTheme {
                PredictionDetailSheet(
                    prediction = prediction,
                    crossing = prediction.crossing!!,
                    readings = emptyList(),
                    glucoseUnit = GlucoseUnit.MGDL,
                    bgLow = 72f,
                    bgHigh = 180f,
                    onPauseAlerts = { _, _ -> },
                    onDismiss = {},
                    isPaused = false
                )
            }
        }
        composeRule.onNode(hasText("Pause", substring = true)).assertExists()
    }

    @Test
    fun `hides pause button when already paused`() {
        val prediction = predictionWith15Points(crossingType = CrossingType.HIGH)

        composeRule.setContent {
            StrimmaTheme {
                PredictionDetailSheet(
                    prediction = prediction,
                    crossing = prediction.crossing!!,
                    readings = emptyList(),
                    glucoseUnit = GlucoseUnit.MGDL,
                    bgLow = 72f,
                    bgHigh = 180f,
                    onPauseAlerts = { _, _ -> },
                    onDismiss = {},
                    isPaused = true
                )
            }
        }
        composeRule.onNode(hasText("Pause", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `tapping pause button calls onPauseAlerts with correct category and duration`() {
        var pausedCategory: AlertCategory? = null
        var pausedDuration: Long? = null
        val prediction = predictionWith15Points(crossingType = CrossingType.LOW)

        composeRule.setContent {
            StrimmaTheme {
                PredictionDetailSheet(
                    prediction = prediction,
                    crossing = prediction.crossing!!,
                    readings = emptyList(),
                    glucoseUnit = GlucoseUnit.MGDL,
                    bgLow = 72f,
                    bgHigh = 180f,
                    onPauseAlerts = { cat, dur ->
                        pausedCategory = cat
                        pausedDuration = dur
                    },
                    onDismiss = {}
                )
            }
        }
        composeRule.onNode(hasText("Pause", substring = true)).performClick()

        assertEquals(AlertCategory.LOW, pausedCategory)
        assertEquals(QUICK_PAUSE_MS, pausedDuration)
    }
}
