package com.psjostrom.strimma.ui

import androidx.compose.ui.geometry.Offset
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.graph.computeYRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `double tap detector detects valid double tap`() {
        val detector = DoubleTapDetector(
            doubleTapTimeoutMillis = 300L,
            doubleTapMinTimeMillis = 40L,
            doubleTapSlop = 50f
        )
        assertFalse("First tap should not trigger double-tap", detector.onSingleTap(1000L, Offset(100f, 100f)))
        assertTrue("Second tap within 150ms and 5px should trigger double-tap", detector.onSingleTap(1150L, Offset(104f, 103f)))
        assertFalse("Third tap should start new sequence", detector.onSingleTap(1300L, Offset(104f, 103f)))
    }

    @Test
    fun `double tap detector rejects taps that exceed timeout`() {
        val detector = DoubleTapDetector(
            doubleTapTimeoutMillis = 300L,
            doubleTapMinTimeMillis = 40L,
            doubleTapSlop = 50f
        )
        assertFalse(detector.onSingleTap(1000L, Offset(100f, 100f)))
        assertFalse("Tap after 400ms is not double tap", detector.onSingleTap(1400L, Offset(100f, 100f)))
    }

    @Test
    fun `double tap detector rejects taps that are too fast`() {
        val detector = DoubleTapDetector(
            doubleTapTimeoutMillis = 300L,
            doubleTapMinTimeMillis = 40L,
            doubleTapSlop = 50f
        )
        assertFalse(detector.onSingleTap(1000L, Offset(100f, 100f)))
        assertFalse("Tap within 20ms is rejected as bounce", detector.onSingleTap(1020L, Offset(100f, 100f)))
    }

    @Test
    fun `double tap detector rejects taps outside slop distance`() {
        val detector = DoubleTapDetector(
            doubleTapTimeoutMillis = 300L,
            doubleTapMinTimeMillis = 40L,
            doubleTapSlop = 50f
        )
        assertFalse(detector.onSingleTap(1000L, Offset(100f, 100f)))
        assertFalse("Tap 100px away exceeds slop", detector.onSingleTap(1150L, Offset(200f, 100f)))
    }

    @Test
    fun `double tap detector reset clears state`() {
        val detector = DoubleTapDetector(
            doubleTapTimeoutMillis = 300L,
            doubleTapMinTimeMillis = 40L,
            doubleTapSlop = 50f
        )
        assertFalse(detector.onSingleTap(1000L, Offset(100f, 100f)))
        detector.reset()
        assertFalse("After reset, next tap within 100ms should be treated as first tap", detector.onSingleTap(1100L, Offset(100f, 100f)))
    }

    @Test
    fun `double tap detector does not trigger on first tap with low timestamp`() {
        val detector = DoubleTapDetector(
            doubleTapTimeoutMillis = 300L,
            doubleTapMinTimeMillis = 40L,
            doubleTapSlop = 50f
        )
        assertFalse(
            "Initial tap with small timestamp near origin must not register as double-tap",
            detector.onSingleTap(150L, Offset(10f, 10f))
        )
    }

    @Test
    fun `findNearestByX returns null for empty readings`() {
        val result = findNearestByX(
            fingerX = 100f,
            sorted = emptyList(),
            viewport = viewport
        )
        assertNull(result)
    }

    @Test
    fun `findNearestByX finds reading closest to finger X`() {
        val target = readings[2]
        val targetX = xFor(target.ts)
        val result = findNearestByX(
            fingerX = targetX + 2f,
            sorted = readings,
            viewport = viewport
        )
        assertNotNull(result)
        assertEquals(target.ts, result!!.ts)
    }

    @Test
    fun `findNearestByX snaps to different reading as finger moves across midpoint`() {
        val r1 = readings[3] // 20 min ago
        val r2 = readings[4] // 10 min ago
        val x1 = xFor(r1.ts)
        val x2 = xFor(r2.ts)
        val midX = (x1 + x2) / 2f

        val beforeMid = findNearestByX(
            fingerX = midX - 1f,
            sorted = readings,
            viewport = viewport
        )
        val afterMid = findNearestByX(
            fingerX = midX + 1f,
            sorted = readings,
            viewport = viewport
        )

        assertEquals(r1.ts, beforeMid!!.ts)
        assertEquals(r2.ts, afterMid!!.ts)
    }
}
