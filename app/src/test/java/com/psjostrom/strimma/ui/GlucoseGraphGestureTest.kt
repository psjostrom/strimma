package com.psjostrom.strimma.ui

import androidx.compose.ui.geometry.Offset
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.graph.computeYRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlucoseGraphGestureTest {

    private val bgLow = 4.0
    private val bgHigh = 10.0
    private val canvasWidth = 400f
    private val canvasHeight = 300f
    private val marginLeft = 50f

    private val now = 1_000_000_000L
    private val visibleMs = 3600_000L // 1 hour
    private val visibleStart = now - visibleMs

    private fun reading(minutesAgo: Int, mmol: Double) = GlucoseReading(
        ts = now - minutesAgo * 60_000L,
        sgv = (mmol * 18.0182).toInt(),
        mmol = mmol,
        direction = "Flat",
        deltaMmol = 0.0
    )

    private val readings = listOf(
        reading(50, 6.0),
        reading(40, 7.0),
        reading(30, 8.0),
        reading(20, 5.5),
        reading(10, 6.5),
        reading(0, 7.5)
    )

    // Replicate the coordinate functions used by findNearestDot
    private fun xFor(ts: Long): Float {
        val plotWidth = canvasWidth - marginLeft - GRAPH_MARGIN_RIGHT
        return marginLeft + ((ts - visibleStart).toFloat() / visibleMs) * plotWidth
    }

    private fun yFor(mmol: Double): Float {
        val plotHeight = canvasHeight - GRAPH_MARGIN_TOP - GRAPH_MARGIN_BOTTOM
        val yr = computeYRange(readings.map { it.mmol }, bgLow, bgHigh)
        return GRAPH_MARGIN_TOP + ((yr.yMax - mmol) / yr.range).toFloat() * plotHeight
    }

    // --- findNearestDot ---

    @Test
    fun `finds dot when finger is exactly on it`() {
        val target = readings[2] // 30 min ago, 8.0 mmol
        val finger = Offset(xFor(target.ts), yFor(target.mmol))

        val result = findNearestDot(
            finger, readings, visibleStart, visibleMs,
            bgLow, bgHigh, canvasWidth, canvasHeight,
            marginLeft, GRAPH_MARGIN_RIGHT, GRAPH_MARGIN_TOP, GRAPH_MARGIN_BOTTOM
        )

        assertNotNull(result)
        assertEquals(target.ts, result!!.ts)
    }

    @Test
    fun `finds nearest dot within hit radius`() {
        val target = readings[3] // 20 min ago, 5.5 mmol
        val finger = Offset(xFor(target.ts) + 15f, yFor(target.mmol) - 10f)

        val result = findNearestDot(
            finger, readings, visibleStart, visibleMs,
            bgLow, bgHigh, canvasWidth, canvasHeight,
            marginLeft, GRAPH_MARGIN_RIGHT, GRAPH_MARGIN_TOP, GRAPH_MARGIN_BOTTOM
        )

        assertNotNull(result)
        assertEquals(target.ts, result!!.ts)
    }

    @Test
    fun `returns null when finger is far from any dot`() {
        // Touch the margin area where no dots exist
        val finger = Offset(5f, 5f)

        val result = findNearestDot(
            finger, readings, visibleStart, visibleMs,
            bgLow, bgHigh, canvasWidth, canvasHeight,
            marginLeft, GRAPH_MARGIN_RIGHT, GRAPH_MARGIN_TOP, GRAPH_MARGIN_BOTTOM
        )

        assertNull(result)
    }

    @Test
    fun `returns null for empty readings`() {
        val result = findNearestDot(
            Offset(200f, 150f), emptyList(), visibleStart, visibleMs,
            bgLow, bgHigh, canvasWidth, canvasHeight,
            marginLeft, GRAPH_MARGIN_RIGHT, GRAPH_MARGIN_TOP, GRAPH_MARGIN_BOTTOM
        )

        assertNull(result)
    }

    @Test
    fun `uses 2D distance not just X`() {
        // Two dots at similar X but very different Y — finger closer in X to the far-Y dot
        // but closer in 2D to the near-Y dot
        val r1 = reading(30, 5.0)
        val r2 = reading(29, 9.0) // nearly same X as r1, very different Y
        val testReadings = listOf(r1, r2)

        // Place finger near r1's Y, slightly closer in X to r2
        val finger = Offset(
            (xFor(r1.ts) + xFor(r2.ts)) / 2f, // midpoint X
            yFor(r1.mmol) + 5f // close to r1's Y
        )

        val result = findNearestDot(
            finger, testReadings, visibleStart, visibleMs,
            bgLow, bgHigh, canvasWidth, canvasHeight,
            marginLeft, GRAPH_MARGIN_RIGHT, GRAPH_MARGIN_TOP, GRAPH_MARGIN_BOTTOM
        )

        assertNotNull(result)
        assertEquals("Should pick dot closer in 2D, not just X", r1.ts, result!!.ts)
    }

    @Test
    fun `hit radius is exactly DOT_HIT_RADIUS`() {
        val target = readings[0]
        val dotX = xFor(target.ts)
        val dotY = yFor(target.mmol)

        // Just inside the radius
        val inside = Offset(dotX + DOT_HIT_RADIUS - 1f, dotY)
        val resultInside = findNearestDot(
            inside, readings, visibleStart, visibleMs,
            bgLow, bgHigh, canvasWidth, canvasHeight,
            marginLeft, GRAPH_MARGIN_RIGHT, GRAPH_MARGIN_TOP, GRAPH_MARGIN_BOTTOM
        )
        assertNotNull("Should hit within radius", resultInside)

        // Just outside the radius (only this dot, so no other can match)
        val singleReading = listOf(target)
        val outside = Offset(dotX + DOT_HIT_RADIUS + 1f, dotY)
        val resultOutside = findNearestDot(
            outside, singleReading, visibleStart, visibleMs,
            bgLow, bgHigh, canvasWidth, canvasHeight,
            marginLeft, GRAPH_MARGIN_RIGHT, GRAPH_MARGIN_TOP, GRAPH_MARGIN_BOTTOM
        )
        assertNull("Should miss outside radius", resultOutside)
    }

    // --- Margin constants ---

    @Test
    fun `margin constants are consistent with graph layout`() {
        // These margins define the plot area — verify they leave reasonable space
        val plotWidth = canvasWidth - marginLeft - GRAPH_MARGIN_RIGHT
        val plotHeight = canvasHeight - GRAPH_MARGIN_TOP - GRAPH_MARGIN_BOTTOM

        assertTrue("Plot width must be positive", plotWidth > 0)
        assertTrue("Plot height must be positive", plotHeight > 0)
        assertTrue("Right margin should be small", GRAPH_MARGIN_RIGHT < canvasWidth / 4)
        assertTrue("Top margin should be small", GRAPH_MARGIN_TOP < canvasHeight / 4)
        assertTrue("Bottom margin should accommodate axis labels", GRAPH_MARGIN_BOTTOM >= GRAPH_MARGIN_TOP)
    }
}
