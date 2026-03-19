package com.psjostrom.strimma.notification

import android.graphics.*
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.graph.CrossingType
import com.psjostrom.strimma.graph.PredictionComputer
import com.psjostrom.strimma.graph.canvasColorFor
import com.psjostrom.strimma.graph.computeYRange
import com.psjostrom.strimma.graph.CANVAS_HIGH
import com.psjostrom.strimma.graph.CANVAS_LOW
import com.psjostrom.strimma.graph.CRITICAL_HIGH
import com.psjostrom.strimma.graph.CRITICAL_LOW

object GraphRenderer {

    private const val DOT_RADIUS = 4f
    private const val LINE_WIDTH = 2f
    private const val BG_COLOR = Color.TRANSPARENT

    private const val ZONE_LOW = 0x20FF4D6A.toInt()
    private const val ZONE_HIGH = 0x20FFB800.toInt()
    private const val ZONE_IN_RANGE = 0x1256CCF2.toInt()
    private const val COLOR_AXIS_TEXT = 0xFFA898C0.toInt()

    fun render(
        readings: List<GlucoseReading>,
        width: Int,
        height: Int,
        bgLow: Double,
        bgHigh: Double,
        windowMs: Long,
        compact: Boolean = false,
        predictionMinutes: Int = 10,
        glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG_COLOR)

        val now = System.currentTimeMillis()
        val predictionMs = predictionMinutes * 60_000L
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
        val dotR = if (compact) 5f else DOT_RADIUS

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

        // Prediction curve (least-squares fit to last 12 min of readings)
        val prediction = PredictionComputer.compute(readings, predictionMinutes, bgLow, bgHigh)
        if (prediction != null) {
            val predPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.WHITE
                alpha = 128
            }
            val predLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = LINE_WIDTH
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
                color = Color.WHITE
                alpha = 128
            }
            var prevPx = xFor(prediction.anchorTs)
            var prevPy = yFor(prediction.anchorMmol)
            for (pt in prediction.points) {
                val px = xFor(prediction.anchorTs + pt.minuteOffset * 60_000L)
                val py = yFor(pt.mmol)
                if (px > width - marginRight) break
                canvas.drawLine(prevPx, prevPy, px, py, predLinePaint)
                canvas.drawCircle(px, py, dotR * 0.6f, predPaint)
                prevPx = px
                prevPy = py
            }

            // "Low in X min" / "High in X min" label at crossing point (skip for compact/widget)
            if (!compact) {
                prediction.crossing?.let { crossing ->
                    val crossTs = prediction.anchorTs + crossing.minutesUntil * 60_000L
                    val cx = xFor(crossTs)
                    val cy = yFor(crossing.mmolAtCrossing)
                    if (cx <= width - marginRight) {
                        val label = when (crossing.type) {
                            CrossingType.LOW -> "Low ${crossing.minutesUntil}m"
                            CrossingType.HIGH -> "High ${crossing.minutesUntil}m"
                        }
                        val labelColor = when (crossing.type) {
                            CrossingType.LOW -> CANVAS_LOW
                            CrossingType.HIGH -> CANVAS_HIGH
                        }
                        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = labelColor
                            textSize = 18f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            textAlign = Paint.Align.CENTER
                        }
                        val ly = if (crossing.type == CrossingType.LOW) cy + 16f else cy - 8f
                        canvas.drawText(label, cx, ly, labelPaint)
                    }
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
            val yStep = if (glucoseUnit == GlucoseUnit.MGDL) {
                val mgStep = if (yr.range * GlucoseUnit.MGDL_FACTOR > 180) 50.0 else 25.0
                mgStep / GlucoseUnit.MGDL_FACTOR
            } else {
                if (yr.range > 10) 2.0 else 1.0
            }
            var yLabel = Math.ceil(yr.yMin / yStep) * yStep
            while (yLabel <= yr.yMax) {
                val y = yFor(yLabel)
                if (y > marginTop + 8 && y < height - marginBottom - 8) {
                    val labelText = if (glucoseUnit == GlucoseUnit.MGDL) {
                        "%.0f".format(yLabel * GlucoseUnit.MGDL_FACTOR)
                    } else {
                        "%.0f".format(yLabel)
                    }
                    canvas.drawText(labelText, marginLeft - 4f, y + 6f, textPaint)
                }
                yLabel += yStep
            }
        }

        // Top gradient for widget text readability (compact only)
        if (compact) {
            val gradientPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height * 0.45f,
                    0xE0000000.toInt(), 0x00000000,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height * 0.45f, gradientPaint)
        }

        return bitmap
    }
}
