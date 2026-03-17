package com.psjostrom.strimma.notification

import android.graphics.*
import com.psjostrom.strimma.data.GlucoseReading

object GraphRenderer {

    private const val DOT_RADIUS = 4f
    private const val LINE_WIDTH = 2f
    private const val AXIS_TEXT_SIZE = 10f
    private const val BG_COLOR = 0xFF121212.toInt()
    private const val COLOR_IN_RANGE = 0xFF4FC3F7.toInt()
    private const val COLOR_HIGH = 0xFFFFB74D.toInt()
    private const val COLOR_LOW = 0xFFEF5350.toInt()
    private const val COLOR_THRESHOLD = 0x80FFFFFF.toInt()
    private const val COLOR_AXIS_TEXT = 0xFFB0B0B0.toInt()

    fun render(
        readings: List<GlucoseReading>,
        width: Int,
        height: Int,
        bgLow: Double,
        bgHigh: Double,
        windowMs: Long
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG_COLOR)

        if (readings.isEmpty()) return bitmap

        val now = System.currentTimeMillis()
        val startTime = now - windowMs

        // Margins
        val marginLeft = 30f
        val marginRight = 10f
        val marginTop = 10f
        val marginBottom = 20f
        val plotWidth = width - marginLeft - marginRight
        val plotHeight = height - marginTop - marginBottom

        // Y range: dynamic, minimum bgLow-0.5 to bgHigh+0.5
        val allMmol = readings.map { it.mmol }
        val yMin = minOf(bgLow - 0.5, allMmol.min() - 0.3)
        val yMax = maxOf(bgHigh + 0.5, allMmol.max() + 0.3)
        val yRange = yMax - yMin

        fun xFor(ts: Long): Float = marginLeft + ((ts - startTime).toFloat() / windowMs) * plotWidth
        fun yFor(mmol: Double): Float = marginTop + ((yMax - mmol) / yRange).toFloat() * plotHeight

        // Draw threshold lines (dashed)
        val thresholdPaint = Paint().apply {
            color = COLOR_THRESHOLD
            strokeWidth = 1f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
        }
        canvas.drawLine(marginLeft, yFor(bgLow), width - marginRight, yFor(bgLow), thresholdPaint)
        canvas.drawLine(marginLeft, yFor(bgHigh), width - marginRight, yFor(bgHigh), thresholdPaint)

        // Filter readings within window and sort by time
        val visible = readings.filter { it.ts >= startTime }.sortedBy { it.ts }

        // Draw connecting lines and dots
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = LINE_WIDTH
            style = Paint.Style.STROKE
        }

        for (i in visible.indices) {
            val r = visible[i]
            val x = xFor(r.ts)
            val y = yFor(r.mmol)
            val color = colorFor(r.mmol, bgLow, bgHigh)

            // Connecting line to next point
            if (i < visible.lastIndex) {
                val next = visible[i + 1]
                linePaint.color = color
                canvas.drawLine(x, y, xFor(next.ts), yFor(next.mmol), linePaint)
            }

            // Dot
            dotPaint.color = color
            canvas.drawCircle(x, y, DOT_RADIUS, dotPaint)
        }

        // X axis: time labels at 15-min intervals for 1h window, 30-min for longer
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_AXIS_TEXT
            textSize = AXIS_TEXT_SIZE * (width / 400f) // Scale text with bitmap size
            textAlign = Paint.Align.CENTER
        }

        val intervalMs = if (windowMs <= 3600_000L) 15 * 60_000L else 30 * 60_000L
        var tickTime = startTime - (startTime % intervalMs) + intervalMs
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        while (tickTime < now) {
            val x = xFor(tickTime)
            if (x > marginLeft && x < width - marginRight) {
                canvas.drawText(sdf.format(java.util.Date(tickTime)), x, height.toFloat() - 2f, textPaint)
            }
            tickTime += intervalMs
        }

        // Y axis: mmol labels
        textPaint.textAlign = Paint.Align.RIGHT
        val yStep = if (yRange > 8) 2.0 else 1.0
        var yLabel = Math.ceil(yMin / yStep) * yStep
        while (yLabel <= yMax) {
            val y = yFor(yLabel)
            if (y > marginTop + 5 && y < height - marginBottom - 5) {
                canvas.drawText("%.0f".format(yLabel), marginLeft - 3f, y + 4f, textPaint)
            }
            yLabel += yStep
        }

        return bitmap
    }

    fun colorFor(mmol: Double, bgLow: Double, bgHigh: Double): Int = when {
        mmol < bgLow -> COLOR_LOW
        mmol > bgHigh -> COLOR_HIGH
        else -> COLOR_IN_RANGE
    }
}
