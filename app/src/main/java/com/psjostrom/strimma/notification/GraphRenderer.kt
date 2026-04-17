package com.psjostrom.strimma.notification

import android.graphics.*
import androidx.core.graphics.createBitmap
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.health.StoredExerciseSession
import com.psjostrom.strimma.graph.Prediction
import com.psjostrom.strimma.graph.PredictionComputer
import com.psjostrom.strimma.graph.canvasColorFor
import com.psjostrom.strimma.graph.computeYAxisLabels
import com.psjostrom.strimma.graph.computeYRange
import com.psjostrom.strimma.graph.CANVAS_EXERCISE
import com.psjostrom.strimma.graph.CANVAS_HIGH
import com.psjostrom.strimma.graph.CANVAS_DANGER
import com.psjostrom.strimma.graph.CRITICAL_HIGH
import com.psjostrom.strimma.graph.CRITICAL_LOW

object GraphRenderer {

    private const val DOT_RADIUS = 4f
    private const val LINE_WIDTH = 2f
    private const val BG_COLOR = Color.TRANSPARENT

    private const val ZONE_LOW = 0x20FF4D6A.toInt()
    private const val ZONE_HIGH = 0x20FFB800.toInt()
    private const val ZONE_IN_RANGE = 0x1256CCF2.toInt()
    // Medium gray — notification graph bitmap is transparent (BG_COLOR), so it sits on the
    // system notification surface which can be light or dark. Lavender was invisible on light.
    private const val COLOR_AXIS_TEXT = 0xFF808080.toInt()

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
    private val TIME_TICK_15_MIN = 15 * MS_PER_MINUTE
    private val TIME_TICK_30_MIN = 30 * MS_PER_MINUTE

    // Axis label dimensions
    private const val LABEL_TEXT_SIZE = 22f
    private const val LABEL_Y_OFFSET = 4f
    private const val LABEL_X_OFFSET = 6f
    private const val LABEL_MARGIN_TOP = 8f
    private const val LABEL_MARGIN_BOTTOM = 8f

    // Alpha values
    private const val PREDICTION_ALPHA = 128
    private const val EXERCISE_FILL_ALPHA = 38
    private const val EXERCISE_BORDER_ALPHA = 127
    private const val EXERCISE_BORDER_WIDTH = 2f


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
        glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
        exerciseSessions: List<StoredExerciseSession> = emptyList(),
        prediction: Prediction? = null
    ): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG_COLOR)

        val now = System.currentTimeMillis()
        val predictionMs = predictionMinutes * MS_PER_MINUTE
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

        thresholdPaint.color = CANVAS_DANGER
        thresholdPaint.pathEffect = null
        canvas.drawLine(marginLeft, yFor(CRITICAL_LOW), width - marginRight, yFor(CRITICAL_LOW), thresholdPaint)
        canvas.drawLine(marginLeft, yFor(CRITICAL_HIGH), width - marginRight, yFor(CRITICAL_HIGH), thresholdPaint)

        // Exercise bands (rendered before dots/lines so BG data draws on top)
        if (exerciseSessions.isNotEmpty()) {
            val exercisePaint = Paint().apply {
                color = CANVAS_EXERCISE
                alpha = EXERCISE_FILL_ALPHA
                style = Paint.Style.FILL
            }
            val exerciseBorderPaint = Paint().apply {
                color = CANVAS_EXERCISE
                alpha = EXERCISE_BORDER_ALPHA
                strokeWidth = EXERCISE_BORDER_WIDTH
                style = Paint.Style.STROKE
            }
            for (session in exerciseSessions) {
                if (session.endTime < startTime || session.startTime > endTime) continue
                val xStart = xFor(session.startTime).coerceIn(marginLeft, width - marginRight)
                val xEnd = xFor(session.endTime).coerceIn(marginLeft, width - marginRight)
                if (xEnd <= xStart) continue

                canvas.drawRect(xStart, marginTop, xEnd, marginTop + plotHeight, exercisePaint)
                if (xFor(session.startTime) >= marginLeft) {
                    canvas.drawLine(xStart, marginTop, xStart, marginTop + plotHeight, exerciseBorderPaint)
                }
                if (xFor(session.endTime) <= width - marginRight) {
                    canvas.drawLine(xEnd, marginTop, xEnd, marginTop + plotHeight, exerciseBorderPaint)
                }
            }
        }

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
        val pred = prediction ?: PredictionComputer.compute(readings, predictionMinutes, bgLow, bgHigh)
        if (pred != null) {
            val predPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            val predLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = LINE_WIDTH
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(DASH_LENGTH, DASH_LENGTH), 0f)
            }
            var prevPx = xFor(pred.anchorTs)
            var prevPy = yFor(pred.anchorMgdl)
            for (pt in pred.points) {
                val px = xFor(pred.anchorTs + pt.minuteOffset * MS_PER_MINUTE)
                val py = yFor(pt.mgdl)
                if (px > width - marginRight) break
                val predColor = canvasColorFor(pt.mgdl, bgLow, bgHigh)
                predLinePaint.color = predColor
                predLinePaint.alpha = PREDICTION_ALPHA
                predPaint.color = predColor
                predPaint.alpha = PREDICTION_ALPHA
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
            for (label in computeYAxisLabels(yr, glucoseUnit)) {
                val y = yFor(label.mgdl)
                if (y > marginTop + LABEL_MARGIN_TOP && y < height - marginBottom - LABEL_MARGIN_BOTTOM) {
                    canvas.drawText(label.text, marginLeft - LABEL_Y_OFFSET, y + LABEL_X_OFFSET, textPaint)
                }
            }
        }

        return bitmap
    }
}
