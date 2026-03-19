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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseStats
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.StatsCalculator
import com.psjostrom.strimma.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

private val PERIODS = listOf(24 to "24h", 168 to "7d", 336 to "14d", 720 to "30d")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    bgLow: Float,
    bgHigh: Float,
    glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
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
    var stats by remember { mutableStateOf<GlucoseStats?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(selectedPeriod) {
        val (hours, label) = PERIODS[selectedPeriod]
        val readings = onLoadReadings(hours)
        stats = StatsCalculator.compute(readings, bgLow.toDouble(), bgHigh.toDouble(), label)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val (hours, _) = PERIODS[selectedPeriod]
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
                                "Export Readings"
                            ))
                        }
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = "Export")
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
                PERIODS.forEachIndexed { index, (_, label) ->
                    SegmentedButton(
                        selected = selectedPeriod == index,
                        onClick = { selectedPeriod = index },
                        shape = SegmentedButtonDefaults.itemShape(index, PERIODS.size)
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
                    Text("No data", color = onSurfaceVar)
                }
            } else {
                // TIR card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = surfVar
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Time in Range",
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
                            TirLabel("%.0f%%".format(s.belowPercent), "Low", BelowLow, onSurfaceVar)
                            TirLabel("%.0f%%".format(s.tirPercent), "In Range", InRange, onSurfaceVar)
                            TirLabel("%.0f%%".format(s.abovePercent), "High", AboveHigh, onSurfaceVar)
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
                        StatRow("Average", glucoseUnit.formatWithUnit(s.averageMmol), onBg, onSurfaceVar)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        StatRow("GMI (eHbA1c)", "%.1f%%".format(s.gmi), onBg, onSurfaceVar)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        StatRow("CV", "%.1f%%".format(s.cv), onBg, onSurfaceVar)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        StatRow("Std Dev", glucoseUnit.formatWithUnit(s.stdDevMmol), onBg, onSurfaceVar)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        StatRow("Readings", "%,d".format(s.count), onBg, onSurfaceVar)
                    }
                }

                // Targets info
                Text(
                    "Range: ${glucoseUnit.formatThreshold(bgLow)} – ${glucoseUnit.formatThreshold(bgHigh)} ${glucoseUnit.label}",
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
