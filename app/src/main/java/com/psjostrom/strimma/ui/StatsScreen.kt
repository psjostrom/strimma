package com.psjostrom.strimma.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.AgpCalculator
import com.psjostrom.strimma.data.AgpResult
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseStats
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.HbA1cUnit
import com.psjostrom.strimma.data.StatsCalculator
import com.psjostrom.strimma.data.Treatment
import com.psjostrom.strimma.data.meal.MealAnalyzer
import com.psjostrom.strimma.data.meal.MealTimeSlotConfig
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.BelowLow
import com.psjostrom.strimma.ui.theme.InRange
import com.psjostrom.strimma.ui.theme.VeryHigh
import com.psjostrom.strimma.ui.theme.VeryLow
import kotlinx.coroutines.launch

private const val HOURS_24 = 24
private const val HOURS_7_DAYS = 168
private const val HOURS_14_DAYS = AgpCalculator.AGP_DAYS * 24
private const val HOURS_30_DAYS = 720

private const val TAB_METRICS = 0
private const val TAB_AGP = 1
private const val TAB_MEALS = 2

@Composable
private fun getPeriods() = listOf(
    HOURS_24 to stringResource(R.string.stats_period_24h),
    HOURS_7_DAYS to stringResource(R.string.stats_period_7d),
    HOURS_14_DAYS to stringResource(R.string.stats_period_14d),
    HOURS_30_DAYS to stringResource(R.string.stats_period_30d)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    bgLow: Float,
    bgHigh: Float,
    glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
    hbA1cUnit: HbA1cUnit = HbA1cUnit.MMOL_MOL,
    onLoadReadings: suspend (Int) -> List<GlucoseReading>,
    onLoadCarbTreatments: suspend (Long, Long) -> List<Treatment>,
    onLoadAllTreatments: suspend (Long) -> List<Treatment>,
    treatmentsSyncEnabled: Boolean = false,
    nightscoutConfigured: Boolean = false,
    tauMinutes: Double,
    mealAnalyzer: MealAnalyzer,
    mealTimeSlotConfig: MealTimeSlotConfig,
    onExportCsv: suspend (Int) -> String,
    onBack: (() -> Unit)? = null
) {
    val bg = MaterialTheme.colorScheme.background
    val onBg = MaterialTheme.colorScheme.onBackground

    var selectedTab by remember { mutableIntStateOf(TAB_METRICS) }
    var selectedPeriod by remember { mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val periods = getPeriods()
    val exportChooserTitle = stringResource(R.string.stats_export_chooser)

    val stats by produceState<GlucoseStats?>(null, selectedPeriod, bgLow, bgHigh) {
        val (hours, label) = periods[selectedPeriod]
        val readings = onLoadReadings(hours)
        value = StatsCalculator.compute(readings, bgLow.toDouble(), bgHigh.toDouble(), label)
    }

    val agpResult by produceState<AgpResult?>(null) {
        val readings = onLoadReadings(HOURS_14_DAYS)
        value = AgpCalculator.compute(readings)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_content_desc_back))
                        }
                    }
                },
                actions = {
                    if (selectedTab == TAB_METRICS) {
                        IconButton(onClick = {
                            scope.launch {
                                val (hours, _) = periods[selectedPeriod]
                                val csv = onExportCsv(hours)
                                shareCsv(context, csv, exportChooserTitle)
                            }
                        }) {
                            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.common_content_desc_export))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bg,
                    titleContentColor = onBg
                )
            )
        },
        containerColor = bg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Tab selector
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selectedTab == TAB_METRICS,
                    onClick = { selectedTab = TAB_METRICS },
                    shape = SegmentedButtonDefaults.itemShape(0, 3)
                ) {
                    Text(stringResource(R.string.stats_tab_metrics))
                }
                SegmentedButton(
                    selected = selectedTab == TAB_AGP,
                    onClick = { selectedTab = TAB_AGP },
                    shape = SegmentedButtonDefaults.itemShape(1, 3)
                ) {
                    Text(stringResource(R.string.stats_tab_agp))
                }
                SegmentedButton(
                    selected = selectedTab == TAB_MEALS,
                    onClick = { selectedTab = TAB_MEALS },
                    shape = SegmentedButtonDefaults.itemShape(2, 3)
                ) {
                    Text(stringResource(R.string.stats_tab_meals))
                }
            }

            when (selectedTab) {
                TAB_METRICS -> MetricsTab(
                    stats = stats,
                    periods = periods,
                    selectedPeriod = selectedPeriod,
                    onPeriodChange = { selectedPeriod = it },
                    bgLow = bgLow,
                    bgHigh = bgHigh,
                    glucoseUnit = glucoseUnit,
                    hbA1cUnit = hbA1cUnit
                )
                TAB_AGP -> AgpTab(
                    agpResult = agpResult,
                    glucoseUnit = glucoseUnit,
                    hbA1cUnit = hbA1cUnit
                )
                TAB_MEALS -> MealStatsTab(
                    onLoadReadings = onLoadReadings,
                    onLoadCarbTreatments = onLoadCarbTreatments,
                    onLoadAllTreatments = onLoadAllTreatments,
                    treatmentsSyncEnabled = treatmentsSyncEnabled,
                    nightscoutConfigured = nightscoutConfigured,
                    periods = periods,
                    selectedPeriod = selectedPeriod,
                    onPeriodChange = { selectedPeriod = it },
                    bgLow = bgLow,
                    bgHigh = bgHigh,
                    glucoseUnit = glucoseUnit,
                    tauMinutes = tauMinutes,
                    analyzer = mealAnalyzer,
                    timeSlotConfig = mealTimeSlotConfig
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MetricsTab(
    stats: GlucoseStats?,
    periods: List<Pair<Int, String>>,
    selectedPeriod: Int,
    onPeriodChange: (Int) -> Unit,
    bgLow: Float,
    bgHigh: Float,
    glucoseUnit: GlucoseUnit,
    hbA1cUnit: HbA1cUnit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val surfVar = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline

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

    val s = stats
    if (s == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.common_no_data), color = onSurfaceVar)
        }
    } else {
        // TIR card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = surfVar
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.stats_time_in_range),
                    color = onBg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(12.dp))

                // TIR bar
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                ) {
                    val r = 16f
                    val w = size.width
                    val h = size.height
                    val belowW = (s.belowPercent / 100 * w).toFloat()
                    val inRangeW = (s.tirPercent / 100 * w).toFloat()
                    val aboveW = w - belowW - inRangeW

                    if (belowW > 0) {
                        drawRoundRect(
                            color = BelowLow,
                            topLeft = Offset.Zero,
                            size = Size(belowW + r, h),
                            cornerRadius = CornerRadius(r)
                        )
                    }
                    if (inRangeW > 0) {
                        drawRect(
                            color = InRange,
                            topLeft = Offset(belowW, 0f),
                            size = Size(inRangeW, h)
                        )
                    }
                    if (aboveW > 0) {
                        drawRoundRect(
                            color = AboveHigh,
                            topLeft = Offset(belowW + inRangeW - r, 0f),
                            size = Size(aboveW + r, h),
                            cornerRadius = CornerRadius(r)
                        )
                    }
                    if (belowW > 0 && aboveW > 0 && inRangeW > 0) {
                        drawRect(
                            color = InRange,
                            topLeft = Offset(belowW, 0f),
                            size = Size(inRangeW, h)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TirLabel("%.0f%%".format(s.belowPercent), stringResource(R.string.stats_low), BelowLow, onSurfaceVar)
                    TirLabel("%.0f%%".format(s.tirPercent), stringResource(R.string.stats_in_range), InRange, onSurfaceVar)
                    TirLabel("%.0f%%".format(s.abovePercent), stringResource(R.string.stats_high), AboveHigh, onSurfaceVar)
                }
            }
        }

        // Metrics card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = surfVar
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatRow(stringResource(R.string.stats_average), glucoseUnit.formatWithUnit(s.averageMgdl), onBg, onSurfaceVar)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StatRow(stringResource(R.string.stats_gmi), hbA1cUnit.format(s.gmi), onBg, onSurfaceVar)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StatRow(stringResource(R.string.stats_cv), "%.1f%%".format(s.cv), onBg, onSurfaceVar)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StatRow(stringResource(R.string.stats_std_dev), glucoseUnit.formatWithUnit(s.stdDevMgdl), onBg, onSurfaceVar)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StatRow(stringResource(R.string.stats_readings), "%,d".format(s.count), onBg, onSurfaceVar)
            }
        }

        // User thresholds note
        Text(
            stringResource(
                R.string.stats_user_thresholds,
                glucoseUnit.formatThreshold(bgLow),
                glucoseUnit.formatThreshold(bgHigh),
                glucoseUnit.label
            ),
            color = outline,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun AgpTab(
    agpResult: AgpResult?,
    glucoseUnit: GlucoseUnit,
    hbA1cUnit: HbA1cUnit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val surfVar = MaterialTheme.colorScheme.surfaceVariant

    val result = agpResult
    if (result == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.agp_no_data), color = onSurfaceVar)
        }
        return
    }

    val m = result.metrics

    // Info box
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = surfVar
    ) {
        Text(
            stringResource(R.string.agp_info),
            color = onSurfaceVar,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(16.dp)
        )
    }

    // AGP Chart
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = surfVar
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.agp_title),
                color = onBg,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            AgpChart(
                buckets = result.buckets,
                glucoseUnit = glucoseUnit
            )
        }
    }

    // 5-tier TIR card
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = surfVar
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.stats_time_in_range),
                color = onBg,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(12.dp))

            FiveTierTirBar(m.veryLowPercent, m.lowPercent, m.inRangePercent, m.highPercent, m.veryHighPercent)

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TirLabel("%.0f%%".format(m.veryLowPercent), stringResource(R.string.agp_very_low), VeryLow, onSurfaceVar)
                TirLabel("%.0f%%".format(m.lowPercent), stringResource(R.string.agp_low), BelowLow, onSurfaceVar)
                TirLabel("%.0f%%".format(m.inRangePercent), stringResource(R.string.agp_in_range), InRange, onSurfaceVar)
                TirLabel("%.0f%%".format(m.highPercent), stringResource(R.string.agp_high), AboveHigh, onSurfaceVar)
                TirLabel("%.0f%%".format(m.veryHighPercent), stringResource(R.string.agp_very_high), VeryHigh, onSurfaceVar)
            }
        }
    }

    // Metrics card
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = surfVar
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatRow(stringResource(R.string.stats_average), glucoseUnit.formatWithUnit(m.averageMgdl), onBg, onSurfaceVar)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StatRow(stringResource(R.string.stats_gmi), hbA1cUnit.format(m.gmi), onBg, onSurfaceVar)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StatRow(stringResource(R.string.stats_cv), "%.1f%%".format(m.cv), onBg, onSurfaceVar)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StatRow(stringResource(R.string.agp_sensor_active), "%.0f%%".format(m.sensorActivePercent), onBg, onSurfaceVar)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StatRow(stringResource(R.string.stats_readings), "%,d".format(m.count), onBg, onSurfaceVar)
        }
    }

    // ADA thresholds + period footer
    Text(
        stringResource(
            R.string.agp_thresholds,
            glucoseUnit.format(AgpCalculator.VERY_LOW),
            glucoseUnit.format(AgpCalculator.LOW),
            glucoseUnit.format(AgpCalculator.HIGH),
            glucoseUnit.format(AgpCalculator.VERY_HIGH),
            glucoseUnit.label
        ),
        color = MaterialTheme.colorScheme.outline,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun FiveTierTirBar(
    veryLowPct: Double,
    lowPct: Double,
    inRangePct: Double,
    highPct: Double,
    veryHighPct: Double
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
    ) {
        val r = 16f
        val w = size.width
        val h = size.height
        val total = veryLowPct + lowPct + inRangePct + highPct + veryHighPct
        if (total <= 0) return@Canvas

        val widths = listOf(veryLowPct, lowPct, inRangePct, highPct, veryHighPct)
            .map { (it / total * w).toFloat() }
        val colors = listOf(VeryLow, BelowLow, InRange, AboveHigh, VeryHigh)

        var x = 0f
        val firstNonZero = widths.indexOfFirst { it > 0 }
        val lastNonZero = widths.indexOfLast { it > 0 }

        widths.forEachIndexed { i, segW ->
            if (segW <= 0) return@forEachIndexed
            when (i) {
                firstNonZero -> {
                    drawRoundRect(
                        color = colors[i],
                        topLeft = Offset(x, 0f),
                        size = Size(segW + r, h),
                        cornerRadius = CornerRadius(r)
                    )
                }
                lastNonZero -> {
                    drawRoundRect(
                        color = colors[i],
                        topLeft = Offset(x - r, 0f),
                        size = Size(segW + r, h),
                        cornerRadius = CornerRadius(r)
                    )
                }
                else -> {
                    drawRect(
                        color = colors[i],
                        topLeft = Offset(x, 0f),
                        size = Size(segW, h)
                    )
                }
            }
            x += segW
        }
    }
}

@Composable
private fun TirLabel(
    percent: String,
    label: String,
    accentColor: androidx.compose.ui.graphics.Color,
    mutedColor: androidx.compose.ui.graphics.Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(percent, color = accentColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = mutedColor, fontSize = 12.sp)
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    labelColor: androidx.compose.ui.graphics.Color,
    valueColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = labelColor, fontSize = 14.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
