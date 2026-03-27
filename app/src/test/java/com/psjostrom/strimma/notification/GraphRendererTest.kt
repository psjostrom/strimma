package com.psjostrom.strimma.notification

import android.graphics.Color
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.graph.CANVAS_HIGH
import com.psjostrom.strimma.graph.CANVAS_IN_RANGE
import com.psjostrom.strimma.graph.CANVAS_LOW
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GraphRendererTest {

    private val bgLow = 72.0
    private val bgHigh = 180.0
    private val windowMs = 3600_000L

    private fun reading(sgv: Int, minutesAgo: Long): GlucoseReading {
        val ts = System.currentTimeMillis() - minutesAgo * 60_000L
        return GlucoseReading(sgv = sgv, ts = ts, delta = null, direction = "Flat")
    }

    @Test
    fun `renders bitmap with correct dimensions`() {
        val readings = (0L..30L step 5).map { reading(108, it) }
        val bitmap = GraphRenderer.render(readings, 400, 200, bgLow, bgHigh, windowMs)
        assertEquals(400, bitmap.width)
        assertEquals(200, bitmap.height)
    }

    @Test
    fun `renders without crash on empty readings`() {
        val bitmap = GraphRenderer.render(emptyList(), 400, 200, bgLow, bgHigh, windowMs)
        assertEquals(400, bitmap.width)
    }

    @Test
    fun `prediction dots use zone colors not white`() {
        // Steady in-range readings to produce a stable in-range prediction
        val readings = (0L..12L).map { reading(108, it) }
        val bitmap = GraphRenderer.render(readings, 800, 400, bgLow, bgHigh, windowMs)

        // The prediction area is to the right of "now". Sample pixels in that region.
        // With a stable 108 mg/dL, prediction dots should be cyan (CANVAS_IN_RANGE),
        // NOT white. We verify by checking that no pure white dots exist in the
        // prediction zone (right quarter of the bitmap).
        val predictionStartX = bitmap.width * 3 / 4
        var foundWhiteDot = false
        var foundColoredDot = false
        for (x in predictionStartX until bitmap.width) {
            for (y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel == Color.TRANSPARENT) continue
                val alpha = Color.alpha(pixel)
                if (alpha < 20) continue
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (r > 240 && g > 240 && b > 240) foundWhiteDot = true
                if (r != g || g != b) foundColoredDot = true // non-gray = colored
            }
        }
        assertFalse("Prediction dots should not be white", foundWhiteDot)
        assertTrue("Prediction dots should have zone-colored pixels", foundColoredDot)
    }

    @Test
    fun `compact mode renders without crash`() {
        val readings = (0L..30L step 5).map { reading(108, it) }
        val bitmap = GraphRenderer.render(
            readings, 400, 100, bgLow, bgHigh, windowMs, compact = true
        )
        assertEquals(400, bitmap.width)
    }
}
