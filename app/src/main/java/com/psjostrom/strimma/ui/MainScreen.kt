package com.psjostrom.strimma.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.data.Direction
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    latestReading: GlucoseReading?,
    readings: List<GlucoseReading>,
    bgLow: Float,
    bgHigh: Float,
    graphWindowHours: Int,
    onSettingsClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Strimma") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgDark,
                    titleContentColor = TextPrimary
                )
            )
        },
        containerColor = BgDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Header: time since, BG, arrow, delta
            BgHeader(latestReading, bgLow, bgHigh)

            Spacer(modifier = Modifier.height(16.dp))

            // Interactive glucose graph
            GlucoseGraph(
                readings = readings,
                bgLow = bgLow.toDouble(),
                bgHigh = bgHigh.toDouble(),
                windowMs = graphWindowHours * 3600_000L,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // Debug log (remove in production)
            val debugEntries by DebugLog.entries.collectAsState()
            if (debugEntries.isNotEmpty()) {
                HorizontalDivider(color = TextSecondary.copy(alpha = 0.3f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(top = 4.dp)
                ) {
                    debugEntries.takeLast(8).forEach { entry ->
                        Text(
                            text = entry,
                            color = TextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BgHeader(reading: GlucoseReading?, bgLow: Float, bgHigh: Float) {
    var minutesAgo by remember { mutableIntStateOf(0) }

    LaunchedEffect(reading?.ts) {
        while (true) {
            minutesAgo = if (reading != null) {
                ((System.currentTimeMillis() - reading.ts) / 60_000).toInt()
            } else -1
            delay(60_000)
        }
    }

    val isStale = minutesAgo > 10
    val direction = reading?.let {
        try { Direction.valueOf(it.direction) } catch (_: Exception) { Direction.NONE }
    } ?: Direction.NONE

    val bgColor = when {
        reading == null || isStale -> Stale
        reading.mmol < bgLow -> BelowLow
        reading.mmol > bgHigh -> AboveHigh
        else -> InRange
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Column {
            Text(
                text = when {
                    minutesAgo < 0 -> "No data"
                    minutesAgo == 0 -> "Just now"
                    else -> "$minutesAgo min ago"
                },
                color = if (isStale) BelowLow else TextSecondary,
                fontSize = 14.sp
            )
            if (reading?.deltaMmol != null) {
                val sign = if (reading.deltaMmol >= 0) "+" else ""
                Text(
                    text = "$sign%.1f mmol/l".format(reading.deltaMmol),
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = reading?.let { "%.1f".format(it.mmol) } ?: "--",
                color = bgColor,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = " ${direction.arrow}",
                color = bgColor,
                fontSize = 36.sp
            )
        }
    }
}

@Composable
fun GlucoseGraph(
    readings: List<GlucoseReading>,
    bgLow: Double,
    bgHigh: Double,
    windowMs: Long,
    modifier: Modifier = Modifier
) {
    val criticalLow = 3.0
    val criticalHigh = 13.0

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    if (newScale != scale) {
                        // Zoom relative to gesture centroid
                        offsetX = centroid.x - (centroid.x - offsetX) * (newScale / scale)
                        offsetY = centroid.y - (centroid.y - offsetY) * (newScale / scale)
                        scale = newScale
                    }
                    offsetX += pan.x
                    offsetY += pan.y

                    // Clamp to data bounds
                    val plotW = size.width * scale
                    val plotH = size.height * scale
                    offsetX = offsetX.coerceIn(size.width - plotW, 0f)
                    offsetY = offsetY.coerceIn(size.height - plotH, 0f)
                }
            }
    ) {
        val now = System.currentTimeMillis()
        val startTime = now - windowMs

        val marginLeft = 50f
        val marginRight = 16f
        val marginTop = 16f
        val marginBottom = 40f
        val baseWidth = size.width - marginLeft - marginRight
        val baseHeight = size.height - marginTop - marginBottom

        val allMmol = readings.filter { it.ts >= startTime }.map { it.mmol }
        val yMin = minOf(bgLow - 0.5, criticalLow - 0.3,
            if (allMmol.isEmpty()) bgLow - 0.5 else allMmol.min() - 0.3)
        val yMax = maxOf(bgHigh + 0.5, criticalHigh + 0.3,
            if (allMmol.isEmpty()) bgHigh + 0.5 else allMmol.max() + 0.3)
        val yRange = yMax - yMin

        fun xFor(ts: Long): Float =
            marginLeft + ((ts - startTime).toFloat() / windowMs) * baseWidth * scale + offsetX
        fun yFor(mmol: Double): Float =
            marginTop + ((yMax - mmol) / yRange).toFloat() * baseHeight * scale + offsetY

        // Threshold lines
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        val thresholds = listOf(
            criticalLow to BelowLow.copy(alpha = 0.5f),
            bgLow to Color(0xAAFFB74D),
            bgHigh to Color(0xAAFFB74D),
            criticalHigh to BelowLow.copy(alpha = 0.5f),
        )
        for ((level, color) in thresholds) {
            val y = yFor(level)
            drawLine(
                color = color,
                start = Offset(marginLeft, y),
                end = Offset(size.width - marginRight, y),
                pathEffect = dashEffect,
                strokeWidth = 1.5f
            )
        }

        // Readings
        val sorted = readings.filter { it.ts >= startTime }.sortedBy { it.ts }
        for (i in sorted.indices) {
            val r = sorted[i]
            val x = xFor(r.ts)
            val y = yFor(r.mmol)
            val color = when {
                r.mmol <= criticalLow -> BelowLow
                r.mmol < bgLow -> BelowLow
                r.mmol >= criticalHigh -> BelowLow
                r.mmol > bgHigh -> AboveHigh
                else -> InRange
            }

            if (i < sorted.lastIndex) {
                val next = sorted[i + 1]
                drawLine(
                    color = color,
                    start = Offset(x, y),
                    end = Offset(xFor(next.ts), yFor(next.mmol)),
                    strokeWidth = 2f
                )
            }
            drawCircle(color = color, radius = 5f, center = Offset(x, y))
        }

        // Axis labels
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFB0B0B0.toInt()
            textSize = 32f
            textAlign = android.graphics.Paint.Align.CENTER
        }

        // X axis
        val intervalMs = if (windowMs <= 3600_000L) 15 * 60_000L else 30 * 60_000L
        var tickTime = startTime - (startTime % intervalMs) + intervalMs
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        while (tickTime < now) {
            val x = xFor(tickTime)
            if (x > marginLeft && x < size.width - marginRight) {
                drawContext.canvas.nativeCanvas.drawText(
                    sdf.format(java.util.Date(tickTime)), x, size.height - 6f, textPaint
                )
            }
            tickTime += intervalMs
        }

        // Y axis
        textPaint.textAlign = android.graphics.Paint.Align.RIGHT
        val yStep = if (yRange > 10) 2.0 else 1.0
        var yLabel = Math.ceil(yMin / yStep) * yStep
        while (yLabel <= yMax) {
            val y = yFor(yLabel)
            if (y > marginTop + 10 && y < size.height - marginBottom - 10) {
                drawContext.canvas.nativeCanvas.drawText(
                    "%.0f".format(yLabel), marginLeft - 6f, y + 8f, textPaint
                )
            }
            yLabel += yStep
        }
    }
}
