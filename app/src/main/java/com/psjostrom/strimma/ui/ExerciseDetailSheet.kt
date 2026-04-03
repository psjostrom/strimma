@file:Suppress("TooManyFunctions") // Single-screen composables + drawing helpers

package com.psjostrom.strimma.ui

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.health.ExerciseBGContext
import com.psjostrom.strimma.data.health.ExerciseCategory
import com.psjostrom.strimma.data.health.StoredExerciseSession
import com.psjostrom.strimma.data.health.Trend
import com.psjostrom.strimma.graph.computeYAxisLabels
import com.psjostrom.strimma.graph.computeYRange
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.BelowLow
import com.psjostrom.strimma.ui.theme.ExerciseDefault
import com.psjostrom.strimma.ui.theme.InRange
import com.psjostrom.strimma.ui.theme.LightTintDanger
import com.psjostrom.strimma.ui.theme.TintDanger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MINUTES_PER_HOUR = 60L

private fun formatDuration(totalMinutes: Long): String {
    val fmt = MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.SHORT)
    val hours = totalMinutes / MINUTES_PER_HOUR
    val mins = totalMinutes % MINUTES_PER_HOUR
    return if (hours > 0) {
        fmt.formatMeasures(Measure(hours, MeasureUnit.HOUR), Measure(mins, MeasureUnit.MINUTE))
    } else {
        fmt.formatMeasures(Measure(mins, MeasureUnit.MINUTE))
    }
}

@Composable
fun ExerciseDetailSheet(
    session: StoredExerciseSession,
    bgContext: ExerciseBGContext?,
    readings: List<GlucoseReading> = emptyList(),
    glucoseUnit: GlucoseUnit,
    bgLow: Double = 70.0,
    bgHigh: Double = 180.0,
    onDismiss: () -> Unit
) {
    StrimmaBottomSheet(onDismiss = onDismiss, expandable = bgContext != null) {

                // Header
                val category = ExerciseCategory.fromHCType(session.type)
                val typeName = stringResource(category.labelRes)
                val durationMin = ((session.endTime - session.startTime) / MS_PER_MINUTE).toInt()
                val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                val dateFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
                val timeRange = "${timeFmt.format(Date(session.startTime))}\u2013${timeFmt.format(Date(session.endTime))}"

                Text(
                    text = "${category.emoji} $typeName \u00B7 ${formatDuration(durationMin.toLong())}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${dateFmt.format(Date(session.startTime))} \u00B7 $timeRange",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                val profileName = bgContext?.let { ctx ->
                    val profile = com.psjostrom.strimma.data.health.CategoryStatsCalculator
                        .resolveProfile(session, ctx, null)
                    profile.displayName
                } ?: category.defaultMetabolicProfile.displayName
                Text(
                    text = profileName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                // BG Graph
                if (readings.size >= 2) {
                    var showFullContext by remember { mutableStateOf(true) }
                    val graphReadings = if (showFullContext) {
                        readings
                    } else {
                        readings.filter { it.ts in session.startTime..session.endTime }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        FilterChip(
                            selected = !showFullContext,
                            onClick = { showFullContext = false },
                            label = { Text(stringResource(R.string.exercise_graph_activity), fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ExerciseDefault.copy(alpha = 0.2f),
                                selectedLabelColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        FilterChip(
                            selected = showFullContext,
                            onClick = { showFullContext = true },
                            label = { Text(stringResource(R.string.exercise_graph_full), fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ExerciseDefault.copy(alpha = 0.2f),
                                selectedLabelColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }

                    if (graphReadings.size >= 2) {
                        ExerciseBGGraph(
                            readings = graphReadings,
                            session = session,
                            bgLow = bgLow,
                            bgHigh = bgHigh,
                            glucoseUnit = glucoseUnit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }

                if (bgContext == null) {
                    Text(
                        text = stringResource(R.string.exercise_detail_no_data),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .padding(vertical = 24.dp)
                            .align(Alignment.CenterHorizontally)
                    )
                } else {
                    val noValue = stringResource(R.string.exercise_detail_no_value)

                    // Stats grid — key numbers at a glance
                    StatsRow(bgContext, glucoseUnit, bgLow, noValue)

                    Spacer(Modifier.height(16.dp))

                    // Activity stats
                    if (bgContext.avgHR != null || bgContext.totalSteps != null || bgContext.activeCalories != null) {
                        SectionHeader(stringResource(R.string.exercise_detail_during))
                        Spacer(Modifier.height(6.dp))
                        bgContext.avgHR?.let { DetailRow(stringResource(R.string.exercise_detail_avg_hr), "$it bpm") }
                        bgContext.maxHR?.let { DetailRow(stringResource(R.string.exercise_detail_max_hr), "$it bpm") }
                        bgContext.totalSteps?.let { DetailRow(stringResource(R.string.exercise_detail_steps), "$it") }
                        bgContext.activeCalories?.let {
                            DetailRow(stringResource(R.string.exercise_detail_calories), "%.0f kcal".format(it))
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // Recovery section
                    SectionHeader(stringResource(R.string.exercise_detail_after))
                    Spacer(Modifier.height(6.dp))

                    val isHypo = bgContext.postExerciseHypo

                    bgContext.lowestBG?.let { lowest ->
                        val minutesAfter = bgContext.lowestBGTime?.let {
                            (it.toEpochMilli() - session.endTime) / MS_PER_MINUTE
                        }
                        DetailRowWithTiming(
                            label = stringResource(R.string.exercise_detail_lowest_bg),
                            value = glucoseUnit.format(lowest),
                            timing = minutesAfter?.takeIf { it > 0 }?.let { "${formatDuration(it)} after" },
                            valueColor = if (isHypo) BelowLow else null
                        )
                    }

                    bgContext.highestBG?.let { highest ->
                        val minutesAfter = bgContext.highestBGTime?.let {
                            (it.toEpochMilli() - session.endTime) / MS_PER_MINUTE
                        }
                        DetailRowWithTiming(
                            label = stringResource(R.string.exercise_detail_highest_bg),
                            value = glucoseUnit.format(highest),
                            timing = minutesAfter?.takeIf { it > 0 }?.let { "${formatDuration(it)} after" }
                        )
                    }

                    // Total drop
                    if (bgContext.entryBG != null) {
                        val overallLowest = listOfNotNull(bgContext.minBG, bgContext.lowestBG).minOrNull()
                        if (overallLowest != null) {
                            val drop = bgContext.entryBG - overallLowest
                            if (drop > 0) {
                                DetailRow(
                                    stringResource(R.string.exercise_detail_total_drop),
                                    glucoseUnit.format(drop.toDouble())
                                )
                            }
                        }
                    }

                    // Post-exercise hypo pill
                    if (isHypo) {
                        Spacer(Modifier.height(12.dp))
                        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                        Surface(
                            shape = RoundedCornerShape(100),
                            color = if (isDark) TintDanger else LightTintDanger
                        ) {
                            Text(
                                text = stringResource(R.string.exercise_detail_post_hypo),
                                color = BelowLow,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
    }
}

@Composable
private fun StatsRow(bgContext: ExerciseBGContext, glucoseUnit: GlucoseUnit, bgLow: Double, noValue: String) {
    val trendArrow = when (bgContext.entryTrend) {
        Trend.RISING -> "\u2197"
        Trend.FALLING -> "\u2198"
        Trend.STABLE -> "\u2192"
        null -> ""
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatBlock(
            label = stringResource(R.string.exercise_detail_entry_bg),
            value = bgContext.entryBG?.let { "${glucoseUnit.format(it)} $trendArrow" } ?: noValue
        )
        StatBlock(
            label = stringResource(R.string.exercise_detail_min_bg),
            value = bgContext.minBG?.let { glucoseUnit.format(it) } ?: noValue,
            valueColor = bgContext.minBG?.let { if (it < bgLow) BelowLow else null }
        )
        bgContext.avgHR?.let {
            StatBlock(
                label = stringResource(R.string.exercise_detail_avg_hr),
                value = "$it bpm"
            )
        }
    }
}

@Composable
private fun StatBlock(
    label: String,
    value: String,
    valueColor: Color? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

private const val LABEL_FONT_SP = 11f
private const val PAD_LEFT_DP = 28f
private const val PAD_BOTTOM_DP = 18f
private const val PAD_TOP_DP = 4f
private const val PAD_RIGHT_DP = 20f
private const val LABEL_GAP_DP = 6f
private const val LABEL_BASELINE_FRAC = 3f
private const val X_LABEL_GAP_DP = 4f
private const val SHORT_RANGE_MINUTES = 30L
private const val MEDIUM_RANGE_MINUTES = 120L
private const val LABEL_COUNT_SHORT = 3
private const val LABEL_COUNT_MEDIUM = 4
private const val LABEL_COUNT_LONG = 5

@Composable
private fun ExerciseBGGraph(
    readings: List<GlucoseReading>,
    session: StoredExerciseSession,
    bgLow: Double,
    bgHigh: Double,
    glucoseUnit: GlucoseUnit,
    modifier: Modifier = Modifier
) {
    val dotColor = InRange
    val lowColor = BelowLow
    val highColor = AboveHigh
    val exerciseBandColor = ExerciseDefault.copy(alpha = 0.15f)
    val exerciseBorderColor = ExerciseDefault.copy(alpha = 0.5f)
    val thresholdDash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cardColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = cardColor
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val sorted = readings.sortedBy { it.ts }
            if (sorted.size < 2) return@Canvas

            val minTs = sorted.first().ts.toFloat()
            val maxTs = sorted.last().ts.toFloat()
            val tsRange = maxTs - minTs
            if (tsRange <= 0f) return@Canvas

            val yRange = computeYRange(
                sorted.map { it.sgv.toDouble() },
                bgLow, bgHigh
            )
            val yLow = yRange.yMin
            val yHigh = yRange.yMax
            val sgvRange = (yHigh - yLow).toFloat()
            if (sgvRange <= 0f) return@Canvas

            val fontSize = LABEL_FONT_SP * density
            val padLeft = PAD_LEFT_DP * density
            val padTop = PAD_TOP_DP * density
            val padRight = PAD_RIGHT_DP * density
            val padBottom = PAD_BOTTOM_DP * density

            val w = size.width - padLeft - padRight
            val h = size.height - padTop - padBottom

            fun xFor(ts: Long) = padLeft + ((ts - minTs) / tsRange) * w
            fun yFor(sgv: Double) = padTop + ((yHigh.toFloat() - sgv.toFloat()) / sgvRange) * h

            val labelArgb = labelColor.toArgb()

            // Exercise band
            val bandX1 = xFor(session.startTime).coerceIn(padLeft, padLeft + w)
            val bandX2 = xFor(session.endTime).coerceIn(padLeft, padLeft + w)
            drawRect(exerciseBandColor, topLeft = Offset(bandX1, padTop), size = Size(bandX2 - bandX1, h))
            drawLine(exerciseBorderColor, Offset(bandX1, padTop), Offset(bandX1, padTop + h), strokeWidth = 1.5f)
            drawLine(exerciseBorderColor, Offset(bandX2, padTop), Offset(bandX2, padTop + h), strokeWidth = 1.5f)

            // In-range zone + threshold lines
            val zoneLowY = yFor(bgLow).coerceIn(padTop, padTop + h)
            val zoneHighY = yFor(bgHigh).coerceIn(padTop, padTop + h)
            drawRect(InRange.copy(alpha = 0.05f), topLeft = Offset(padLeft, zoneHighY), size = Size(w, zoneLowY - zoneHighY))
            drawLine(
                lowColor.copy(alpha = 0.4f), Offset(padLeft, zoneLowY),
                Offset(padLeft + w, zoneLowY), strokeWidth = 1f, pathEffect = thresholdDash
            )
            drawLine(
                highColor.copy(alpha = 0.4f), Offset(padLeft, zoneHighY),
                Offset(padLeft + w, zoneHighY), strokeWidth = 1f, pathEffect = thresholdDash
            )

            // Y-axis labels
            val yLabels = computeYAxisLabels(yRange, glucoseUnit)
                .map { it.text to yFor(it.mgdl) }
            drawYAxisLabels(yLabels, labelArgb, fontSize, padLeft, padTop, h)

            // X-axis labels
            drawXAxisLabels(sorted.first().ts, sorted.last().ts, labelArgb, fontSize, padLeft, padTop, w, h)

            // BG dots + lines
            var prevX = 0f
            var prevY = 0f
            for ((i, r) in sorted.withIndex()) {
                val x = xFor(r.ts)
                val y = yFor(r.sgv.toDouble())
                val c = when {
                    r.sgv < bgLow -> lowColor
                    r.sgv > bgHigh -> highColor
                    else -> dotColor
                }
                if (i > 0) {
                    drawLine(c.copy(alpha = 0.5f), Offset(prevX, prevY), Offset(x, y), strokeWidth = 1.5f)
                }
                drawCircle(c, radius = 2.5f, center = Offset(x, y))
                prevX = x
                prevY = y
            }
        }
    }
}

private fun DrawScope.drawYAxisLabels(
    labels: List<Pair<String, Float>>,
    colorArgb: Int,
    fontSize: Float,
    padLeft: Float,
    padTop: Float,
    chartHeight: Float
) {
    val paint = android.graphics.Paint().apply {
        color = colorArgb
        textSize = fontSize
        textAlign = android.graphics.Paint.Align.RIGHT
        isAntiAlias = true
    }
    for ((text, y) in labels) {
        val clampedY = y.coerceIn(padTop, padTop + chartHeight)
        drawContext.canvas.nativeCanvas.drawText(
            text,
            padLeft - LABEL_GAP_DP * density,
            clampedY + fontSize / LABEL_BASELINE_FRAC,
            paint
        )
    }
}

@Suppress("LongParameterList") // Drawing function needs layout context
private fun DrawScope.drawXAxisLabels(
    startTs: Long,
    endTs: Long,
    colorArgb: Int,
    fontSize: Float,
    padLeft: Float,
    padTop: Float,
    chartWidth: Float,
    chartHeight: Float
) {
    val paint = android.graphics.Paint().apply {
        color = colorArgb
        textSize = fontSize
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }
    val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val rangeMs = endTs - startTs
    val count = when {
        rangeMs < SHORT_RANGE_MINUTES * MS_PER_MINUTE -> LABEL_COUNT_SHORT
        rangeMs < MEDIUM_RANGE_MINUTES * MS_PER_MINUTE -> LABEL_COUNT_MEDIUM
        else -> LABEL_COUNT_LONG
    }
    val labelY = padTop + chartHeight + fontSize + X_LABEL_GAP_DP * density
    for (i in 0 until count) {
        val frac = i.toFloat() / (count - 1)
        val ts = startTs + (frac * rangeMs).toLong()
        drawContext.canvas.nativeCanvas.drawText(
            timeFmt.format(Date(ts)),
            padLeft + frac * chartWidth,
            labelY,
            paint
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun DetailRowWithTiming(
    label: String,
    value: String,
    timing: String?,
    valueColor: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface
            )
            if (timing != null) {
                Text(
                    text = timing,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}
