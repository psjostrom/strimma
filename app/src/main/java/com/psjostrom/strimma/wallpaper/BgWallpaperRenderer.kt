package com.psjostrom.strimma.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.psjostrom.strimma.data.Direction
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.graph.canvasColorFor

class BgWallpaperRenderer {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    private val deltaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = SECONDARY_TEXT_COLOR
    }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = TERTIARY_TEXT_COLOR
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    @Suppress("LongParameterList") // Renderer needs all display parameters
    fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        reading: GlucoseReading?,
        unit: GlucoseUnit,
        showGraph: Boolean,
        recentReadings: List<GlucoseReading>,
        bgLow: Int,
        bgHigh: Int
    ) {
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        if (reading == null) return

        val density = width / REFERENCE_WIDTH
        val centerX = width / 2f

        // Determine status color
        val ageMs = System.currentTimeMillis() - reading.ts
        val stale = ageMs > STALE_THRESHOLD_MS
        val statusColor = if (stale) {
            TERTIARY_TEXT_COLOR
        } else {
            canvasColorFor(reading.sgv.toDouble(), bgLow.toDouble(), bgHigh.toDouble())
        }

        // BG value text
        val bgText = unit.format(reading.sgv)
        bgPaint.textSize = BG_TEXT_SIZE * density
        bgPaint.color = statusColor

        // Arrow
        val direction = Direction.parse(reading.direction)
        val arrowText = direction.arrow
        arrowPaint.textSize = ARROW_TEXT_SIZE * density
        arrowPaint.color = statusColor

        // Measure BG text width to position arrow next to it
        val bgTextWidth = bgPaint.measureText(bgText)
        val arrowWidth = arrowPaint.measureText(arrowText)
        val combinedWidth = bgTextWidth + ARROW_GAP * density + arrowWidth
        val bgX = centerX - (combinedWidth / 2f) + (bgTextWidth / 2f)

        // Vertical layout: center the text block vertically, shift up if graph is shown
        val textBlockHeight = (BG_TEXT_SIZE + DELTA_TEXT_SIZE + TIME_TEXT_SIZE + LINE_SPACING * 2) * density
        val graphHeight = if (showGraph && recentReadings.size >= 2) GRAPH_HEIGHT * density else 0f
        val totalHeight = textBlockHeight + (if (graphHeight > 0) GRAPH_TOP_MARGIN * density + graphHeight else 0f)
        var y = (height - totalHeight) / 2f

        // Draw BG value
        y += BG_TEXT_SIZE * density
        canvas.drawText(bgText, bgX, y, bgPaint)

        // Draw arrow next to BG value
        val arrowX = bgX + bgTextWidth / 2f + ARROW_GAP * density
        canvas.drawText(arrowText, arrowX, y, arrowPaint)

        // Draw delta
        y += LINE_SPACING * density
        deltaPaint.textSize = DELTA_TEXT_SIZE * density
        val deltaText = if (reading.delta != null) {
            unit.formatDelta(reading.delta)
        } else {
            ""
        }
        if (deltaText.isNotEmpty()) {
            y += DELTA_TEXT_SIZE * density
            canvas.drawText(deltaText, centerX, y, deltaPaint)
        }

        // Draw time since reading
        y += LINE_SPACING * density
        timePaint.textSize = TIME_TEXT_SIZE * density
        val ageMinutes = (ageMs / MS_PER_MINUTE).toInt()
        val timeText = if (ageMinutes < 1) "Just now" else "$ageMinutes min ago"
        y += TIME_TEXT_SIZE * density
        canvas.drawText(timeText, centerX, y, timePaint)

        // Draw mini graph
        if (showGraph && recentReadings.size >= 2) {
            y += GRAPH_TOP_MARGIN * density
            drawMiniGraph(canvas, recentReadings, GraphLayout(centerX, y, density, bgLow, bgHigh))
        }
    }

    private data class GraphLayout(
        val centerX: Float,
        val top: Float,
        val density: Float,
        val bgLow: Int,
        val bgHigh: Int
    )

    private fun drawMiniGraph(
        canvas: Canvas,
        readings: List<GlucoseReading>,
        layout: GraphLayout
    ) {
        val graphWidth = GRAPH_WIDTH * layout.density
        val graphHeight = GRAPH_HEIGHT * layout.density
        val left = layout.centerX - graphWidth / 2f
        val dotRadius = DOT_RADIUS * layout.density

        val now = System.currentTimeMillis()
        val windowMs = GRAPH_WINDOW_MS

        // Y range from readings
        val minSgv = readings.minOf { it.sgv }.toDouble()
        val maxSgv = readings.maxOf { it.sgv }.toDouble()
        val yMin = minOf(minSgv - Y_PADDING, layout.bgLow.toDouble() - Y_PADDING)
        val yMax = maxOf(maxSgv + Y_PADDING, layout.bgHigh.toDouble() + Y_PADDING)
        val yRange = if (yMax - yMin < MIN_Y_RANGE) MIN_Y_RANGE else yMax - yMin

        for (reading in readings) {
            val age = now - reading.ts
            if (age < 0 || age > windowMs) continue

            val x = left + graphWidth * (1f - age.toFloat() / windowMs)
            val yFraction = 1f - ((reading.sgv.toDouble() - yMin) / yRange).toFloat()
            val y = layout.top + yFraction * graphHeight

            dotPaint.color = canvasColorFor(
                reading.sgv.toDouble(),
                layout.bgLow.toDouble(),
                layout.bgHigh.toDouble()
            )
            canvas.drawCircle(x, y, dotRadius, dotPaint)
        }
    }

    companion object {
        // Layout constants (in dp-like units, scaled by density)
        private const val REFERENCE_WIDTH = 400f
        private const val BG_TEXT_SIZE = 80f
        private const val ARROW_TEXT_SIZE = 40f
        private const val DELTA_TEXT_SIZE = 20f
        private const val TIME_TEXT_SIZE = 16f
        private const val LINE_SPACING = 8f
        private const val ARROW_GAP = 4f

        private const val GRAPH_WIDTH = 200f
        private const val GRAPH_HEIGHT = 60f
        private const val GRAPH_TOP_MARGIN = 16f
        private const val DOT_RADIUS = 3f
        private const val Y_PADDING = 10.0
        private const val MIN_Y_RANGE = 36.0

        private const val GRAPH_WINDOW_MS = 3_600_000L // 1 hour
        private const val STALE_THRESHOLD_MS = 10L * 60 * 1000 // 10 minutes
        private const val MS_PER_MINUTE = 60_000L

        // Colors matching Color.kt: DarkTextSecondary, DarkTextTertiary
        const val SECONDARY_TEXT_COLOR = 0xFFA898C0.toInt()
        const val TERTIARY_TEXT_COLOR = 0xFF6A5F80.toInt()
    }
}
