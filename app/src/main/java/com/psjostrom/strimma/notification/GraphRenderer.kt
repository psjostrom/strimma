package com.psjostrom.strimma.notification

import android.graphics.*
import com.psjostrom.strimma.data.GlucoseReading

object GraphRenderer {

    private const val DOT_RADIUS = 4f
    private const val LINE_WIDTH = 2f
    private const val BG_COLOR = Color.TRANSPARENT
    private const val COLOR_IN_RANGE = 0xFF56CCF2.toInt()
    private const val COLOR_HIGH = 0xFFFFBE76.toInt()
    private const val COLOR_LOW = 0xFFFF6B6B.toInt()
    private const val COLOR_AXIS_TEXT = 0xFF8892A0.toInt()

    private const val ZONE_LOW = 0x20FF6B6B.toInt()
    private const val ZONE_HIGH = 0x20FFBE76.toInt()
    private const val ZONE_IN_RANGE = 0x1256CCF2.toInt()

    private const val CRITICAL_LOW = 3.0
    private const val CRITICAL_HIGH = 13.0

    /**
     * @param compact If true, renders a mini graph (no axis labels, no margins, thicker dots).
     */
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
        val startTime = now - windowMs

        val marginLeft = if (compact) 4f else 40f
        val marginRight = if (compact) 4f else 10f
        val marginTop = if (compact) 4f else 10f
        val marginBottom = if (compact) 4f else 24f
        val plotWidth = width - marginLeft - marginRight
        val plotHeight = height - marginTop - marginBottom

        val allMmol = readings.filter { it.ts >= startTime }.map { it.mmol }
        val yMin = minOf(bgLow - 0.5, CRITICAL_LOW - 0.3,
            if (allMmol.isEmpty()) bgLow - 0.5 else allMmol.min() - 0.3)
        val yMax = maxOf(bgHigh + 0.5, CRITICAL_HIGH + 0.3,
            if (allMmol.isEmpty()) bgHigh + 0.5 else allMmol.max() + 0.3)
        val yRange = yMax - yMin

        fun xFor(ts: Long): Float = marginLeft + ((ts - startTime).toFloat() / windowMs) * plotWidth
        fun yFor(mmol: Double): Float = marginTop + ((yMax - mmol) / yRange).toFloat() * plotHeight

        // Zone backgrounds
        val zonePaint = Paint().apply { style = Paint.Style.FILL }

        val highY = yFor(bgHigh)
        val lowY = yFor(bgLow)

        // In-range zone
        zonePaint.color = ZONE_IN_RANGE
        canvas.drawRect(marginLeft, highY, width - marginRight, lowY, zonePaint)

        // Low zone (below bgLow)
        zonePaint.color = ZONE_LOW
        canvas.drawRect(marginLeft, lowY, width - marginRight, height - marginBottom, zonePaint)

        // High zone (above bgHigh)
        zonePaint.color = ZONE_HIGH
        canvas.drawRect(marginLeft, marginTop, width - marginRight, highY, zonePaint)

        // Threshold lines
        val thresholdPaint = Paint().apply {
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        thresholdPaint.color = COLOR_HIGH
        thresholdPaint.pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
        canvas.drawLine(marginLeft, lowY, width - marginRight, lowY, thresholdPaint)
        canvas.drawLine(marginLeft, highY, width - marginRight, highY, thresholdPaint)

        thresholdPaint.color = COLOR_LOW
        thresholdPaint.pathEffect = null
        val critLowY = yFor(CRITICAL_LOW)
        canvas.drawLine(marginLeft, critLowY, width - marginRight, critLowY, thresholdPaint)
        val critHighY = yFor(CRITICAL_HIGH)
        canvas.drawLine(marginLeft, critHighY, width - marginRight, critHighY, thresholdPaint)

        // Readings
        val visible = readings.filter { it.ts >= startTime }.sortedBy { it.ts }
        if (visible.isEmpty()) return bitmap

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
            val color = colorFor(r.mmol, bgLow, bgHigh)

            if (i < visible.lastIndex) {
                val next = visible[i + 1]
                linePaint.color = color
                canvas.drawLine(x, y, xFor(next.ts), yFor(next.mmol), linePaint)
            }

            dotPaint.color = color
            canvas.drawCircle(x, y, dotR, dotPaint)
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
            while (tickTime < now) {
                val x = xFor(tickTime)
                if (x > marginLeft && x < width - marginRight) {
                    canvas.drawText(sdf.format(java.util.Date(tickTime)), x, height.toFloat() - 4f, textPaint)
                }
                tickTime += intervalMs
            }

            textPaint.textAlign = Paint.Align.RIGHT
            val yStep = if (yRange > 10) 2.0 else 1.0
            var yLabel = Math.ceil(yMin / yStep) * yStep
            while (yLabel <= yMax) {
                val y = yFor(yLabel)
                if (y > marginTop + 8 && y < height - marginBottom - 8) {
                    canvas.drawText("%.0f".format(yLabel), marginLeft - 4f, y + 6f, textPaint)
                }
                yLabel += yStep
            }
        }

        return bitmap
    }

    fun colorFor(mmol: Double, bgLow: Double, bgHigh: Double): Int = when {
        mmol <= CRITICAL_LOW -> COLOR_LOW
        mmol < bgLow -> COLOR_LOW
        mmol >= CRITICAL_HIGH -> COLOR_LOW
        mmol > bgHigh -> COLOR_HIGH
        else -> COLOR_IN_RANGE
    }
}
