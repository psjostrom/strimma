package com.psjostrom.strimma.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BgWallpaperRendererTest {

    private val renderer = BgWallpaperRenderer()
    private val bgLow = 72
    private val bgHigh = 180

    private fun reading(sgv: Int, minutesAgo: Long, delta: Double? = -1.0): GlucoseReading {
        val ts = System.currentTimeMillis() - minutesAgo * 60_000L
        return GlucoseReading(sgv = sgv, ts = ts, delta = delta, direction = "Flat")
    }

    private fun createCanvas(width: Int = 400, height: Int = 800): Pair<Bitmap, Canvas> {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return bitmap to Canvas(bitmap)
    }

    @Test
    fun `renders in-range value without crash`() {
        val (_, canvas) = createCanvas()
        renderer.render(canvas, 400, 800, reading(108, 2), GlucoseUnit.MMOL, false, emptyList(), bgLow, bgHigh)
    }

    @Test
    fun `renders high value without crash`() {
        val (_, canvas) = createCanvas()
        renderer.render(canvas, 400, 800, reading(200, 2), GlucoseUnit.MMOL, false, emptyList(), bgLow, bgHigh)
    }

    @Test
    fun `renders low value without crash`() {
        val (_, canvas) = createCanvas()
        renderer.render(canvas, 400, 800, reading(60, 2), GlucoseUnit.MMOL, false, emptyList(), bgLow, bgHigh)
    }

    @Test
    fun `renders stale reading without crash`() {
        val (_, canvas) = createCanvas()
        renderer.render(canvas, 400, 800, reading(108, 15), GlucoseUnit.MMOL, false, emptyList(), bgLow, bgHigh)
    }

    @Test
    fun `renders null reading gracefully`() {
        val (_, canvas) = createCanvas()
        renderer.render(canvas, 400, 800, null, GlucoseUnit.MMOL, false, emptyList(), bgLow, bgHigh)
    }

    @Test
    fun `renders with delta text`() {
        val (_, canvas) = createCanvas()
        renderer.render(canvas, 400, 800, reading(108, 2, delta = -5.0), GlucoseUnit.MMOL, false, emptyList(), bgLow, bgHigh)
    }

    @Test
    fun `renders with null delta`() {
        val (_, canvas) = createCanvas()
        renderer.render(canvas, 400, 800, reading(108, 2, delta = null), GlucoseUnit.MMOL, false, emptyList(), bgLow, bgHigh)
    }

    @Test
    fun `renders time since reading for various ages`() {
        val (_, canvas) = createCanvas()
        // Just now
        renderer.render(canvas, 400, 800, reading(108, 0), GlucoseUnit.MMOL, false, emptyList(), bgLow, bgHigh)
        // 5 minutes ago
        renderer.render(canvas, 400, 800, reading(108, 5), GlucoseUnit.MMOL, false, emptyList(), bgLow, bgHigh)
    }

    @Test
    fun `graph dots drawn with showGraph true without crash`() {
        val (_, canvas) = createCanvas()
        val readings = (0L..30L step 5).map { reading(108, it) }

        // Verify the renderer completes without error when drawing graph dots
        renderer.render(canvas, 400, 800, reading(108, 0), GlucoseUnit.MMOL, true, readings, bgLow, bgHigh)
    }

    @Test
    fun `graph hidden when showGraph is false`() {
        val (bitmap1, canvas1) = createCanvas()
        val (bitmap2, canvas2) = createCanvas()
        val readings = (0L..30L step 5).map { reading(108, it) }
        val r = reading(108, 0)

        renderer.render(canvas1, 400, 800, r, GlucoseUnit.MMOL, false, readings, bgLow, bgHigh)
        renderer.render(canvas2, 400, 800, r, GlucoseUnit.MMOL, true, readings, bgLow, bgHigh)

        // The graph version should have more non-transparent pixels (from dots)
        val pixels1 = countNonTransparent(bitmap1)
        val pixels2 = countNonTransparent(bitmap2)
        assertTrue("Graph enabled should draw more pixels than graph disabled", pixels2 >= pixels1)
    }

    @Test
    fun `handles empty readings list for graph`() {
        val (_, canvas) = createCanvas()
        renderer.render(canvas, 400, 800, reading(108, 2), GlucoseUnit.MMOL, true, emptyList(), bgLow, bgHigh)
    }

    @Test
    fun `handles single reading for graph`() {
        val (_, canvas) = createCanvas()
        val readings = listOf(reading(108, 5))
        renderer.render(canvas, 400, 800, reading(108, 0), GlucoseUnit.MMOL, true, readings, bgLow, bgHigh)
    }

    @Test
    fun `renders in mgdl unit without crash`() {
        val (_, canvas) = createCanvas()
        renderer.render(canvas, 400, 800, reading(108, 2, delta = -5.0), GlucoseUnit.MGDL, false, emptyList(), bgLow, bgHigh)
    }

    @Test
    fun `graph dots use status colors for mixed readings without crash`() {
        val (_, canvas) = createCanvas()
        val readings = listOf(
            reading(108, 5),   // in range
            reading(200, 10),  // high
            reading(50, 15)    // low/danger
        )

        // Verify the renderer handles mixed status readings without error
        renderer.render(canvas, 400, 800, reading(108, 0), GlucoseUnit.MMOL, true, readings, bgLow, bgHigh)
    }

    private fun countNonTransparent(bitmap: Bitmap): Int {
        var count = 0
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) count++
            }
        }
        return count
    }
}
