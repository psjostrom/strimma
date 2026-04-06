package com.psjostrom.strimma.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.psjostrom.strimma.data.Direction
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.graph.CANVAS_TEXT_SECONDARY
import com.psjostrom.strimma.graph.CANVAS_TEXT_TERTIARY
import com.psjostrom.strimma.graph.canvasColorFor
import com.psjostrom.strimma.graph.computeYRange
import com.psjostrom.strimma.notification.AlertManager

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
        color = CANVAS_TEXT_SECONDARY
    }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = CANVAS_TEXT_TERTIARY
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
        bgLow: Double,
        bgHigh: Double,
        timeText: String
    ) {
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        if (reading == null) return

        val density = width / REFERENCE_WIDTH
        val centerX = width / 2f

        // Determine status color
        val ageMs = System.currentTimeMillis() - reading.ts
        val stale = ageMs > AlertManager.STALE_THRESHOLD_MINUTES * MS_PER_MINUTE
        val statusColor = if (stale) {
            CANVAS_TEXT_TERTIARY
        } else {
            canvasColorFor(reading.sgv.toDouble(), bgLow, bgHigh)
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
        val bgLow: Double,
        val bgHigh: Double
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

        val yr = computeYRange(readings.map { it.sgv.toDouble() }, layout.bgLow, layout.bgHigh)
        val yRange = if (yr.range < MIN_Y_RANGE) MIN_Y_RANGE else yr.range

        for (reading in readings) {
            val age = now - reading.ts
            if (age < 0 || age > windowMs) continue

            val x = left + graphWidth * (1f - age.toFloat() / windowMs)
            val yFraction = 1f - ((reading.sgv.toDouble() - yr.yMin) / yRange).toFloat()
            val y = layout.top + yFraction * graphHeight

            dotPaint.color = canvasColorFor(
                reading.sgv.toDouble(),
                layout.bgLow,
                layout.bgHigh
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
        private const val MIN_Y_RANGE = 36.0

        private const val GRAPH_WINDOW_MS = 3_600_000L // 1 hour
    }
}
