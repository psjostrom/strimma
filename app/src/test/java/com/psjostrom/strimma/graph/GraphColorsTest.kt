package com.psjostrom.strimma.graph

import org.junit.Assert.*
import org.junit.Test

class GraphColorsTest {

    // All values in mg/dL
    private val bgLow = 72.0
    private val bgHigh = 180.0

    // --- canvasColorFor ---

    @Test
    fun `in-range returns blue`() {
        assertEquals(CANVAS_IN_RANGE, canvasColorFor(108.0, bgLow, bgHigh))
        assertEquals(CANVAS_IN_RANGE, canvasColorFor(72.0, bgLow, bgHigh))
        assertEquals(CANVAS_IN_RANGE, canvasColorFor(180.0, bgLow, bgHigh))
    }

    @Test
    fun `above high returns orange`() {
        assertEquals(CANVAS_HIGH, canvasColorFor(182.0, bgLow, bgHigh))
        assertEquals(CANVAS_HIGH, canvasColorFor(232.0, bgLow, bgHigh))
    }

    @Test
    fun `below low returns red`() {
        assertEquals(CANVAS_DANGER, canvasColorFor(70.0, bgLow, bgHigh))
        assertEquals(CANVAS_DANGER, canvasColorFor(36.0, bgLow, bgHigh))
    }

    @Test
    fun `critical high returns red not orange`() {
        assertEquals(CANVAS_DANGER, canvasColorFor(234.0, bgLow, bgHigh))
        assertEquals(CANVAS_DANGER, canvasColorFor(360.0, bgLow, bgHigh))
    }

    @Test
    fun `critical low returns red`() {
        assertEquals(CANVAS_DANGER, canvasColorFor(54.0, bgLow, bgHigh))
        assertEquals(CANVAS_DANGER, canvasColorFor(18.0, bgLow, bgHigh))
    }

    @Test
    fun `boundary at bgLow is in-range`() {
        assertEquals(CANVAS_IN_RANGE, canvasColorFor(72.0, bgLow, bgHigh))
    }

    @Test
    fun `boundary at bgHigh is in-range`() {
        assertEquals(CANVAS_IN_RANGE, canvasColorFor(180.0, bgLow, bgHigh))
    }

    // --- computeYRange ---

    @Test
    fun `y range includes thresholds with padding`() {
        val yr = computeYRange(listOf(108.0, 126.0, 144.0), bgLow, bgHigh)
        assertTrue(yr.yMin <= bgLow - 9.0)
        assertTrue(yr.yMax >= bgHigh + 9.0)
    }

    @Test
    fun `y range includes critical levels`() {
        val yr = computeYRange(listOf(108.0), bgLow, bgHigh)
        assertTrue(yr.yMin <= CRITICAL_LOW - 5.0)
        assertTrue(yr.yMax >= CRITICAL_HIGH + 5.0)
    }

    @Test
    fun `y range expands for extreme data`() {
        val yr = computeYRange(listOf(27.0, 450.0), bgLow, bgHigh)
        assertTrue(yr.yMin <= 22.0)
        assertTrue(yr.yMax >= 455.0)
    }

    @Test
    fun `y range works with empty data`() {
        val yr = computeYRange(emptyList(), bgLow, bgHigh)
        assertTrue(yr.range > 0)
        assertTrue(yr.yMin <= bgLow - 9.0)
        assertTrue(yr.yMax >= bgHigh + 9.0)
    }

    @Test
    fun `y range is always positive`() {
        val yr = computeYRange(listOf(108.0), bgLow, bgHigh)
        assertTrue(yr.range > 0)
    }

    // --- computeYAxisLabels ---

    @Test
    fun `mgdl small range uses 25 step`() {
        val yr = YRange(63.0, 189.0) // range=126, < 180
        val labels = computeYAxisLabels(yr, com.psjostrom.strimma.data.GlucoseUnit.MGDL)
        val values = labels.map { it.mgdl }
        assertTrue(values.size >= 2)
        for (i in 1 until values.size) {
            assertEquals(25.0, values[i] - values[i - 1], 0.01)
        }
    }

    @Test
    fun `mgdl large range uses 50 step`() {
        val yr = YRange(40.0, 300.0) // range=260, > 180
        val labels = computeYAxisLabels(yr, com.psjostrom.strimma.data.GlucoseUnit.MGDL)
        val values = labels.map { it.mgdl }
        assertTrue(values.size >= 2)
        for (i in 1 until values.size) {
            assertEquals(50.0, values[i] - values[i - 1], 0.01)
        }
    }

    @Test
    fun `mgdl labels show integer text`() {
        val yr = YRange(63.0, 189.0)
        val labels = computeYAxisLabels(yr, com.psjostrom.strimma.data.GlucoseUnit.MGDL)
        for (label in labels) {
            assertEquals("%.0f".format(label.mgdl), label.text)
        }
    }

    @Test
    fun `mmol small range uses 1 mmol step`() {
        val yr = YRange(63.0, 189.0) // range=126, < 180
        val labels = computeYAxisLabels(yr, com.psjostrom.strimma.data.GlucoseUnit.MMOL)
        val values = labels.map { it.mgdl }
        assertTrue(values.size >= 2)
        val expectedStep = 1.0 * com.psjostrom.strimma.data.GlucoseUnit.MGDL_FACTOR
        for (i in 1 until values.size) {
            assertEquals(expectedStep, values[i] - values[i - 1], 0.01)
        }
    }

    @Test
    fun `mmol large range uses 2 mmol step`() {
        val yr = YRange(40.0, 300.0) // range=260, > 180
        val labels = computeYAxisLabels(yr, com.psjostrom.strimma.data.GlucoseUnit.MMOL)
        val values = labels.map { it.mgdl }
        assertTrue(values.size >= 2)
        val expectedStep = 2.0 * com.psjostrom.strimma.data.GlucoseUnit.MGDL_FACTOR
        for (i in 1 until values.size) {
            assertEquals(expectedStep, values[i] - values[i - 1], 0.01)
        }
    }

    @Test
    fun `mmol labels show integer text`() {
        val yr = YRange(63.0, 189.0)
        val labels = computeYAxisLabels(yr, com.psjostrom.strimma.data.GlucoseUnit.MMOL)
        for (label in labels) {
            val mmolValue = label.mgdl / com.psjostrom.strimma.data.GlucoseUnit.MGDL_FACTOR
            assertEquals("%.0f".format(mmolValue), label.text)
        }
    }

    @Test
    fun `labels are within y range`() {
        val yr = YRange(63.0, 189.0)
        val labels = computeYAxisLabels(yr, com.psjostrom.strimma.data.GlucoseUnit.MGDL)
        for (label in labels) {
            assertTrue("${label.mgdl} >= ${yr.yMin}", label.mgdl >= yr.yMin)
            assertTrue("${label.mgdl} <= ${yr.yMax}", label.mgdl <= yr.yMax)
        }
    }
}
