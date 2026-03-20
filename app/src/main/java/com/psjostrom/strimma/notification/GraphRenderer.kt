package com.psjostrom.strimma.notification

import android.graphics.*
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
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

    // Margins
    private const val MARGIN_COMPACT = 4f
    private const val MARGIN_LEFT_FULL = 40f
    private const val MARGIN_RIGHT_FULL = 10f
    private const val MARGIN_TOP_FULL = 10f
    private const val MARGIN_BOTTOM_FULL = 24f

    // Dash patterns
    private const val DASH_LENGTH = 6f
    private const val DASH_GAP = 4f

    // Compact mode
    private const val COMPACT_DOT_RADIUS = 5f
    private const val COMPACT_DOT_SCALE = 0.6f

    // Time intervals
    private const val HOUR_IN_MS = 3600_000L
    private const val MINUTE_IN_MS = 60_000L
    private const val TIME_TICK_15_MIN = 15 * MINUTE_IN_MS
    private const val TIME_TICK_30_MIN = 30 * MINUTE_IN_MS

    // Axis label dimensions
    private const val LABEL_TEXT_SIZE = 22f
    private const val LABEL_Y_OFFSET = 4f
    private const val LABEL_X_OFFSET = 6f
    private const val LABEL_MARGIN_TOP = 8f
    private const val LABEL_MARGIN_BOTTOM = 8f

    // Y-axis step thresholds (all in mg/dL now — yRange is in mg/dL)
    private const val MGDL_Y_RANGE_THRESHOLD = 180.0
    private const val MGDL_Y_STEP_LARGE = 50.0
    private const val MGDL_Y_STEP_SMALL = 25.0
    private const val MMOL_Y_RANGE_THRESHOLD = 180.0

    // Alpha values
    private const val PREDICTION_ALPHA = 128

    // Gradient
    private const val GRADIENT_HEIGHT_FRACTION = 0.45f

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Sequential render pipeline
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
        val predictionMs = predictionMinutes * MINUTE_IN_MS
        val endTime = now + predictionMs
        val startTime = endTime - windowMs - predictionMs

        val marginLeft = if (compact) MARGIN_COMPACT else MARGIN_LEFT_FULL
        val marginRight = if (compact) MARGIN_COMPACT else MARGIN_RIGHT_FULL
        val marginTop = if (compact) MARGIN_COMPACT else MARGIN_TOP_FULL
        val marginBottom = if (compact) MARGIN_COMPACT else MARGIN_BOTTOM_FULL
        val plotWidth = width - marginLeft - marginRight
        val plotHeight = height - marginTop - marginBottom

        val visible = readings.filter { it.ts >= startTime }.sortedBy { it.ts }
        val yr = computeYRange(visible.map { it.sgv.toDouble() }, bgLow, bgHigh)

        val totalMs = endTime - startTime
        fun xFor(ts: Long): Float = marginLeft + ((ts - startTime).toFloat() / totalMs) * plotWidth
        fun yFor(mgdl: Double): Float = marginTop + ((yr.yMax - mgdl) / yr.range).toFloat() * plotHeight

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
            strokeWidth = LINE_WIDTH
            style = Paint.Style.STROKE
        }
        thresholdPaint.color = CANVAS_HIGH
        thresholdPaint.pathEffect = DashPathEffect(floatArrayOf(DASH_LENGTH, DASH_GAP), 0f)
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
        val dotR = if (compact) COMPACT_DOT_RADIUS else DOT_RADIUS

        for (i in visible.indices) {
            val r = visible[i]
            val x = xFor(r.ts)
            val y = yFor(r.sgv.toDouble())
            val color = canvasColorFor(r.sgv.toDouble(), bgLow, bgHigh)

            if (i < visible.lastIndex) {
                val next = visible[i + 1]
                linePaint.color = color
                canvas.drawLine(x, y, xFor(next.ts), yFor(next.sgv.toDouble()), linePaint)
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
                alpha = PREDICTION_ALPHA
            }
            val predLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = LINE_WIDTH
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(DASH_LENGTH, DASH_LENGTH), 0f)
                color = Color.WHITE
                alpha = PREDICTION_ALPHA
            }
            var prevPx = xFor(prediction.anchorTs)
            var prevPy = yFor(prediction.anchorMgdl)
            for (pt in prediction.points) {
                val px = xFor(prediction.anchorTs + pt.minuteOffset * MINUTE_IN_MS)
                val py = yFor(pt.mgdl)
                if (px > width - marginRight) break
                canvas.drawLine(prevPx, prevPy, px, py, predLinePaint)
                canvas.drawCircle(px, py, dotR * COMPACT_DOT_SCALE, predPaint)
                prevPx = px
                prevPy = py
            }

        }

        // Axis labels (skip for compact)
        if (!compact) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_AXIS_TEXT
                textSize = LABEL_TEXT_SIZE
                textAlign = Paint.Align.CENTER
            }

            val intervalMs = if (windowMs <= HOUR_IN_MS) TIME_TICK_15_MIN else TIME_TICK_30_MIN
            var tickTime = startTime - (startTime % intervalMs) + intervalMs
            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            while (tickTime < endTime) {
                val x = xFor(tickTime)
                if (x > marginLeft && x < width - marginRight) {
                    canvas.drawText(sdf.format(java.util.Date(tickTime)), x, height.toFloat() - LABEL_Y_OFFSET, textPaint)
                }
                tickTime += intervalMs
            }

            textPaint.textAlign = Paint.Align.RIGHT
            val yStep = if (glucoseUnit == GlucoseUnit.MGDL) {
                if (yr.range > MGDL_Y_RANGE_THRESHOLD) MGDL_Y_STEP_LARGE else MGDL_Y_STEP_SMALL
            } else {
                if (yr.range > MMOL_Y_RANGE_THRESHOLD) 2.0 * GlucoseUnit.MGDL_FACTOR else GlucoseUnit.MGDL_FACTOR
            }
            var yLabel = Math.ceil(yr.yMin / yStep) * yStep
            while (yLabel <= yr.yMax) {
                val y = yFor(yLabel)
                if (y > marginTop + LABEL_MARGIN_TOP && y < height - marginBottom - LABEL_MARGIN_BOTTOM) {
                    val labelText = if (glucoseUnit == GlucoseUnit.MGDL) {
                        "%.0f".format(yLabel)
                    } else {
                        "%.0f".format(yLabel / GlucoseUnit.MGDL_FACTOR)
                    }
                    canvas.drawText(labelText, marginLeft - LABEL_Y_OFFSET, y + LABEL_X_OFFSET, textPaint)
                }
                yLabel += yStep
            }
        }

        // Top gradient for widget text readability (compact only)
        if (compact) {
            val gradientPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height * GRADIENT_HEIGHT_FRACTION,
                    0xE0000000.toInt(), 0x00000000,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height * GRADIENT_HEIGHT_FRACTION, gradientPaint)
        }

        return bitmap
    }
}
