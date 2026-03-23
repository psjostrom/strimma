package com.psjostrom.strimma.ui

import android.content.Intent
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
import androidx.core.content.FileProvider
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseStats
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.HbA1cUnit
import com.psjostrom.strimma.data.StatsCalculator
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.BelowLow
import com.psjostrom.strimma.ui.theme.InRange
import kotlinx.coroutines.launch
import java.io.File

private const val HOURS_24 = 24
private const val HOURS_7_DAYS = 168
private const val HOURS_14_DAYS = 336
private const val HOURS_30_DAYS = 720

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
    onExportCsv: suspend (Int) -> String,
    onBack: () -> Unit
) {
    val bg = MaterialTheme.colorScheme.background
    val onBg = MaterialTheme.colorScheme.onBackground
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val surfVar = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_content_desc_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val (hours, _) = periods[selectedPeriod]
                            val csv = onExportCsv(hours)
                            val file = File(context.cacheDir, "strimma_readings.csv")
                            file.writeText(csv)
                            val uri = FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file
                            )
                            context.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                exportChooserTitle
                            ))
                        }
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.common_content_desc_export))
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

            // Period selector
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                periods.forEachIndexed { index, (_, label) ->
                    SegmentedButton(
                        selected = selectedPeriod == index,
                        onClick = { selectedPeriod = index },
                        shape = SegmentedButtonDefaults.itemShape(index, periods.size)
                    ) {
                        Text(label)
                    }
                }
            }

            val s = stats
            if (s == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
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

                            // Below (red)
                            if (belowW > 0) {
                                drawRoundRect(
                                    color = BelowLow,
                                    topLeft = Offset.Zero,
                                    size = Size(belowW + r, h),
                                    cornerRadius = CornerRadius(r)
                                )
                            }
                            // In range (cyan)
                            if (inRangeW > 0) {
                                drawRect(
                                    color = InRange,
                                    topLeft = Offset(belowW, 0f),
                                    size = Size(inRangeW, h)
                                )
                            }
                            // Above (amber)
                            if (aboveW > 0) {
                                drawRoundRect(
                                    color = AboveHigh,
                                    topLeft = Offset(belowW + inRangeW - r, 0f),
                                    size = Size(aboveW + r, h),
                                    cornerRadius = CornerRadius(r)
                                )
                            }
                            // Redraw in-range over overlapping corners if all 3 segments exist
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

                // Targets info
                Text(
                    stringResource(
                        R.string.stats_range,
                        glucoseUnit.formatThreshold(bgLow),
                        glucoseUnit.formatThreshold(bgHigh),
                        glucoseUnit.label
                    ),
                    color = outline,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
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
