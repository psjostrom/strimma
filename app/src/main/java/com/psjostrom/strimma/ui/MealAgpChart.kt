package com.psjostrom.strimma.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.meal.MealAgpBucket
import com.psjostrom.strimma.graph.computeYRange
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.BelowLow
import com.psjostrom.strimma.ui.theme.GraphAxisText
import com.psjostrom.strimma.ui.theme.InRange
import com.psjostrom.strimma.ui.theme.InRangeZone

private const val LEFT_MARGIN = 48f
private const val RIGHT_MARGIN = 12f
private const val TOP_MARGIN = 12f
private const val BOTTOM_MARGIN = 28f
private const val AXIS_LABEL_SIZE = 24f
private const val AXIS_LABEL_OFFSET = 8f
private const val AXIS_LABEL_BASELINE_OFFSET = 3f
private const val MEDIAN_STROKE_WIDTH = 5f
private const val THRESHOLD_STROKE_WIDTH = 2f
private const val DASH_LENGTH = 10f
private const val DASH_GAP = 8f
private const val THRESHOLD_ALPHA = 0.6f
private const val OUTER_BAND_ALPHA = 0.12f
private const val INNER_BAND_ALPHA = 0.25f
private const val Y_STEP_MGDL = 50.0
private const val Y_STEP_MMOL = 2.0
private const val X_LABEL_SPACING = 2f
private const val MINUTES_STEP = 60

@Composable
fun MealAgpChart(
    buckets: List<MealAgpBucket>,
    maxWindowMinutes: Int,
    bgLow: Float,
    bgHigh: Float,
    glucoseUnit: GlucoseUnit,
    modifier: Modifier = Modifier
) {
    if (buckets.isEmpty()) return

    val allValues = buckets.flatMap { listOf(it.p5, it.p95) }
    val yRange = computeYRange(allValues, bgLow.toDouble(), bgHigh.toDouble())
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_LENGTH, DASH_GAP))
    val maxMinutes = buckets.last().minuteFromMeal.toFloat().coerceAtLeast(1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        val left = LEFT_MARGIN
        val top = TOP_MARGIN
        val right = size.width - RIGHT_MARGIN
        val bottom = size.height - BOTTOM_MARGIN
        val chartWidth = right - left
        val chartHeight = bottom - top

        fun xForMinute(minute: Int): Float =
            left + (minute / maxMinutes) * chartWidth

        fun yForMgdl(mgdl: Double): Float =
            bottom - ((mgdl - yRange.yMin) / yRange.range * chartHeight).toFloat()

        // Target zone fill
        drawRect(
            color = InRangeZone,
            topLeft = Offset(left, yForMgdl(bgHigh.toDouble())),
            size = Size(chartWidth, yForMgdl(bgLow.toDouble()) - yForMgdl(bgHigh.toDouble()))
        )

        // 5th-95th band
        drawBand(buckets, { it.p5 }, { it.p95 }, InRange.copy(alpha = OUTER_BAND_ALPHA),
            left, bottom, chartWidth, chartHeight, maxMinutes, yRange.yMin, yRange.range)

        // 25th-75th band
        drawBand(buckets, { it.p25 }, { it.p75 }, InRange.copy(alpha = INNER_BAND_ALPHA),
            left, bottom, chartWidth, chartHeight, maxMinutes, yRange.yMin, yRange.range)

        // Threshold lines
        drawLine(
            color = BelowLow.copy(alpha = THRESHOLD_ALPHA),
            start = Offset(left, yForMgdl(bgLow.toDouble())),
            end = Offset(right, yForMgdl(bgLow.toDouble())),
            strokeWidth = THRESHOLD_STROKE_WIDTH, pathEffect = dashEffect
        )
        drawLine(
            color = AboveHigh.copy(alpha = THRESHOLD_ALPHA),
            start = Offset(left, yForMgdl(bgHigh.toDouble())),
            end = Offset(right, yForMgdl(bgHigh.toDouble())),
            strokeWidth = THRESHOLD_STROKE_WIDTH, pathEffect = dashEffect
        )

        // Median line
        val medianPath = Path()
        buckets.forEachIndexed { i, bucket ->
            val x = xForMinute(bucket.minuteFromMeal)
            val y = yForMgdl(bucket.p50)
            if (i == 0) medianPath.moveTo(x, y) else medianPath.lineTo(x, y)
        }
        drawPath(medianPath, InRange, style = Stroke(width = MEDIAN_STROKE_WIDTH))

        // Y-axis labels
        drawMealYAxis(yRange.yMin, yRange.yMax, glucoseUnit, left, top, bottom)

        // X-axis labels (minutes from meal)
        drawMealXAxis(maxMinutes.toInt(), left, bottom, chartWidth, maxMinutes)
    }
}

private fun DrawScope.drawBand(
    buckets: List<MealAgpBucket>,
    lowerFn: (MealAgpBucket) -> Double,
    upperFn: (MealAgpBucket) -> Double,
    color: Color,
    left: Float, bottom: Float, chartWidth: Float, chartHeight: Float,
    maxMinutes: Float, yMin: Double, yRange: Double
) {
    if (buckets.isEmpty()) return

    fun xFor(minute: Int) = left + (minute / maxMinutes) * chartWidth
    fun yFor(mgdl: Double) = bottom - ((mgdl - yMin) / yRange * chartHeight).toFloat()

    val path = Path()
    buckets.forEachIndexed { i, b ->
        val x = xFor(b.minuteFromMeal)
        if (i == 0) path.moveTo(x, yFor(upperFn(b))) else path.lineTo(x, yFor(upperFn(b)))
    }
    buckets.reversed().forEach { b ->
        path.lineTo(xFor(b.minuteFromMeal), yFor(lowerFn(b)))
    }
    path.close()
    drawPath(path, color, style = Fill)
}

private fun DrawScope.drawMealYAxis(
    yMin: Double, yMax: Double,
    glucoseUnit: GlucoseUnit,
    left: Float, top: Float, bottom: Float
) {
    val paint = android.graphics.Paint().apply {
        this.color = GraphAxisText.toArgb()
        textSize = AXIS_LABEL_SIZE
        textAlign = android.graphics.Paint.Align.RIGHT
        isAntiAlias = true
    }
    val chartHeight = bottom - top
    val stepMgdl = when (glucoseUnit) {
        GlucoseUnit.MGDL -> Y_STEP_MGDL
        GlucoseUnit.MMOL -> Y_STEP_MMOL * GlucoseUnit.MGDL_FACTOR
    }

    var value = (yMin / stepMgdl).toInt() * stepMgdl
    if (value < yMin) value += stepMgdl
    while (value <= yMax) {
        val y = bottom - ((value - yMin) / (yMax - yMin) * chartHeight).toFloat()
        if (y in top..bottom) {
            drawContext.canvas.nativeCanvas.drawText(
                glucoseUnit.format(value),
                left - AXIS_LABEL_OFFSET,
                y + AXIS_LABEL_SIZE / AXIS_LABEL_BASELINE_OFFSET,
                paint
            )
        }
        value += stepMgdl
    }
}

private fun DrawScope.drawMealXAxis(
    maxWindowMinutes: Int,
    left: Float, bottom: Float, chartWidth: Float, maxMinutes: Float
) {
    val paint = android.graphics.Paint().apply {
        color = GraphAxisText.toArgb()
        textSize = AXIS_LABEL_SIZE
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }

    var minute = 0
    while (minute <= maxWindowMinutes) {
        val x = left + (minute / maxMinutes) * chartWidth
        val label = if (minute < 60) "${minute}m" else "${minute / 60}h"
        drawContext.canvas.nativeCanvas.drawText(
            label, x, bottom + AXIS_LABEL_SIZE + X_LABEL_SPACING, paint
        )
        minute += MINUTES_STEP
    }
}
