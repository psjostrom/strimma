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

    private val now = 1_000_000_000L
    private val visibleMs = 3600_000L // 1 hour
    private val viewport = GraphViewport(
        visibleStart = now - visibleMs,
        visibleMs = visibleMs,
        bgLow = 4.0,
        bgHigh = 10.0,
        canvasWidth = 400f,
        canvasHeight = 300f,
        marginLeft = 50f
    )

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

    private fun xFor(ts: Long): Float =
        viewport.marginLeft + ((ts - viewport.visibleStart).toFloat() / visibleMs) * viewport.plotWidth

    private fun yFor(mmol: Double): Float {
        val yr = computeYRange(readings.map { it.mmol }, viewport.bgLow, viewport.bgHigh)
        return GRAPH_MARGIN_TOP + ((yr.yMax - mmol) / yr.range).toFloat() * viewport.plotHeight
    }

    @Test
    fun `finds dot when finger is exactly on it`() {
        val target = readings[2]
        val finger = Offset(xFor(target.ts), yFor(target.mmol))
        val result = findNearestDot(finger, readings, viewport)
        assertNotNull(result)
        assertEquals(target.ts, result!!.ts)
    }

    @Test
    fun `finds nearest dot within hit radius`() {
        val target = readings[3]
        val finger = Offset(xFor(target.ts) + 15f, yFor(target.mmol) - 10f)
        val result = findNearestDot(finger, readings, viewport)
        assertNotNull(result)
        assertEquals(target.ts, result!!.ts)
    }

    @Test
    fun `returns null when finger is far from any dot`() {
        val result = findNearestDot(Offset(5f, 5f), readings, viewport)
        assertNull(result)
    }

    @Test
    fun `returns null for empty readings`() {
        val result = findNearestDot(Offset(200f, 150f), emptyList(), viewport)
        assertNull(result)
    }

    @Test
    fun `uses 2D distance not just X`() {
        val r1 = reading(30, 5.0)
        val r2 = reading(29, 9.0)
        val testReadings = listOf(r1, r2)

        val finger = Offset(
            (xFor(r1.ts) + xFor(r2.ts)) / 2f,
            yFor(r1.mmol) + 5f
        )

        val result = findNearestDot(finger, testReadings, viewport)
        assertNotNull(result)
        assertEquals("Should pick dot closer in 2D, not just X", r1.ts, result!!.ts)
    }

    @Test
    fun `hit radius is exactly DOT_HIT_RADIUS`() {
        val target = readings[0]
        val dotX = xFor(target.ts)
        val dotY = yFor(target.mmol)

        val inside = Offset(dotX + DOT_HIT_RADIUS - 1f, dotY)
        assertNotNull("Should hit within radius", findNearestDot(inside, readings, viewport))

        val outside = Offset(dotX + DOT_HIT_RADIUS + 1f, dotY)
        assertNull("Should miss outside radius", findNearestDot(outside, listOf(target), viewport))
    }

    @Test
    fun `graph viewport computes valid plot dimensions`() {
        assertTrue("Plot width must be positive", viewport.plotWidth > 0)
        assertTrue("Plot height must be positive", viewport.plotHeight > 0)
        assertTrue("Bottom margin should accommodate axis labels", GRAPH_MARGIN_BOTTOM >= GRAPH_MARGIN_TOP)
    }
}
