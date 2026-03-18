package com.psjostrom.strimma.notification

import android.graphics.*
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.graph.*

object GraphRenderer {

    private const val DOT_RADIUS = 4f
    private const val LINE_WIDTH = 2f
    private const val BG_COLOR = Color.TRANSPARENT

    private const val ZONE_LOW = 0x20FF6B6B.toInt()
    private const val ZONE_HIGH = 0x20FFBE76.toInt()
    private const val ZONE_IN_RANGE = 0x1256CCF2.toInt()
    private const val COLOR_AXIS_TEXT = 0xFF8892A0.toInt()

    fun render(
        readings: List<GlucoseReading>,
        width: Int,
        height: Int,
        bgLow: Double,
        bgHigh: Double,
        windowMs: Long,
        compact: Boolean = false
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG_COLOR)

        val now = System.currentTimeMillis()
        val predictionMs = 30 * 60_000L
        val endTime = now + predictionMs
        val startTime = endTime - windowMs - predictionMs

        val marginLeft = if (compact) 4f else 40f
        val marginRight = if (compact) 4f else 10f
        val marginTop = if (compact) 4f else 10f
        val marginBottom = if (compact) 4f else 24f
        val plotWidth = width - marginLeft - marginRight
        val plotHeight = height - marginTop - marginBottom

        val visible = readings.filter { it.ts >= startTime }.sortedBy { it.ts }
        val yr = computeYRange(visible.map { it.mmol }, bgLow, bgHigh)

        val totalMs = endTime - startTime
        fun xFor(ts: Long): Float = marginLeft + ((ts - startTime).toFloat() / totalMs) * plotWidth
        fun yFor(mmol: Double): Float = marginTop + ((yr.yMax - mmol) / yr.range).toFloat() * plotHeight

        // Zone backgrounds
        val zonePaint = Paint().apply { style = Paint.Style.FILL }
        val highY = yFor(bgHigh)
        val lowY = yFor(bgLow)

        zonePaint.color = ZONE_IN_RANGE
        canvas.drawRect(marginLeft, highY, width - marginRight, lowY, zonePaint)
        zonePaint.color = ZONE_LOW
        canvas.drawRect(marginLeft, lowY, width - marginRight, height - marginBottom, zonePaint)
        zonePaint.color = ZONE_HIGH
        canvas.drawRect(marginLeft, marginTop, width - marginRight, highY, zonePaint)

        // Threshold lines
        val thresholdPaint = Paint().apply {
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        thresholdPaint.color = CANVAS_HIGH
        thresholdPaint.pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
        canvas.drawLine(marginLeft, lowY, width - marginRight, lowY, thresholdPaint)
        canvas.drawLine(marginLeft, highY, width - marginRight, highY, thresholdPaint)

        thresholdPaint.color = CANVAS_LOW
        thresholdPaint.pathEffect = null
        canvas.drawLine(marginLeft, yFor(CRITICAL_LOW), width - marginRight, yFor(CRITICAL_LOW), thresholdPaint)
        canvas.drawLine(marginLeft, yFor(CRITICAL_HIGH), width - marginRight, yFor(CRITICAL_HIGH), thresholdPaint)

        if (visible.isEmpty()) return bitmap

        // Readings
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = LINE_WIDTH
            style = Paint.Style.STROKE
        }
        val dotR = if (compact) 3f else DOT_RADIUS

        for (i in visible.indices) {
            val r = visible[i]
            val x = xFor(r.ts)
            val y = yFor(r.mmol)
            val color = canvasColorFor(r.mmol, bgLow, bgHigh)

            if (i < visible.lastIndex) {
                val next = visible[i + 1]
                linePaint.color = color
                canvas.drawLine(x, y, xFor(next.ts), yFor(next.mmol), linePaint)
            }

            dotPaint.color = color
            canvas.drawCircle(x, y, dotR, dotPaint)
        }

        // Prediction dots
        if (visible.size >= 2) {
            val last = visible.last()
            val prev = visible[visible.size - 2]
            val dtMin = (last.ts - prev.ts) / 60_000.0
            if (dtMin > 0) {
                val ratePerMin = (last.mmol - prev.mmol) / dtMin
                val predPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    alpha = 77 // ~0.3
                }
                val predLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    strokeWidth = LINE_WIDTH
                    style = Paint.Style.STROKE
                    pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
                    alpha = 77
                }
                var prevPx = xFor(last.ts)
                var prevPy = yFor(last.mmol)
                for (m in 1..30) {
                    val predMmol = (last.mmol + ratePerMin * m).coerceIn(1.0, 30.0)
                    val predTs = last.ts + m * 60_000L
                    val px = xFor(predTs)
                    val py = yFor(predMmol)
                    if (px > width - marginRight) break
                    val color = canvasColorFor(predMmol, bgLow, bgHigh)
                    predLinePaint.color = color
                    predLinePaint.alpha = 77
                    canvas.drawLine(prevPx, prevPy, px, py, predLinePaint)
                    predPaint.color = color
                    predPaint.alpha = 77
                    canvas.drawCircle(px, py, dotR * 0.7f, predPaint)
                    prevPx = px
                    prevPy = py
                }
            }
        }

        // Axis labels (skip for compact)
        if (!compact) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_AXIS_TEXT
                textSize = 22f
                textAlign = Paint.Align.CENTER
            }

            val intervalMs = if (windowMs <= 3600_000L) 15 * 60_000L else 30 * 60_000L
            var tickTime = startTime - (startTime % intervalMs) + intervalMs
            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            while (tickTime < endTime) {
                val x = xFor(tickTime)
                if (x > marginLeft && x < width - marginRight) {
                    canvas.drawText(sdf.format(java.util.Date(tickTime)), x, height.toFloat() - 4f, textPaint)
                }
                tickTime += intervalMs
            }

            textPaint.textAlign = Paint.Align.RIGHT
            val yStep = if (yr.range > 10) 2.0 else 1.0
            var yLabel = Math.ceil(yr.yMin / yStep) * yStep
            while (yLabel <= yr.yMax) {
                val y = yFor(yLabel)
                if (y > marginTop + 8 && y < height - marginBottom - 8) {
                    canvas.drawText("%.0f".format(yLabel), marginLeft - 4f, y + 6f, textPaint)
                }
                yLabel += yStep
            }
        }

        return bitmap
    }
}
