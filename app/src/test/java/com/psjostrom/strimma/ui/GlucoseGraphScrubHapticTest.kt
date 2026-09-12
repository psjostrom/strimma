package com.psjostrom.strimma.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.graph.computeYRange
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
class GlucoseGraphScrubHapticTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeHapticFeedback : HapticFeedback {
        val calls = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            calls.add(hapticFeedbackType)
        }
    }

    private fun reading(ts: Long, sgv: Int = 120) = GlucoseReading(
        ts = ts,
        sgv = sgv,
        direction = "Flat",
        delta = 0.0
    )

    private fun setGraph(
        readings: List<GlucoseReading>,
        haptic: FakeHapticFeedback,
        windowMs: Long,
        viewportEnd: Long,
        unit: GlucoseUnit = GlucoseUnit.MMOL
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptic) {
                StrimmaTheme {
                    GlucoseGraph(
                        readings = readings,
                        bgLow = 72.0,
                        bgHigh = 180.0,
                        windowMs = windowMs,
                        viewportEnd = viewportEnd,
                        zoomScale = 1.0f,
                        glucoseUnit = unit,
                        onViewportChange = {},
                        onZoomChange = {},
                        modifier = Modifier.size(400.dp, 300.dp).testTag("glucose_graph")
                    )
                }
            }
        }
    }

    @Test
    fun `triggers haptic feedback on scrub down and when reading changes`() {
        val fakeHaptic = FakeHapticFeedback()
        val windowMs = 4 * 3600_000L
        val viewportEnd = 1_000_000_000L
        val visibleStart = viewportEnd - windowMs

        val r1 = reading(visibleStart + windowMs / 4)
        val r2 = reading(visibleStart + 3 * windowMs / 4)
        val readings = listOf(r1, r2)
        setGraph(readings, fakeHaptic, windowMs, viewportEnd)

        composeRule.onNodeWithTag("glucose_graph").performTouchInput {
            val plotW = width.toFloat() - 50f - GRAPH_MARGIN_RIGHT
            val plotH = height.toFloat() - GRAPH_MARGIN_TOP - GRAPH_MARGIN_BOTTOM
            val yr = computeYRange(readings.map { it.sgv.toDouble() }, 72.0, 180.0)

            val x1 = 50f + ((r1.ts - visibleStart).toFloat() / windowMs) * plotW
            val y1 = GRAPH_MARGIN_TOP + ((yr.yMax - r1.sgv.toDouble()) / yr.range).toFloat() * plotH
            val x2 = 50f + ((r2.ts - visibleStart).toFloat() / windowMs) * plotW

            down(Offset(x1, y1))
            moveTo(Offset(x1 + 5f, y1))
            moveTo(Offset((x1 + x2) / 2f + 10f, y1))
            up()
        }

        assertEquals(
            "Must trigger haptic feedback on initial dot hit and on snap to r2",
            listOf(HapticFeedbackType.TextHandleMove, HapticFeedbackType.TextHandleMove),
            fakeHaptic.calls
        )
    }

    @Test
    fun `multi-touch cancels scrub and stops further haptics`() {
        val fakeHaptic = FakeHapticFeedback()
        val windowMs = 4 * 3600_000L
        val viewportEnd = 1_000_000_000L
        val visibleStart = viewportEnd - windowMs

        val r1 = reading(visibleStart + windowMs / 4)
        val r2 = reading(visibleStart + 3 * windowMs / 4)
        setGraph(listOf(r1, r2), fakeHaptic, windowMs, viewportEnd)

        composeRule.onNodeWithTag("glucose_graph").performTouchInput {
            val plotW = width.toFloat() - 50f - GRAPH_MARGIN_RIGHT
            val plotH = height.toFloat() - GRAPH_MARGIN_TOP - GRAPH_MARGIN_BOTTOM
            val yr = computeYRange(listOf(120.0), 72.0, 180.0)
            val x1 = 50f + ((r1.ts - visibleStart).toFloat() / windowMs) * plotW
            val y1 = GRAPH_MARGIN_TOP + ((yr.yMax - 120.0) / yr.range).toFloat() * plotH

            val p1 = 0
            val p2 = 1
            down(p1, Offset(x1, y1))
            down(p2, Offset(x1 + 40f, y1))
            moveTo(p1, Offset(x1 + 100f, y1))
            up(p1)
            up(p2)
        }

        assertEquals(
            "Only initial down on dot triggers haptic; second finger cancels scrub",
            listOf(HapticFeedbackType.TextHandleMove),
            fakeHaptic.calls
        )
    }

    @Test
    fun `does not trigger haptic feedback when touching empty space`() {
        val fakeHaptic = FakeHapticFeedback()
        val windowMs = 4 * 3600_000L
        val viewportEnd = 1_000_000_000L
        val visibleStart = viewportEnd - windowMs

        setGraph(listOf(reading(visibleStart + windowMs / 2)), fakeHaptic, windowMs, viewportEnd)

        composeRule.onNodeWithTag("glucose_graph").performTouchInput {
            down(Offset(10f, 10f))
            moveTo(Offset(20f, 20f))
            up()
        }

        assertTrue("No haptics should fire when gesture does not hit a dot", fakeHaptic.calls.isEmpty())
    }
}
