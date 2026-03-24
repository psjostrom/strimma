package com.psjostrom.strimma.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.psjostrom.strimma.data.AgpBucket
import com.psjostrom.strimma.data.AgpCalculator
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.graph.computeYRange
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.BelowLow
import com.psjostrom.strimma.ui.theme.GraphAxisText
import com.psjostrom.strimma.ui.theme.InRange
import com.psjostrom.strimma.ui.theme.InRangeZone

private const val MINUTES_PER_DAY = 1440f
private const val LEFT_MARGIN = 48f
private const val RIGHT_MARGIN = 12f
private const val TOP_MARGIN = 12f
private const val BOTTOM_MARGIN = 28f
private const val AXIS_LABEL_SIZE = 24f
private const val AXIS_LABEL_OFFSET = 8f
private const val AXIS_LABEL_BASELINE_OFFSET = 3f
private const val X_AXIS_LABEL_SPACING = 2f
private const val HOURS_STEP = 3
private const val HOURS_IN_DAY = 23
private const val MINUTES_PER_HOUR = 60f
private const val MEDIAN_STROKE_WIDTH = 5f
private const val THRESHOLD_STROKE_WIDTH = 2f
private const val DASH_LENGTH = 10f
private const val DASH_GAP = 8f
private const val THRESHOLD_ALPHA = 0.6f
private const val OUTER_BAND_ALPHA = 0.12f
private const val INNER_BAND_ALPHA = 0.25f
private const val Y_STEP_MGDL = 50.0
private const val Y_STEP_MMOL = 2.0

private data class ChartBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

@Composable
fun AgpChart(
    buckets: List<AgpBucket>,
    glucoseUnit: GlucoseUnit,
    modifier: Modifier = Modifier
) {
    if (buckets.isEmpty()) return

    val allValues = buckets.flatMap { listOf(it.p5, it.p95) }
    val yRange = computeYRange(allValues, AgpCalculator.LOW, AgpCalculator.HIGH)
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_LENGTH, DASH_GAP))

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        val bounds = ChartBounds(
            left = LEFT_MARGIN,
            top = TOP_MARGIN,
            right = size.width - RIGHT_MARGIN,
            bottom = size.height - BOTTOM_MARGIN
        )

        fun xForMinute(minute: Int): Float =
            bounds.left + (minute / MINUTES_PER_DAY) * bounds.width

        fun yForMgdl(mgdl: Double): Float =
            bounds.bottom - ((mgdl - yRange.yMin) / yRange.range * bounds.height).toFloat()

        // Target zone fill (70-180)
        val zoneTop = yForMgdl(AgpCalculator.HIGH)
        val zoneBottom = yForMgdl(AgpCalculator.LOW)
        drawRect(
            color = InRangeZone,
            topLeft = Offset(bounds.left, zoneTop),
            size = androidx.compose.ui.geometry.Size(bounds.width, zoneBottom - zoneTop)
        )

        // 5th-95th band
        drawPercentileBand(buckets, { it.p5 }, { it.p95 }, InRange.copy(alpha = OUTER_BAND_ALPHA), bounds, yRange.yMin, yRange.range)

        // 25th-75th band
        drawPercentileBand(buckets, { it.p25 }, { it.p75 }, InRange.copy(alpha = INNER_BAND_ALPHA), bounds, yRange.yMin, yRange.range)

        // Threshold lines (dashed)
        drawLine(
            color = BelowLow.copy(alpha = THRESHOLD_ALPHA),
            start = Offset(bounds.left, yForMgdl(AgpCalculator.LOW)),
            end = Offset(bounds.right, yForMgdl(AgpCalculator.LOW)),
            strokeWidth = THRESHOLD_STROKE_WIDTH,
            pathEffect = dashEffect
        )
        drawLine(
            color = AboveHigh.copy(alpha = THRESHOLD_ALPHA),
            start = Offset(bounds.left, yForMgdl(AgpCalculator.HIGH)),
            end = Offset(bounds.right, yForMgdl(AgpCalculator.HIGH)),
            strokeWidth = THRESHOLD_STROKE_WIDTH,
            pathEffect = dashEffect
        )

        // Median line (bold)
        val medianPath = Path()
        buckets.forEachIndexed { i, bucket ->
            val x = xForMinute(bucket.minuteOfDay)
            val y = yForMgdl(bucket.p50)
            if (i == 0) medianPath.moveTo(x, y) else medianPath.lineTo(x, y)
        }
        drawPath(medianPath, InRange, style = Stroke(width = MEDIAN_STROKE_WIDTH))

        drawYAxis(yRange.yMin, yRange.yMax, glucoseUnit, bounds)
        drawXAxis(bounds)
    }
}

@Suppress("LongParameterList") // Chart drawing helper — bounds + y-axis mapping params are cohesive
private fun DrawScope.drawPercentileBand(
    buckets: List<AgpBucket>,
    lowerFn: (AgpBucket) -> Double,
    upperFn: (AgpBucket) -> Double,
    color: Color,
    bounds: ChartBounds,
    yMin: Double, yRange: Double
) {
    if (buckets.isEmpty()) return

    fun xForMinute(minute: Int) = bounds.left + (minute / MINUTES_PER_DAY) * bounds.width
    fun yForMgdl(mgdl: Double) = bounds.bottom - ((mgdl - yMin) / yRange * bounds.height).toFloat()

    val path = Path()
    buckets.forEachIndexed { i, b ->
        val x = xForMinute(b.minuteOfDay)
        val y = yForMgdl(upperFn(b))
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    buckets.reversed().forEach { b ->
        path.lineTo(xForMinute(b.minuteOfDay), yForMgdl(lowerFn(b)))
    }
    path.close()
    drawPath(path, color, style = Fill)
}

private fun DrawScope.drawYAxis(
    yMin: Double, yMax: Double,
    glucoseUnit: GlucoseUnit,
    bounds: ChartBounds
) {
    val paint = makeAxisPaint(GraphAxisText, android.graphics.Paint.Align.RIGHT)

    val stepMgdl = when (glucoseUnit) {
        GlucoseUnit.MGDL -> Y_STEP_MGDL
        GlucoseUnit.MMOL -> Y_STEP_MMOL * GlucoseUnit.MGDL_FACTOR
    }

    var value = (yMin / stepMgdl).toInt() * stepMgdl
    if (value < yMin) value += stepMgdl

    while (value <= yMax) {
        val y = bounds.bottom - ((value - yMin) / (yMax - yMin) * bounds.height).toFloat()
        if (y in bounds.top..bounds.bottom) {
            val label = glucoseUnit.format(value)
            drawContext.canvas.nativeCanvas.drawText(
                label, bounds.left - AXIS_LABEL_OFFSET, y + AXIS_LABEL_SIZE / AXIS_LABEL_BASELINE_OFFSET, paint
            )
        }
        value += stepMgdl
    }
}

private fun DrawScope.drawXAxis(bounds: ChartBounds) {
    val paint = makeAxisPaint(GraphAxisText, android.graphics.Paint.Align.CENTER)

    for (hour in 0..HOURS_IN_DAY step HOURS_STEP) {
        val x = bounds.left + (hour * MINUTES_PER_HOUR / MINUTES_PER_DAY) * bounds.width
        val label = "%02d".format(hour)
        drawContext.canvas.nativeCanvas.drawText(
            label, x, bounds.bottom + AXIS_LABEL_SIZE + X_AXIS_LABEL_SPACING, paint
        )
    }
}

private fun makeAxisPaint(textColor: Color, align: android.graphics.Paint.Align): android.graphics.Paint =
    android.graphics.Paint().apply {
        color = textColor.toArgb()
        textSize = AXIS_LABEL_SIZE
        textAlign = align
        isAntiAlias = true
    }
