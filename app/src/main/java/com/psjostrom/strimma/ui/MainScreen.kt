package com.psjostrom.strimma.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.data.Direction
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.graph.CRITICAL_HIGH
import com.psjostrom.strimma.graph.CRITICAL_LOW
import com.psjostrom.strimma.graph.PredictionComputer
import com.psjostrom.strimma.graph.ThresholdCrossing
import com.psjostrom.strimma.graph.CrossingType
import com.psjostrom.strimma.graph.computeYRange
import com.psjostrom.strimma.network.FollowerStatus
import com.psjostrom.strimma.ui.theme.*
import kotlinx.coroutines.delay

private const val MINIMAP_WINDOW_MS = 24L * 3600_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    latestReading: GlucoseReading?,
    readings: List<GlucoseReading>,
    bgLow: Float,
    bgHigh: Float,
    graphWindowHours: Int,
    glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
    followerStatus: FollowerStatus = FollowerStatus.Idle,
    onSettingsClick: () -> Unit,
    onStatsClick: () -> Unit = {}
) {
    val mainWindowMs = graphWindowHours * 3600_000L

    var viewportEnd by remember { mutableLongStateOf(System.currentTimeMillis() + 15 * 60_000L) }
    var zoomScale by remember { mutableFloatStateOf(1f) }

    // Auto-track "now" + 15 min of prediction space when viewport is near current time
    val predictionMs = 15 * 60_000L
    LaunchedEffect(readings) {
        val now = System.currentTimeMillis()
        if (now + predictionMs - viewportEnd < 2 * 60 * 1000) {
            viewportEnd = now + predictionMs
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                actions = {
                    IconButton(onClick = onStatsClick) {
                        Icon(
                            Icons.Outlined.BarChart,
                            contentDescription = "Statistics",
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            val crossing = remember(readings, bgLow, bgHigh) {
                PredictionComputer.compute(readings, 15, bgLow.toDouble(), bgHigh.toDouble())?.crossing
            }
            BgHeader(latestReading, bgLow, bgHigh, glucoseUnit, crossing, followerStatus)

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                GlucoseGraph(
                    readings = readings,
                    bgLow = bgLow.toDouble(),
                    bgHigh = bgHigh.toDouble(),
                    windowMs = mainWindowMs,
                    viewportEnd = viewportEnd,
                    zoomScale = zoomScale,
                    glucoseUnit = glucoseUnit,
                    onViewportChange = { viewportEnd = it },
                    onZoomChange = { zoomScale = it },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Minimap(
                    readings = readings,
                    bgLow = bgLow.toDouble(),
                    bgHigh = bgHigh.toDouble(),
                    mainWindowMs = mainWindowMs,
                    viewportEnd = viewportEnd,
                    zoomScale = zoomScale,
                    onViewportChange = { viewportEnd = it },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun BgHeader(reading: GlucoseReading?, bgLow: Float, bgHigh: Float, glucoseUnit: GlucoseUnit, crossing: ThresholdCrossing? = null, followerStatus: FollowerStatus) {
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.radialGradient(
                    colors = listOf(bgColor.copy(alpha = 0.10f), Color.Transparent),
                    radius = 400f
                )
            )
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = reading?.let { glucoseUnit.format(it.mmol) } ?: "--",
                    color = bgColor,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = " ${direction.arrow}",
                    color = bgColor,
                    fontSize = 40.sp
                )
            }

            if (reading?.deltaMmol != null) {
                Text(
                    text = glucoseUnit.formatDelta(reading.deltaMmol),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp
                )
            }

            if (crossing != null) {
                val crossingColor = when (crossing.type) {
                    CrossingType.LOW -> BelowLow
                    CrossingType.HIGH -> AboveHigh
                }
                val crossingText = when (crossing.type) {
                    CrossingType.LOW -> "Low in ${crossing.minutesUntil} min"
                    CrossingType.HIGH -> "High in ${crossing.minutesUntil} min"
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = crossingText,
                    color = crossingColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = when {
                    minutesAgo < 0 -> "No data"
                    minutesAgo == 0 -> "Just now"
                    else -> "$minutesAgo min ago"
                },
                color = if (isStale) BelowLow else MaterialTheme.colorScheme.outline,
                fontSize = 13.sp
            )

            if (followerStatus !is FollowerStatus.Idle) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when (followerStatus) {
                        is FollowerStatus.Connecting -> "Following \u00b7 connecting\u2026"
                        is FollowerStatus.Connected -> {
                            val secsAgo = ((System.currentTimeMillis() - followerStatus.lastPollTs) / 1000).toInt()
                            if (secsAgo < 60) "Following \u00b7 ${secsAgo}s ago"
                            else "Following \u00b7 ${secsAgo / 60}m ago"
                        }
                        is FollowerStatus.Disconnected -> {
                            val minsAgo = ((System.currentTimeMillis() - followerStatus.since) / 60_000).toInt()
                            "Following \u00b7 connection lost${if (minsAgo > 0) " ${minsAgo}m" else ""}"
                        }
                        else -> ""
                    },
                    color = when (followerStatus) {
                        is FollowerStatus.Disconnected -> BelowLow
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 12.sp
                )
            }
        }
    }
}

// --- Main graph ---

@Composable
fun GlucoseGraph(
    readings: List<GlucoseReading>,
    bgLow: Double,
    bgHigh: Double,
    windowMs: Long,
    viewportEnd: Long,
    zoomScale: Float,
    glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
    onViewportChange: (Long) -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedReading by remember { mutableStateOf<GlucoseReading?>(null) }
    // Capture theme colors for use inside Canvas
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tooltipBg = MaterialTheme.colorScheme.surfaceVariant
    val tooltipText = MaterialTheme.colorScheme.onSurface
    val tooltipSubtext = MaterialTheme.colorScheme.onSurfaceVariant
    val crosshairColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val predictionColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

    val visibleMs = (windowMs / zoomScale).toLong()
    val visibleStart = viewportEnd - visibleMs
    val sorted = remember(readings, visibleStart, viewportEnd) {
        readings.filter { it.ts in visibleStart..viewportEnd }.sortedBy { it.ts }
    }

    Canvas(
        modifier = modifier
            .pointerInput(glucoseUnit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newZoom = (zoomScale * zoom).coerceIn(1f, 5f)
                    onZoomChange(newZoom)

                    val marginLeft = if (glucoseUnit == GlucoseUnit.MGDL) 70f else 50f
                    val marginRight = 16f
                    val plotWidth = size.width - marginLeft - marginRight
                    val currentVisibleMs = windowMs / newZoom
                    val msPerPx = currentVisibleMs / plotWidth
                    val timeShift = (-pan.x * msPerPx).toLong()
                    val now = System.currentTimeMillis()
                    val maxEnd = now + 15 * 60_000L // prediction space
                    val newEnd = (viewportEnd + timeShift).coerceIn(
                        readings.minOfOrNull { it.ts }?.plus(currentVisibleMs.toLong()) ?: maxEnd,
                        maxEnd
                    )
                    onViewportChange(newEnd)
                }
            }
            .pointerInput(sorted, visibleStart, viewportEnd, glucoseUnit) {
                val mLeft = if (glucoseUnit == GlucoseUnit.MGDL) 70f else 50f
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        selectedReading = findNearestByX(
                            down.position.x, sorted, visibleStart, visibleMs,
                            size.width.toFloat(), mLeft
                        )

                        while (true) {
                            val event = awaitPointerEvent()
                            val pos = event.changes.firstOrNull()?.position ?: break
                            val pressed = event.changes.any { it.pressed }
                            if (!pressed) {
                                selectedReading = null
                                break
                            }
                            selectedReading = findNearestByX(
                                pos.x, sorted, visibleStart, visibleMs,
                                size.width.toFloat(), mLeft
                            )
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
    ) {
        val marginLeft = if (glucoseUnit == GlucoseUnit.MGDL) 70f else 50f
        val marginRight = 16f
        val marginTop = 16f
        val marginBottom = 40f
        val plotWidth = size.width - marginLeft - marginRight
        val plotHeight = size.height - marginTop - marginBottom

        val yr = computeYRange(sorted.map { it.mmol }, bgLow, bgHigh)

        fun xFor(ts: Long): Float =
            marginLeft + ((ts - visibleStart).toFloat() / visibleMs) * plotWidth
        fun yFor(mmol: Double): Float =
            marginTop + ((yr.yMax - mmol) / yr.range).toFloat() * plotHeight

        // In-range zone band
        drawRect(
            color = InRangeZone,
            topLeft = Offset(marginLeft, yFor(bgHigh)),
            size = Size(plotWidth, yFor(bgLow) - yFor(bgHigh))
        )

        // Threshold lines
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        val thresholds = listOf(
            CRITICAL_LOW to BelowLow.copy(alpha = 0.5f),
            bgLow to Color(0xAAFFBE76),
            bgHigh to Color(0xAAFFBE76),
            CRITICAL_HIGH to BelowLow.copy(alpha = 0.5f),
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
        for (i in sorted.indices) {
            val r = sorted[i]
            val x = xFor(r.ts)
            val y = yFor(r.mmol)
            val color = dotColor(r.mmol, bgLow, bgHigh)

            if (i < sorted.lastIndex) {
                val next = sorted[i + 1]
                drawLine(
                    color = color,
                    start = Offset(x, y),
                    end = Offset(xFor(next.ts), yFor(next.mmol)),
                    strokeWidth = 2f
                )
            }

            val isSelected = selectedReading?.ts == r.ts
            drawCircle(color = color, radius = if (isSelected) 9f else 5f, center = Offset(x, y))
        }

        // Prediction curve (least-squares fit to last 12 min of readings)
        val prediction = PredictionComputer.compute(readings, 15, bgLow, bgHigh)
        if (prediction != null) {
            val predColor = predictionColor
            val predDash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            var prevPx = xFor(prediction.anchorTs)
            var prevPy = yFor(prediction.anchorMmol)
            for (pt in prediction.points) {
                val px = xFor(prediction.anchorTs + pt.minuteOffset * 60_000L)
                val py = yFor(pt.mmol)
                if (px > size.width - marginRight) break
                drawLine(
                    color = predColor,
                    start = Offset(prevPx, prevPy),
                    end = Offset(px, py),
                    strokeWidth = 1.5f,
                    pathEffect = predDash
                )
                drawCircle(color = predColor, radius = 2.5f, center = Offset(px, py))
                prevPx = px
                prevPy = py
            }

        }

        // Scrub crosshair + tooltip
        selectedReading?.let { sel ->
            val pointX = xFor(sel.ts)
            val pointY = yFor(sel.mmol)

            drawLine(
                color = crosshairColor,
                start = Offset(pointX, marginTop),
                end = Offset(pointX, size.height - marginBottom),
                strokeWidth = 1.5f
            )

            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val timeStr = sdf.format(java.util.Date(sel.ts))
            val valueStr = glucoseUnit.format(sel.mmol)
            val direction = try { Direction.valueOf(sel.direction) } catch (_: Exception) { Direction.NONE }
            val deltaStr = sel.deltaMmol?.let { glucoseUnit.format(kotlin.math.abs(it)) }

            val line1 = "$valueStr ${direction.arrow}"
            val line2 = if (deltaStr != null) "$timeStr  $deltaStr" else timeStr

            val tooltipPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = tooltipText.hashCode() or 0xFF000000.toInt()
                textSize = 44f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val subtextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = tooltipSubtext.hashCode() or 0xFF000000.toInt()
                textSize = 36f
            }

            val textWidth = maxOf(tooltipPaint.measureText(line1), subtextPaint.measureText(line2))
            val padH = 28f
            val padV = 20f
            val spacing = 8f
            val tooltipW = textWidth + padH * 2
            val tooltipH = tooltipPaint.fontSpacing + subtextPaint.fontSpacing + spacing + padV * 2
            val cornerR = 20f

            val tx = (pointX - tooltipW / 2).coerceIn(marginLeft, size.width - marginRight - tooltipW)
            var ty = pointY - tooltipH - 24f
            if (ty < marginTop) ty = pointY + 24f

            drawRoundRect(
                color = tooltipBg.copy(alpha = 0.95f),
                topLeft = Offset(tx, ty),
                size = Size(tooltipW, tooltipH),
                cornerRadius = CornerRadius(cornerR)
            )
            drawRoundRect(
                color = crosshairColor,
                topLeft = Offset(tx, ty),
                size = Size(tooltipW, tooltipH),
                cornerRadius = CornerRadius(cornerR),
                style = Stroke(width = 1f)
            )

            drawContext.canvas.nativeCanvas.drawText(
                line1, tx + padH,
                ty + padV + tooltipPaint.fontSpacing - tooltipPaint.descent(),
                tooltipPaint
            )
            drawContext.canvas.nativeCanvas.drawText(
                line2, tx + padH,
                ty + padV + tooltipPaint.fontSpacing + spacing + subtextPaint.fontSpacing - subtextPaint.descent(),
                subtextPaint
            )
        }

        // Axis labels
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = axisColor.hashCode() or 0xFF000000.toInt()
            textSize = 38f
            textAlign = android.graphics.Paint.Align.CENTER
        }

        val intervalMs = if (visibleMs <= 3600_000L) 15 * 60_000L else 30 * 60_000L
        var tickTime = visibleStart - (visibleStart % intervalMs) + intervalMs
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        while (tickTime < viewportEnd) {
            val x = xFor(tickTime)
            if (x > marginLeft && x < size.width - marginRight) {
                drawContext.canvas.nativeCanvas.drawText(
                    sdf.format(java.util.Date(tickTime)), x, size.height - 6f, textPaint
                )
            }
            tickTime += intervalMs
        }

        textPaint.textAlign = android.graphics.Paint.Align.RIGHT
        val yStepMmol = if (yr.range > 10) 2.0 else 1.0
        val yStep = if (glucoseUnit == GlucoseUnit.MGDL) {
            val mgStep = if (yr.range * GlucoseUnit.MGDL_FACTOR > 180) 50.0 else 25.0
            mgStep / GlucoseUnit.MGDL_FACTOR
        } else yStepMmol
        var yLabel = Math.ceil(yr.yMin / yStep) * yStep
        while (yLabel <= yr.yMax) {
            val y = yFor(yLabel)
            if (y > marginTop + 10 && y < size.height - marginBottom - 10) {
                val labelText = if (glucoseUnit == GlucoseUnit.MGDL) {
                    "%.0f".format(yLabel * GlucoseUnit.MGDL_FACTOR)
                } else {
                    "%.0f".format(yLabel)
                }
                drawContext.canvas.nativeCanvas.drawText(
                    labelText, marginLeft - 6f, y + 8f, textPaint
                )
            }
            yLabel += yStep
        }
    }
}

// --- Minimap ---

@Composable
fun Minimap(
    readings: List<GlucoseReading>,
    bgLow: Double,
    bgHigh: Double,
    mainWindowMs: Long,
    viewportEnd: Long,
    zoomScale: Float,
    onViewportChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val dimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val vpBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val miniAxisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val now = System.currentTimeMillis()
    val minimapStart = now - MINIMAP_WINDOW_MS
    val sorted = remember(readings, minimapStart) {
        readings.filter { it.ts >= minimapStart }.sortedBy { it.ts }
    }

    Canvas(
        modifier = modifier
            .pointerInput(mainWindowMs, zoomScale) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val visibleMs = (mainWindowMs / zoomScale).toLong()
                        val now0 = System.currentTimeMillis()
                        val start0 = now0 - MINIMAP_WINDOW_MS
                        val time = start0 + ((down.position.x / size.width) * MINIMAP_WINDOW_MS).toLong()
                        onViewportChange((time + visibleMs / 2).coerceIn(start0 + visibleMs, now0))

                        while (true) {
                            val event = awaitPointerEvent()
                            val pos = event.changes.firstOrNull()?.position ?: break
                            if (!event.changes.any { it.pressed }) break
                            val nowD = System.currentTimeMillis()
                            val startD = nowD - MINIMAP_WINDOW_MS
                            val dragTime = startD + ((pos.x.coerceIn(0f, size.width.toFloat()) / size.width) * MINIMAP_WINDOW_MS).toLong()
                            onViewportChange((dragTime + visibleMs / 2).coerceIn(startD + visibleMs, nowD))
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height

        val yr = computeYRange(sorted.map { it.mmol }, bgLow, bgHigh)

        fun xFor(ts: Long): Float = ((ts - minimapStart).toFloat() / MINIMAP_WINDOW_MS) * w
        fun yFor(mmol: Double): Float = ((yr.yMax - mmol) / yr.range).toFloat() * h

        // Threshold lines (subtle)
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
        drawLine(color = Color(0x40FFBE76), start = Offset(0f, yFor(bgLow)), end = Offset(w, yFor(bgLow)), pathEffect = dashEffect, strokeWidth = 1f)
        drawLine(color = Color(0x40FFBE76), start = Offset(0f, yFor(bgHigh)), end = Offset(w, yFor(bgHigh)), pathEffect = dashEffect, strokeWidth = 1f)

        // Data points
        for (r in sorted) {
            val x = xFor(r.ts)
            val y = yFor(r.mmol)
            drawCircle(color = dotColor(r.mmol, bgLow, bgHigh), radius = 2.5f, center = Offset(x, y))
        }

        // Viewport rectangle
        val visibleMs = (mainWindowMs / zoomScale).toLong()
        val vpStart = viewportEnd - visibleMs
        val vpX1 = xFor(vpStart).coerceIn(0f, w)
        val vpX2 = xFor(viewportEnd).coerceIn(0f, w)

        // Dim areas outside viewport
        drawRect(color = dimColor, topLeft = Offset.Zero, size = Size(vpX1, h))
        drawRect(color = dimColor, topLeft = Offset(vpX2, 0f), size = Size(w - vpX2, h))

        // Viewport border
        drawRect(
            color = vpBorderColor,
            topLeft = Offset(vpX1, 0f),
            size = Size(vpX2 - vpX1, h),
            style = Stroke(width = 2f)
        )

        // Time labels
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = miniAxisColor.hashCode() or 0xFF000000.toInt()
            textSize = 28f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val intervalMs = 3 * 3600_000L
        var tickTime = minimapStart - (minimapStart % intervalMs) + intervalMs
        while (tickTime < now) {
            val x = xFor(tickTime)
            if (x > 20f && x < w - 20f) {
                drawContext.canvas.nativeCanvas.drawText(
                    sdf.format(java.util.Date(tickTime)), x, h - 4f, textPaint
                )
            }
            tickTime += intervalMs
        }
    }
}

// --- Helpers ---

private fun dotColor(mmol: Double, bgLow: Double, bgHigh: Double): Color = when {
    mmol <= CRITICAL_LOW -> BelowLow
    mmol < bgLow -> BelowLow
    mmol >= CRITICAL_HIGH -> BelowLow
    mmol > bgHigh -> AboveHigh
    else -> InRange
}

private fun findNearestByX(
    fingerX: Float,
    sorted: List<GlucoseReading>,
    visibleStart: Long,
    visibleMs: Long,
    canvasWidth: Float,
    marginLeft: Float = 50f
): GlucoseReading? {
    if (sorted.isEmpty()) return null
    val marginRight = 16f
    val plotWidth = canvasWidth - marginLeft - marginRight

    fun xFor(ts: Long): Float =
        marginLeft + ((ts - visibleStart).toFloat() / visibleMs) * plotWidth

    var closest: GlucoseReading? = null
    var closestDist = Float.MAX_VALUE
    for (r in sorted) {
        val dist = kotlin.math.abs(xFor(r.ts) - fingerX)
        if (dist < closestDist) {
            closestDist = dist
            closest = r
        }
    }
    return closest
}
