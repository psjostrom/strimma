package com.psjostrom.strimma.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.Treatment
import com.psjostrom.strimma.data.meal.*
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.BelowLow
import com.psjostrom.strimma.ui.theme.InRange
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

private val analyzer = MealAnalyzer()

private fun MealPostprandialResult.slot(): MealTimeSlot =
    MealTimeSlot.fromTimestamp(mealTime, ZoneId.systemDefault())

private fun MealPostprandialResult.carbSize(): CarbSizeBucket =
    CarbSizeBucket.fromGrams(carbGrams)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealStatsTab(
    onLoadReadings: suspend (Int) -> List<GlucoseReading>,
    onLoadCarbTreatments: suspend (Long, Long) -> List<Treatment>,
    onLoadAllTreatments: suspend (Long) -> List<Treatment>,
    treatmentsSyncEnabled: Boolean,
    periods: List<Pair<Int, String>>,
    selectedPeriod: Int,
    onPeriodChange: (Int) -> Unit,
    bgLow: Float,
    bgHigh: Float,
    glucoseUnit: GlucoseUnit,
    tauMinutes: Double
) {
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val surfVar = MaterialTheme.colorScheme.surfaceVariant

    if (!treatmentsSyncEnabled) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.stats_meals_treatments_disabled),
                color = onSurfaceVar,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        return
    }

    val onBg = MaterialTheme.colorScheme.onBackground

    var selectedSlot by remember { mutableStateOf<MealTimeSlot?>(null) }

    val results by produceState<List<MealPostprandialResult>>(emptyList(), selectedPeriod) {
        val (hours, _) = periods[selectedPeriod]
        val now = System.currentTimeMillis()
        val start = now - hours * 3600_000L
        val readings = onLoadReadings(hours)
        val carbTreatments = onLoadCarbTreatments(start, now)
        val allTreatments = onLoadAllTreatments(start)
        value = carbTreatments.mapIndexedNotNull { i, meal ->
            val nextMealTime = carbTreatments.getOrNull(i + 1)?.createdAt
            analyzer.analyze(meal, readings, bgLow.toDouble(), bgHigh.toDouble(),
                nextMealTime, allTreatments, tauMinutes)
        }
    }

    val filteredResults = if (selectedSlot != null) {
        results.filter { it.slot() == selectedSlot }
    } else results

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Period selector
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            periods.forEachIndexed { index, (_, label) ->
                SegmentedButton(
                    selected = selectedPeriod == index,
                    onClick = { onPeriodChange(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, periods.size)
                ) {
                    Text(label)
                }
            }
        }

        // Time slot filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedSlot == null,
                onClick = { selectedSlot = null },
                label = { Text("All", maxLines = 1) },
                modifier = Modifier.weight(1f)
            )
            MealTimeSlot.entries.forEach { slot ->
                FilterChip(
                    selected = selectedSlot == slot,
                    onClick = { selectedSlot = if (selectedSlot == slot) null else slot },
                    label = { Text(slot.label, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (filteredResults.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No meals found", color = onSurfaceVar)
            }
        } else {
            // Aggregate header
            MealAggregateHeader(filteredResults, bgLow, bgHigh, glucoseUnit)

            // Meal cards
            filteredResults.forEach { result ->
                MealCard(result, bgLow, bgHigh, glucoseUnit)
            }
        }
    }
}

@Composable
private fun MealAggregateHeader(
    results: List<MealPostprandialResult>,
    bgLow: Float,
    bgHigh: Float,
    glucoseUnit: GlucoseUnit
) {
    val surfVar = MaterialTheme.colorScheme.surfaceVariant
    val onBg = MaterialTheme.colorScheme.onBackground
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant

    val avgTir = results.map { it.tirPercent }.average()
    val avgExcursion = results.map { it.excursionMgdl }.average()
    val avgRecovery = results.mapNotNull { it.recoveryMinutes }.average()

    val bySize = results.groupBy { it.carbSize() }.mapValues { it.value.size }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = surfVar
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "MEAL SUMMARY",
                color = onSurfaceVar,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%.0f%%".format(avgTir), color = getTirColor(avgTir), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Avg TIR", color = onSurfaceVar, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(glucoseUnit.format(avgExcursion), color = onBg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Avg Peak", color = onSurfaceVar, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%.0f min".format(avgRecovery), color = onBg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Avg Recovery", color = onSurfaceVar, fontSize = 12.sp)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                "BY CARB SIZE",
                color = onSurfaceVar,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CarbSizeBucket.entries.forEach { size ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${bySize[size] ?: 0}",
                            color = onBg,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(size.label, color = onSurfaceVar, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun getTirColor(tir: Double): Color {
    return when {
        tir >= 80 -> Color(0xFF4ADE80)
        tir >= 50 -> AboveHigh
        else -> BelowLow
    }
}

@Composable
private fun MealCard(
    result: MealPostprandialResult,
    bgLow: Float,
    bgHigh: Float,
    glucoseUnit: GlucoseUnit
) {
    var expanded by remember { mutableStateOf(false) }
    val surfVar = MaterialTheme.colorScheme.surfaceVariant
    val onBg = MaterialTheme.colorScheme.onBackground
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val rotationAngle by animateFloatAsState(if (expanded) 180f else 0f, label = "expand")

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = surfVar,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "%.0fg".format(result.carbGrams),
                            color = onBg,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(result.slot().label, color = onSurfaceVar, fontSize = 14.sp)
                        Text(result.carbSize().label, color = onSurfaceVar, fontSize = 12.sp)
                    }
                    Text(
                        formatTime(result.mealTime),
                        color = onSurfaceVar,
                        fontSize = 12.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // TIR pill
                    Surface(
                        shape = RoundedCornerShape(100),
                        color = if (result.tirPercent >= 80) Color(0xFF1A3A2A) else if (result.tirPercent >= 50) Color(0xFF35280E) else Color(0xFF351525)
                    ) {
                        Text(
                            "%.0f%% TIR".format(result.tirPercent),
                            color = getTirColor(result.tirPercent),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.rotate(rotationAngle),
                        tint = onSurfaceVar
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealSparkline(result, bgLow, bgHigh, glucoseUnit)

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricLabel("Peak", glucoseUnit.format(result.excursionMgdl), onBg, onSurfaceVar)
                        MetricLabel("Time to Peak", "${result.timeToPeakMinutes} min", onBg, onSurfaceVar)
                        result.recoveryMinutes?.let {
                            MetricLabel("Recovery", "$it min", onBg, onSurfaceVar)
                        }
                    }

                    if (result.iobAtMeal > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            MetricLabel("IOB at Meal", "%.1fU".format(result.iobAtMeal), onBg, onSurfaceVar)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MealSparkline(
    result: MealPostprandialResult,
    bgLow: Float,
    bgHigh: Float,
    glucoseUnit: GlucoseUnit
) {
    val points = result.readings
    if (points.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("No data", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        return
    }

    val minMgdl = points.minOf { it.sgv }.toDouble()
    val maxMgdl = points.maxOf { it.sgv }.toDouble()
    val yMin = min(minMgdl, bgLow.toDouble()) - 10
    val yMax = max(maxMgdl, bgHigh.toDouble()) + 10

    val zoneFill = InRange.copy(alpha = 0.08f)
    val thresholdColor = Color(0xFF6A5F80)
    val excursionFill = AboveHigh.copy(alpha = 0.15f)
    val baselineColor = Color(0xFF6A5F80)
    val curveColor = InRange

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
    ) {
        val w = size.width
        val h = size.height
        val padding = 8f

        val firstTs = points.first().ts
        val lastTs = points.last().ts
        val duration = (lastTs - firstTs).toDouble()
        if (duration <= 0) return@Canvas

        fun xFor(ts: Long): Float {
            val frac = (ts - firstTs) / duration
            return (padding + frac * (w - 2 * padding)).toFloat()
        }

        fun yFor(mgdl: Double): Float {
            val frac = (mgdl - yMin) / (yMax - yMin)
            return (h - padding - frac * (h - 2 * padding)).toFloat()
        }

        // Layer 1: Zone band
        drawRect(
            color = zoneFill,
            topLeft = Offset(padding, yFor(bgHigh.toDouble())),
            size = Size(w - 2 * padding, yFor(bgLow.toDouble()) - yFor(bgHigh.toDouble()))
        )

        // Layer 2: Threshold lines
        drawLine(
            color = thresholdColor,
            start = Offset(padding, yFor(bgLow.toDouble())),
            end = Offset(w - padding, yFor(bgLow.toDouble())),
            strokeWidth = 1f
        )
        drawLine(
            color = thresholdColor,
            start = Offset(padding, yFor(bgHigh.toDouble())),
            end = Offset(w - padding, yFor(bgHigh.toDouble())),
            strokeWidth = 1f
        )

        // Layer 3: Excursion fill (area above baseline)
        val baselineY = yFor(result.baselineMgdl)
        val excursionPath = Path().apply {
            moveTo(xFor(points.first().ts), baselineY)
            points.forEach { p ->
                val x = xFor(p.ts)
                val y = yFor(p.sgv.toDouble())
                lineTo(x, y)
            }
            lineTo(xFor(points.last().ts), baselineY)
            close()
        }
        drawPath(excursionPath, color = excursionFill, style = Fill)

        // Layer 4: Baseline
        drawLine(
            color = baselineColor,
            start = Offset(padding, baselineY),
            end = Offset(w - padding, baselineY),
            strokeWidth = 1f
        )

        // Layer 5: BG curve
        val bgPath = Path().apply {
            points.forEachIndexed { i, p ->
                val x = xFor(p.ts)
                val y = yFor(p.sgv.toDouble())
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(bgPath, color = curveColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
    }
}

@Composable
private fun MetricLabel(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = valueColor, fontSize = 11.sp)
        Text(value, color = labelColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatTime(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ts))
}
