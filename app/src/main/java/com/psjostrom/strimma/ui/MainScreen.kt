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
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 5f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
    ) {
        val now = System.currentTimeMillis()
        val startTime = now - windowMs

        val marginLeft = 40f
        val marginRight = 16f
        val marginTop = 16f
        val marginBottom = 30f
        val plotWidth = (size.width - marginLeft - marginRight) * scale
        val plotHeight = (size.height - marginTop - marginBottom) * scale

        val visible = readings.filter { it.ts >= startTime - (windowMs * (scale - 1)) }
        val allMmol = visible.map { it.mmol }
        val yMin = if (allMmol.isEmpty()) bgLow - 0.5 else minOf(bgLow - 0.5, allMmol.min() - 0.3)
        val yMax = if (allMmol.isEmpty()) bgHigh + 0.5 else maxOf(bgHigh + 0.5, allMmol.max() + 0.3)
        val yRange = yMax - yMin

        fun xFor(ts: Long): Float = marginLeft + ((ts - startTime).toFloat() / windowMs) * plotWidth + offsetX
        fun yFor(mmol: Double): Float = marginTop + ((yMax - mmol) / yRange).toFloat() * plotHeight + offsetY

        // Threshold lines
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        drawLine(
            color = Color(0x80FFFFFF),
            start = Offset(marginLeft, yFor(bgLow)),
            end = Offset(size.width - marginRight, yFor(bgLow)),
            pathEffect = dashEffect
        )
        drawLine(
            color = Color(0x80FFFFFF),
            start = Offset(marginLeft, yFor(bgHigh)),
            end = Offset(size.width - marginRight, yFor(bgHigh)),
            pathEffect = dashEffect
        )

        // Readings
        val sorted = visible.sortedBy { it.ts }
        for (i in sorted.indices) {
            val r = sorted[i]
            val x = xFor(r.ts)
            val y = yFor(r.mmol)
            val color = when {
                r.mmol < bgLow -> BelowLow
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

        // X axis labels
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFB0B0B0.toInt()
            textSize = 24f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val intervalMs = if (windowMs <= 3600_000L) 15 * 60_000L else 30 * 60_000L
        var tickTime = startTime - (startTime % intervalMs) + intervalMs
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        while (tickTime < now) {
            val x = xFor(tickTime)
            if (x > marginLeft && x < size.width - marginRight) {
                drawContext.canvas.nativeCanvas.drawText(
                    sdf.format(java.util.Date(tickTime)), x, size.height - 4f, textPaint
                )
            }
            tickTime += intervalMs
        }

        // Y axis labels
        textPaint.textAlign = android.graphics.Paint.Align.RIGHT
        val yStep = if (yRange > 8) 2.0 else 1.0
        var yLabel = Math.ceil(yMin / yStep) * yStep
        while (yLabel <= yMax) {
            val y = yFor(yLabel)
            if (y > marginTop + 5 && y < size.height - marginBottom - 5) {
                drawContext.canvas.nativeCanvas.drawText(
                    "%.0f".format(yLabel), marginLeft - 4f, y + 6f, textPaint
                )
            }
            yLabel += yStep
        }
    }
}
