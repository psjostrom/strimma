package com.psjostrom.strimma.graph

import org.junit.Assert.*
import org.junit.Test

class GraphColorsTest {

    private val bgLow = 4.0
    private val bgHigh = 10.0

    // --- canvasColorFor ---

    @Test
    fun `in-range returns blue`() {
        assertEquals(CANVAS_IN_RANGE, canvasColorFor(6.0, bgLow, bgHigh))
        assertEquals(CANVAS_IN_RANGE, canvasColorFor(4.0, bgLow, bgHigh))
        assertEquals(CANVAS_IN_RANGE, canvasColorFor(10.0, bgLow, bgHigh))
    }

    @Test
    fun `above high returns orange`() {
        assertEquals(CANVAS_HIGH, canvasColorFor(10.1, bgLow, bgHigh))
        assertEquals(CANVAS_HIGH, canvasColorFor(12.9, bgLow, bgHigh))
    }

    @Test
    fun `below low returns red`() {
        assertEquals(CANVAS_LOW, canvasColorFor(3.9, bgLow, bgHigh))
        assertEquals(CANVAS_LOW, canvasColorFor(2.0, bgLow, bgHigh))
    }

    @Test
    fun `critical high returns red not orange`() {
        assertEquals(CANVAS_LOW, canvasColorFor(13.0, bgLow, bgHigh))
        assertEquals(CANVAS_LOW, canvasColorFor(20.0, bgLow, bgHigh))
    }

    @Test
    fun `critical low returns red`() {
        assertEquals(CANVAS_LOW, canvasColorFor(3.0, bgLow, bgHigh))
        assertEquals(CANVAS_LOW, canvasColorFor(1.0, bgLow, bgHigh))
    }

    @Test
    fun `boundary at bgLow is in-range`() {
        assertEquals(CANVAS_IN_RANGE, canvasColorFor(4.0, bgLow, bgHigh))
    }

    @Test
    fun `boundary at bgHigh is in-range`() {
        assertEquals(CANVAS_IN_RANGE, canvasColorFor(10.0, bgLow, bgHigh))
    }

    // --- computeYRange ---

    @Test
    fun `y range includes thresholds with padding`() {
        val yr = computeYRange(listOf(6.0, 7.0, 8.0), bgLow, bgHigh)
        assertTrue(yr.yMin <= bgLow - 0.5)
        assertTrue(yr.yMax >= bgHigh + 0.5)
    }

    @Test
    fun `y range includes critical levels`() {
        val yr = computeYRange(listOf(6.0), bgLow, bgHigh)
        assertTrue(yr.yMin <= CRITICAL_LOW - 0.3)
        assertTrue(yr.yMax >= CRITICAL_HIGH + 0.3)
    }

    @Test
    fun `y range expands for extreme data`() {
        val yr = computeYRange(listOf(1.5, 25.0), bgLow, bgHigh)
        assertTrue(yr.yMin <= 1.2)
        assertTrue(yr.yMax >= 25.3)
    }

    @Test
    fun `y range works with empty data`() {
        val yr = computeYRange(emptyList(), bgLow, bgHigh)
        assertTrue(yr.range > 0)
        assertTrue(yr.yMin <= bgLow - 0.5)
        assertTrue(yr.yMax >= bgHigh + 0.5)
    }

    @Test
    fun `y range is always positive`() {
        val yr = computeYRange(listOf(6.0), bgLow, bgHigh)
        assertTrue(yr.range > 0)
    }
}
