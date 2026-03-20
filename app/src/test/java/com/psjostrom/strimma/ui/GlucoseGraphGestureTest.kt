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
        bgLow = 72.0,
        bgHigh = 180.0,
        canvasWidth = 400f,
        canvasHeight = 300f,
        marginLeft = 50f
    )

    private fun reading(minutesAgo: Int, sgv: Int) = GlucoseReading(
        ts = now - minutesAgo * 60_000L,
        sgv = sgv,
        direction = "Flat",
        delta = 0.0
    )

    private val readings = listOf(
        reading(50, 108),
        reading(40, 126),
        reading(30, 144),
        reading(20, 99),
        reading(10, 117),
        reading(0, 135)
    )

    private fun xFor(ts: Long): Float =
        viewport.marginLeft + ((ts - viewport.visibleStart).toFloat() / visibleMs) * viewport.plotWidth

    private fun yFor(mgdl: Double): Float {
        val yr = computeYRange(readings.map { it.sgv.toDouble() }, viewport.bgLow, viewport.bgHigh)
        return GRAPH_MARGIN_TOP + ((yr.yMax - mgdl) / yr.range).toFloat() * viewport.plotHeight
    }

    @Test
    fun `finds dot when finger is exactly on it`() {
        val target = readings[2]
        val finger = Offset(xFor(target.ts), yFor(target.sgv.toDouble()))
        val result = findNearestDot(finger, readings, viewport)
        assertNotNull(result)
        assertEquals(target.ts, result!!.ts)
    }

    @Test
    fun `finds nearest dot within hit radius`() {
        val target = readings[3]
        val finger = Offset(xFor(target.ts) + 15f, yFor(target.sgv.toDouble()) - 10f)
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
        val r1 = reading(30, 90)
        val r2 = reading(29, 162)
        val testReadings = listOf(r1, r2)

        val finger = Offset(
            (xFor(r1.ts) + xFor(r2.ts)) / 2f,
            yFor(r1.sgv.toDouble()) + 5f
        )

        val result = findNearestDot(finger, testReadings, viewport)
        assertNotNull(result)
        assertEquals("Should pick dot closer in 2D, not just X", r1.ts, result!!.ts)
    }

    @Test
    fun `hit radius is exactly DOT_HIT_RADIUS`() {
        val target = readings[0]
        val dotX = xFor(target.ts)
        val dotY = yFor(target.sgv.toDouble())

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
