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
}
