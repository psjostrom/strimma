package com.psjostrom.strimma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.TimeConstants
import com.psjostrom.strimma.data.health.ExerciseBGContext
import com.psjostrom.strimma.data.health.ExerciseCategory
import com.psjostrom.strimma.data.health.StoredExerciseSession
import com.psjostrom.strimma.data.health.Trend
import com.psjostrom.strimma.ui.theme.BelowLow
import com.psjostrom.strimma.ui.theme.LightTintDanger
import com.psjostrom.strimma.ui.theme.TintDanger
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MS_PER_MINUTE = TimeConstants.MS_PER_MINUTE_L
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
    glucoseUnit: GlucoseUnit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 48.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Header
                val category = ExerciseCategory.fromHCType(session.type)
                val typeName = stringResource(category.labelRes)
                val durationMin = ((session.endTime - session.startTime) / MS_PER_MINUTE).toInt()
                val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                val timeRange = "${timeFmt.format(Date(session.startTime))}\u2013${timeFmt.format(Date(session.endTime))}"
                val header = "${category.emoji} $typeName \u00B7 $durationMin min \u00B7 $timeRange"
                val profileName = bgContext?.let { ctx ->
                    val profile = com.psjostrom.strimma.data.health.CategoryStatsCalculator
                        .resolveProfile(session, ctx, null)
                    profile.displayName
                } ?: category.defaultMetabolicProfile.displayName

                Text(
                    text = header,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$profileName \u2014 ${stringResource(R.string.exercise_metabolic_profile_hint)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (bgContext == null) {
                    Text(
                        text = stringResource(R.string.exercise_detail_no_data),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 24.dp).align(Alignment.CenterHorizontally)
                    )
                } else {
                    val noValue = stringResource(R.string.exercise_detail_no_value)

                    // Before section
                    SectionHeader(stringResource(R.string.exercise_detail_before))
                    Spacer(Modifier.height(8.dp))
                    val trendArrow = when (bgContext.entryTrend) {
                        Trend.RISING -> "\u2197"
                        Trend.FALLING -> "\u2198"
                        Trend.STABLE -> "\u2192"
                        null -> ""
                    }
                    val entryText = bgContext.entryBG?.let { "${glucoseUnit.format(it)} $trendArrow" } ?: noValue
                    DetailRow(stringResource(R.string.exercise_detail_entry_bg), entryText)

                    Spacer(Modifier.height(16.dp))

                    // During section
                    SectionHeader(stringResource(R.string.exercise_detail_during))
                    Spacer(Modifier.height(8.dp))
                    DetailRow(
                        stringResource(R.string.exercise_detail_min_bg),
                        bgContext.minBG?.let { glucoseUnit.format(it) } ?: noValue
                    )
                    bgContext.avgHR?.let { DetailRow(stringResource(R.string.exercise_detail_avg_hr), "$it bpm") }
                    bgContext.maxHR?.let { DetailRow(stringResource(R.string.exercise_detail_max_hr), "$it bpm") }
                    bgContext.totalSteps?.let { DetailRow(stringResource(R.string.exercise_detail_steps), "$it") }
                    bgContext.activeCalories?.let { DetailRow(stringResource(R.string.exercise_detail_calories), "%.0f kcal".format(it)) }

                    Spacer(Modifier.height(16.dp))

                    // After section
                    SectionHeader(stringResource(R.string.exercise_detail_after))
                    Spacer(Modifier.height(8.dp))

                    val isHypo = bgContext.postExerciseHypo

                    // Lowest BG with time inline
                    val lowestValue = buildString {
                        append(bgContext.lowestBG?.let { glucoseUnit.format(it) } ?: noValue)
                        bgContext.lowestBGTime?.let { lowestTime ->
                            val minutesAfter = (lowestTime.toEpochMilli() - session.endTime) / MS_PER_MINUTE
                            if (minutesAfter > 0) append(" \u00B7 after ${formatDuration(minutesAfter)}")
                        }
                    }
                    DetailRow(
                        stringResource(R.string.exercise_detail_lowest_bg),
                        lowestValue,
                        valueColor = if (isHypo) BelowLow else null
                    )

                    // Highest BG with time inline
                    bgContext.highestBG?.let { highest ->
                        val highestValue = buildString {
                            append(glucoseUnit.format(highest))
                            bgContext.highestBGTime?.let { highestTime ->
                                val minutesAfter = (highestTime.toEpochMilli() - session.endTime) / MS_PER_MINUTE
                                if (minutesAfter > 0) append(" \u00B7 after ${formatDuration(minutesAfter)}")
                            }
                        }
                        DetailRow(
                            stringResource(R.string.exercise_detail_highest_bg),
                            highestValue
                        )
                    }

                    // Total drop: entry BG → overall lowest (during or post)
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

                    // Post-exercise hypo flag
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
private fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color? = null
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
