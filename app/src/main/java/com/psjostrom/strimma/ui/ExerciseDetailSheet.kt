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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
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
                    ExerciseBGGraph(
                        readings = readings,
                        session = session,
                        bgLow = bgLow,
                        bgHigh = bgHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    )
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
                    StatsRow(bgContext, glucoseUnit, noValue)

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
private fun StatsRow(bgContext: ExerciseBGContext, glucoseUnit: GlucoseUnit, noValue: String) {
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
            valueColor = bgContext.minBG?.let { if (it < 70) BelowLow else null }
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

@Suppress("LongMethod") // Canvas drawing with coordinate math
@Composable
private fun ExerciseBGGraph(
    readings: List<GlucoseReading>,
    session: StoredExerciseSession,
    bgLow: Double,
    bgHigh: Double,
    modifier: Modifier = Modifier
) {
    val dotColor = InRange
    val lowColor = BelowLow
    val highColor = AboveHigh
    val exerciseBandColor = ExerciseDefault.copy(alpha = 0.15f)
    val exerciseBorderColor = ExerciseDefault.copy(alpha = 0.5f)
    val thresholdDash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
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

            val pad = 4f
            val w = size.width - 2 * pad
            val h = size.height - 2 * pad

            fun xFor(ts: Long) = pad + ((ts - minTs) / tsRange) * w
            fun yFor(sgv: Double) = pad + ((yHigh.toFloat() - sgv.toFloat()) / sgvRange) * h

            // Exercise band
            val bandX1 = xFor(session.startTime).coerceIn(pad, pad + w)
            val bandX2 = xFor(session.endTime).coerceIn(pad, pad + w)
            drawRect(
                color = exerciseBandColor,
                topLeft = Offset(bandX1, pad),
                size = Size(bandX2 - bandX1, h)
            )
            drawLine(exerciseBorderColor, Offset(bandX1, pad), Offset(bandX1, pad + h), strokeWidth = 1.5f)
            drawLine(exerciseBorderColor, Offset(bandX2, pad), Offset(bandX2, pad + h), strokeWidth = 1.5f)

            // In-range zone
            val zoneLowY = yFor(bgLow).coerceIn(pad, pad + h)
            val zoneHighY = yFor(bgHigh).coerceIn(pad, pad + h)
            drawRect(
                color = Color(0x0C56CCF2),
                topLeft = Offset(pad, zoneHighY),
                size = Size(w, zoneLowY - zoneHighY)
            )

            // Threshold lines
            drawLine(
                color = lowColor.copy(alpha = 0.4f),
                start = Offset(pad, zoneLowY),
                end = Offset(pad + w, zoneLowY),
                strokeWidth = 1f,
                pathEffect = thresholdDash
            )
            drawLine(
                color = highColor.copy(alpha = 0.4f),
                start = Offset(pad, zoneHighY),
                end = Offset(pad + w, zoneHighY),
                strokeWidth = 1f,
                pathEffect = thresholdDash
            )

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
                    drawLine(color = c.copy(alpha = 0.5f), start = Offset(prevX, prevY), end = Offset(x, y), strokeWidth = 1.5f)
                }
                drawCircle(color = c, radius = 2.5f, center = Offset(x, y))
                prevX = x
                prevY = y
            }
        }
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
