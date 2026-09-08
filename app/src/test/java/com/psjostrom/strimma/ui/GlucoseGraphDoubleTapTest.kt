package com.psjostrom.strimma.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
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
class GlucoseGraphDoubleTapTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `double tap triggers onResetZoomAndViewport callback`() {
        var resetInvoked = false

        composeRule.setContent {
            StrimmaTheme {
                GlucoseGraph(
                    readings = emptyList(),
                    bgLow = 72.0,
                    bgHigh = 180.0,
                    windowMs = 4 * 3600_000L,
                    viewportEnd = 1_000_000_000L,
                    zoomScale = 2.5f,
                    onViewportChange = {},
                    onZoomChange = {},
                    onResetZoomAndViewport = { resetInvoked = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("glucose_graph")
                )
            }
        }

        composeRule.onNodeWithTag("glucose_graph").performTouchInput {
            doubleClick()
        }

        assertTrue("Double tap must invoke onResetZoomAndViewport", resetInvoked)
    }

    @Test
    fun `default onResetZoomAndViewport resets zoom and advances viewport`() {
        var newZoom = 2.5f
        var newViewport = 0L

        composeRule.setContent {
            StrimmaTheme {
                GlucoseGraph(
                    readings = emptyList(),
                    bgLow = 72.0,
                    bgHigh = 180.0,
                    windowMs = 4 * 3600_000L,
                    viewportEnd = 1_000_000_000L,
                    zoomScale = 2.5f,
                    predictionMinutes = 15,
                    onViewportChange = { newViewport = it },
                    onZoomChange = { newZoom = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("glucose_graph")
                )
            }
        }

        val before = System.currentTimeMillis()
        composeRule.onNodeWithTag("glucose_graph").performTouchInput {
            doubleClick()
        }
        val after = System.currentTimeMillis()

        assertEquals(1f, newZoom, 0.001f)
        assertTrue(
            "Viewport must advance near current time + 15 min prediction",
            newViewport >= before + 15 * 60_000L && newViewport <= after + 15 * 60_000L
        )
    }
}
