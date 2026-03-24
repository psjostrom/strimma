package com.psjostrom.strimma.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.Direction
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.IOBComputer
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.Treatment
import com.psjostrom.strimma.data.health.ExerciseCategory
import com.psjostrom.strimma.data.health.StoredExerciseSession
import com.psjostrom.strimma.graph.CRITICAL_HIGH
import com.psjostrom.strimma.graph.CRITICAL_LOW
import com.psjostrom.strimma.graph.PredictionComputer
import com.psjostrom.strimma.graph.ThresholdCrossing
import com.psjostrom.strimma.graph.CrossingType
import com.psjostrom.strimma.graph.computeYRange
import com.psjostrom.strimma.network.FollowerStatus
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.BelowLow
import com.psjostrom.strimma.ui.theme.BolusBlue
import com.psjostrom.strimma.ui.theme.CarbGreen
import com.psjostrom.strimma.ui.theme.ExerciseDefault
import com.psjostrom.strimma.ui.theme.InRange
import com.psjostrom.strimma.ui.theme.InRangeZone
import com.psjostrom.strimma.ui.theme.Stale
import com.psjostrom.strimma.ui.theme.TintDanger
import com.psjostrom.strimma.ui.theme.TintWarning
import com.psjostrom.strimma.ui.theme.LightTintDanger
import com.psjostrom.strimma.ui.theme.LightTintWarning
import androidx.compose.ui.graphics.Path
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
    predictionMinutes: Int = 15,
    glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
    followerStatus: FollowerStatus = FollowerStatus.Idle,
    treatments: List<Treatment> = emptyList(),
    iob: Double = 0.0,
    iobTauMinutes: Double = 55.0,
    exerciseSessions: List<StoredExerciseSession> = emptyList(),
    onSettingsClick: () -> Unit,
    onStatsClick: () -> Unit = {},
    onExerciseClick: () -> Unit = {}
) {
    val mainWindowMs = graphWindowHours * 3600_000L

    val predictionMs = predictionMinutes * 60_000L
    var viewportEnd by remember { mutableLongStateOf(System.currentTimeMillis() + predictionMs) }
    var zoomScale by remember { mutableFloatStateOf(1f) }

    // Auto-track "now" + prediction space when viewport is near current time
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
                    IconButton(onClick = onExerciseClick) {
                        Icon(
                            Icons.Default.FitnessCenter,
                            contentDescription = stringResource(R.string.settings_exercise),
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = onStatsClick) {
                        Icon(
                            Icons.Outlined.BarChart,
                            contentDescription = stringResource(R.string.common_content_desc_statistics),
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.common_content_desc_settings),
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
            val crossing = remember(readings, bgLow, bgHigh, predictionMinutes) {
                PredictionComputer.compute(readings, predictionMinutes, bgLow.toDouble(), bgHigh.toDouble())?.crossing
            }
            BgHeader(latestReading, bgLow, bgHigh, glucoseUnit, crossing, followerStatus, iob, treatments, iobTauMinutes)

            Spacer(modifier = Modifier.height(16.dp))

            val cardShape = RoundedCornerShape(12.dp)
            val cardBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = cardShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = cardBorder
            ) {
                GlucoseGraph(
                    readings = readings,
                    bgLow = bgLow.toDouble(),
                    bgHigh = bgHigh.toDouble(),
                    windowMs = mainWindowMs,
                    viewportEnd = viewportEnd,
                    zoomScale = zoomScale,
                    predictionMinutes = predictionMinutes,
                    glucoseUnit = glucoseUnit,
                    treatments = treatments,
                    exerciseSessions = exerciseSessions,
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
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = cardBorder
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

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BgHeader(
    reading: GlucoseReading?, bgLow: Float, bgHigh: Float,
    glucoseUnit: GlucoseUnit, crossing: ThresholdCrossing? = null,
    followerStatus: FollowerStatus, iob: Double = 0.0,
    treatments: List<Treatment> = emptyList(),
    iobTauMinutes: Double = 55.0
) {
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
        reading.sgv < bgLow -> BelowLow
        reading.sgv > bgHigh -> AboveHigh
        else -> InRange
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Hero value + direction
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = reading?.let { glucoseUnit.format(it.sgv) } ?: "--",
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

        // Delta + timestamp on one line
        val timeText = when {
            minutesAgo < 0 -> stringResource(R.string.common_no_data)
            minutesAgo == 0 -> stringResource(R.string.main_just_now)
            else -> stringResource(R.string.main_min_ago, minutesAgo)
        }
        val subtitleParts = buildList {
            reading?.delta?.let { add(glucoseUnit.formatDelta(it)) }
            add(timeText)
        }
        Text(
            text = subtitleParts.joinToString(" · "),
            color = if (isStale) BelowLow else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )

        // Prediction warning as pill
        if (crossing != null) {
            val crossingColor = when (crossing.type) {
                CrossingType.LOW -> BelowLow
                CrossingType.HIGH -> AboveHigh
            }
            val crossingText = when (crossing.type) {
                CrossingType.LOW -> stringResource(R.string.main_prediction_low, crossing.minutesUntil)
                CrossingType.HIGH -> stringResource(R.string.main_prediction_high, crossing.minutesUntil)
            }
            Spacer(modifier = Modifier.height(8.dp))
            val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val pillBg = when (crossing.type) {
                CrossingType.LOW -> if (isDark) TintDanger else LightTintDanger
                CrossingType.HIGH -> if (isDark) TintWarning else LightTintWarning
            }
            Surface(
                shape = RoundedCornerShape(100),
                color = pillBg
            ) {
                Text(
                    text = crossingText,
                    color = crossingColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                )
            }
        }

        // IOB pill
        if (iob > 0.0) {
            Spacer(modifier = Modifier.height(8.dp))
            var showIobDetail by remember { mutableStateOf(false) }
            val iobColor = if (iob < 0.3) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            Surface(
                onClick = { showIobDetail = true },
                shape = RoundedCornerShape(100),
                color = InRange.copy(alpha = 0.12f)
            ) {
                Text(
                    text = stringResource(R.string.main_iob_value, iob),
                    color = iobColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                )
            }
            if (showIobDetail) {
                IobDetailDialog(treatments, iobTauMinutes, onDismiss = { showIobDetail = false })
            }
        }

        if (followerStatus !is FollowerStatus.Idle) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when (followerStatus) {
                    is FollowerStatus.Connecting -> stringResource(R.string.main_follower_connecting)
                    is FollowerStatus.Connected -> {
                        val secsAgo = ((System.currentTimeMillis() - followerStatus.lastPollTs) / 1000).toInt()
                        if (secsAgo < 60) stringResource(R.string.main_follower_seconds_ago, secsAgo)
                        else stringResource(R.string.main_follower_minutes_ago, secsAgo / 60)
                    }
                    is FollowerStatus.Disconnected -> {
                        val minsAgo = ((System.currentTimeMillis() - followerStatus.since) / 60_000).toInt()
                        if (minsAgo > 0) stringResource(R.string.main_follower_lost_minutes, minsAgo)
                        else stringResource(R.string.main_follower_lost)
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

@Composable
private fun IobDetailDialog(treatments: List<Treatment>, tauMinutes: Double, onDismiss: () -> Unit) {
    val now = System.currentTimeMillis()
    val cutoff = now - IOBComputer.lookbackMs(tauMinutes)
    val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    // Show treatments with insulin still contributing to IOB
    val insulinTreatments = treatments
        .filter { (it.insulin ?: 0.0) > 0.0 && it.createdAt in cutoff..now }
        .sortedByDescending { it.createdAt }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
        },
        title = { Text(stringResource(R.string.main_iob_breakdown_title)) },
        text = {
            if (insulinTreatments.isEmpty()) {
                Text(stringResource(R.string.main_no_active_treatments))
            } else {
                Column {
                    insulinTreatments.forEach { t ->
                        val dose = t.insulin ?: return@forEach
                        val minutesSince = (now - t.createdAt) / 60_000.0
                        val remainingIob = IOBComputer.iobForTreatment(dose, minutesSince, tauMinutes)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = timeFmt.format(java.util.Date(t.createdAt)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                            val parts = mutableListOf(stringResource(R.string.main_bolus_label, dose))
                            t.carbs?.let { if (it > 0.0) parts.add(stringResource(R.string.main_carb_label, it)) }
                            Text(
                                text = parts.joinToString("  "),
                                fontSize = 14.sp
                            )
                            Text(
                                text = stringResource(R.string.main_bolus_label, remainingIob),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = InRange
                            )
                        }
                    }
                }
            }
        }
    )
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
    predictionMinutes: Int = 15,
    glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
    treatments: List<Treatment> = emptyList(),
    exerciseSessions: List<StoredExerciseSession> = emptyList(),
    onViewportChange: (Long) -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val predictionMs = predictionMinutes * 60_000L
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

    // Stable refs for pointerInput(Unit) — avoids coroutine restart on every recomposition
    val currentZoom by rememberUpdatedState(zoomScale)
    val currentViewportEnd by rememberUpdatedState(viewportEnd)
    val currentSorted by rememberUpdatedState(sorted)
    val currentVisibleStart by rememberUpdatedState(visibleStart)
    val currentVisibleMs by rememberUpdatedState(visibleMs)
    val currentReadings by rememberUpdatedState(readings)
    val currentBgLow by rememberUpdatedState(bgLow)
    val currentBgHigh by rememberUpdatedState(bgHigh)
    val currentGlucoseUnit by rememberUpdatedState(glucoseUnit)
    val currentWindowMs by rememberUpdatedState(windowMs)
    val currentPredictionMs by rememberUpdatedState(predictionMs)
    // Resolve format strings in Composable scope for use inside Canvas drawText
    val bolusLabelFmt = stringResource(R.string.main_bolus_label)
    val carbLabelFmt = stringResource(R.string.main_carb_label)

    val treatmentLabelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    // Resolve exercise category labels in Composable scope (stringResource unavailable in Canvas)
    val exerciseCategoryLabels = ExerciseCategory.entries.associateWith { stringResource(it.labelRes) }

    val exerciseLabelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 24f
            textAlign = android.graphics.Paint.Align.LEFT
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            color = ExerciseDefault.copy(alpha = 0.7f).toArgb()
        }
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                val mRight = GRAPH_MARGIN_RIGHT
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val mLeft = if (currentGlucoseUnit == GlucoseUnit.MGDL) 70f else 50f
                    val viewport = GraphViewport(
                        currentVisibleStart, currentVisibleMs,
                        currentBgLow, currentBgHigh,
                        size.width.toFloat(), size.height.toFloat(), mLeft
                    )
                    // Check if finger landed on a dot
                    val hit = findNearestDot(down.position, currentSorted, viewport)
                    var isScrubbing = hit != null
                    if (isScrubbing) {
                        selectedReading = hit
                        down.consume()
                    }

                    var totalPan = Offset.Zero
                    var pastSlop = false
                    var gestureZoom = currentZoom // local accumulator avoids stale rememberUpdatedState

                    do {
                        val event = awaitPointerEvent()
                        if (!event.changes.any { it.pressed }) {
                            selectedReading = null
                            break
                        }

                        if (isScrubbing && event.changes.count { it.pressed } == 1) {
                            // Single-finger scrub: track nearest reading by time
                            val pos = event.changes.firstOrNull()?.position
                            if (pos != null) {
                                selectedReading = findNearestByX(
                                    pos.x, currentSorted, currentVisibleStart, currentVisibleMs,
                                    size.width.toFloat(), mLeft
                                )
                            }
                            event.changes.forEach { it.consume() }
                        } else {
                            // Multi-touch while scrubbing cancels scrub mode
                            if (isScrubbing) {
                                isScrubbing = false
                                selectedReading = null
                            }

                            // Transform handling (pan/zoom)
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            totalPan += panChange
                            if (!pastSlop && totalPan.getDistance() < touchSlop && zoomChange == 1f) {
                                // Below touch slop — wait for more movement
                            } else {
                                pastSlop = true
                                gestureZoom = (gestureZoom * zoomChange).coerceIn(1f, 5f)
                                onZoomChange(gestureZoom)

                                val plotWidth = size.width - mLeft - mRight
                                val visMs = currentWindowMs / gestureZoom
                                val msPerPx = visMs / plotWidth
                                val timeShift = (-panChange.x * msPerPx).toLong()
                                val now = System.currentTimeMillis()
                                val maxEnd = now + currentPredictionMs
                                val newEnd = (currentViewportEnd + timeShift).coerceIn(
                                    currentReadings.minOfOrNull { it.ts }?.plus(visMs.toLong()) ?: maxEnd,
                                    maxEnd
                                )
                                onViewportChange(newEnd)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (true)
                }
            }
    ) {
        val marginLeft = if (glucoseUnit == GlucoseUnit.MGDL) 70f else 50f
        val marginRight = GRAPH_MARGIN_RIGHT
        val marginTop = GRAPH_MARGIN_TOP
        val marginBottom = GRAPH_MARGIN_BOTTOM
        val plotWidth = size.width - marginLeft - marginRight
        val plotHeight = size.height - marginTop - marginBottom

        val yr = computeYRange(sorted.map { it.sgv.toDouble() }, bgLow, bgHigh)

        fun xFor(ts: Long): Float =
            marginLeft + ((ts - visibleStart).toFloat() / visibleMs) * plotWidth
        fun yFor(mgdl: Double): Float =
            marginTop + ((yr.yMax - mgdl) / yr.range).toFloat() * plotHeight

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
            bgLow to AboveHigh.copy(alpha = 0.4f),
            bgHigh to AboveHigh.copy(alpha = 0.4f),
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

        // Exercise bands (rendered before BG data so dots/lines draw on top)
        for (session in exerciseSessions) {
            // Skip sessions that don't overlap the visible window
            if (session.endTime < visibleStart || session.startTime > viewportEnd) continue

            val xStart = xFor(session.startTime).coerceIn(marginLeft, size.width - marginRight)
            val xEnd = xFor(session.endTime).coerceIn(marginLeft, size.width - marginRight)
            if (xEnd <= xStart) continue

            // Filled band
            drawRect(
                color = ExerciseDefault.copy(alpha = 0.15f),
                topLeft = Offset(xStart, marginTop),
                size = Size(xEnd - xStart, plotHeight)
            )

            // Left and right border lines
            val borderColor = ExerciseDefault.copy(alpha = 0.5f)
            if (xFor(session.startTime) >= marginLeft) {
                drawLine(
                    color = borderColor,
                    start = Offset(xStart, marginTop),
                    end = Offset(xStart, marginTop + plotHeight),
                    strokeWidth = 2f
                )
            }
            if (xFor(session.endTime) <= size.width - marginRight) {
                drawLine(
                    color = borderColor,
                    start = Offset(xEnd, marginTop),
                    end = Offset(xEnd, marginTop + plotHeight),
                    strokeWidth = 2f
                )
            }

            // Label at top-left of band
            val category = ExerciseCategory.fromHCType(session.type)
            val label = "${category.emoji} ${exerciseCategoryLabels[category] ?: ""}"
            drawContext.canvas.nativeCanvas.drawText(
                label, xStart + 4f, marginTop + exerciseLabelPaint.fontSpacing, exerciseLabelPaint
            )
        }

        // Readings
        for (i in sorted.indices) {
            val r = sorted[i]
            val x = xFor(r.ts)
            val y = yFor(r.sgv.toDouble())
            val color = dotColor(r.sgv.toDouble(), bgLow, bgHigh)

            if (i < sorted.lastIndex) {
                val next = sorted[i + 1]
                drawLine(
                    color = color,
                    start = Offset(x, y),
                    end = Offset(xFor(next.ts), yFor(next.sgv.toDouble())),
                    strokeWidth = 2f
                )
            }

            val isSelected = selectedReading?.ts == r.ts
            drawCircle(color = color, radius = if (isSelected) 9f else 5f, center = Offset(x, y))
        }

        // Prediction curve (least-squares fit to last 12 min of readings)
        val prediction = PredictionComputer.compute(readings, predictionMinutes, bgLow, bgHigh)
        if (prediction != null) {
            val predColor = predictionColor
            val predDash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            var prevPx = xFor(prediction.anchorTs)
            var prevPy = yFor(prediction.anchorMgdl)
            for (pt in prediction.points) {
                val px = xFor(prediction.anchorTs + pt.minuteOffset * 60_000L)
                val py = yFor(pt.mgdl)
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

        // Treatment markers (bolus + carb)
        val bolusColor = BolusBlue
        val carbColor = CarbGreen
        for (t in treatments) {
            val tx = xFor(t.createdAt)
            if (tx < marginLeft || tx > size.width - marginRight) continue

            // Bolus: downward triangle at bottom of plot area
            t.insulin?.let { dose ->
                if (dose <= 0.0) return@let
                val triSize = (8f + dose.toFloat() * 3f).coerceIn(10f, 30f)
                val baseY = size.height - marginBottom - 4f
                val path = Path().apply {
                    moveTo(tx, baseY)
                    lineTo(tx - triSize / 2, baseY - triSize)
                    lineTo(tx + triSize / 2, baseY - triSize)
                    close()
                }
                drawPath(path, bolusColor)
                val label = String.format(bolusLabelFmt, dose)
                treatmentLabelPaint.color = bolusColor.toArgb()
                drawContext.canvas.nativeCanvas.drawText(
                    label, tx, baseY - triSize - 4f, treatmentLabelPaint
                )
            }

            // Carbs: upward triangle at top of plot area
            t.carbs?.let { grams ->
                if (grams <= 0.0) return@let
                val triSize = (8f + grams.toFloat() * 0.3f).coerceIn(10f, 30f)
                val baseY = marginTop + 4f
                val path = Path().apply {
                    moveTo(tx, baseY)
                    lineTo(tx - triSize / 2, baseY + triSize)
                    lineTo(tx + triSize / 2, baseY + triSize)
                    close()
                }
                drawPath(path, carbColor)
                val label = String.format(carbLabelFmt, grams)
                treatmentLabelPaint.color = carbColor.toArgb()
                drawContext.canvas.nativeCanvas.drawText(
                    label, tx, baseY + triSize + treatmentLabelPaint.fontSpacing, treatmentLabelPaint
                )
            }
        }

        // Scrub crosshair + tooltip
        selectedReading?.let { sel ->
            val pointX = xFor(sel.ts)
            val pointY = yFor(sel.sgv.toDouble())

            drawLine(
                color = crosshairColor,
                start = Offset(pointX, marginTop),
                end = Offset(pointX, size.height - marginBottom),
                strokeWidth = 1.5f
            )

            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val timeStr = sdf.format(java.util.Date(sel.ts))
            val valueStr = glucoseUnit.format(sel.sgv)
            val direction = try { Direction.valueOf(sel.direction) } catch (_: Exception) { Direction.NONE }
            val deltaStr = sel.delta?.let { glucoseUnit.formatDeltaCompact(it) }

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
        val yStep = if (glucoseUnit == GlucoseUnit.MGDL) {
            if (yr.range > 180.0) 50.0 else 25.0
        } else {
            if (yr.range > 180.0) 2.0 * GlucoseUnit.MGDL_FACTOR else GlucoseUnit.MGDL_FACTOR
        }
        var yLabel = Math.ceil(yr.yMin / yStep) * yStep
        while (yLabel <= yr.yMax) {
            val y = yFor(yLabel)
            if (y > marginTop + 10 && y < size.height - marginBottom - 10) {
                val labelText = if (glucoseUnit == GlucoseUnit.MGDL) {
                    "%.0f".format(yLabel)
                } else {
                    "%.0f".format(yLabel / GlucoseUnit.MGDL_FACTOR)
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

        val yr = computeYRange(sorted.map { it.sgv.toDouble() }, bgLow, bgHigh)

        fun xFor(ts: Long): Float = ((ts - minimapStart).toFloat() / MINIMAP_WINDOW_MS) * w
        fun yFor(mgdl: Double): Float = ((yr.yMax - mgdl) / yr.range).toFloat() * h

        // Threshold lines (subtle)
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
        drawLine(
            color = Color(0x40FFB800), start = Offset(0f, yFor(bgLow)),
            end = Offset(w, yFor(bgLow)), pathEffect = dashEffect, strokeWidth = 1f
        )
        drawLine(
            color = Color(0x40FFB800), start = Offset(0f, yFor(bgHigh)),
            end = Offset(w, yFor(bgHigh)), pathEffect = dashEffect, strokeWidth = 1f
        )

        // Data points
        for (r in sorted) {
            val x = xFor(r.ts)
            val y = yFor(r.sgv.toDouble())
            drawCircle(color = dotColor(r.sgv.toDouble(), bgLow, bgHigh), radius = 2.5f, center = Offset(x, y))
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

private fun dotColor(mgdl: Double, bgLow: Double, bgHigh: Double): Color = when {
    mgdl <= CRITICAL_LOW -> BelowLow
    mgdl < bgLow -> BelowLow
    mgdl >= CRITICAL_HIGH -> BelowLow
    mgdl > bgHigh -> AboveHigh
    else -> InRange
}

internal const val GRAPH_MARGIN_TOP = 16f
internal const val GRAPH_MARGIN_BOTTOM = 40f
internal const val GRAPH_MARGIN_RIGHT = 16f

internal const val DOT_HIT_RADIUS = 40f

internal fun findNearestDot(
    finger: Offset,
    sorted: List<GlucoseReading>,
    viewport: GraphViewport
): GlucoseReading? {
    if (sorted.isEmpty()) return null
    val yr = computeYRange(sorted.map { it.sgv.toDouble() }, viewport.bgLow, viewport.bgHigh)

    fun xFor(ts: Long): Float =
        viewport.marginLeft + ((ts - viewport.visibleStart).toFloat() / viewport.visibleMs) * viewport.plotWidth
    fun yFor(mgdl: Double): Float =
        GRAPH_MARGIN_TOP + ((yr.yMax - mgdl) / yr.range).toFloat() * viewport.plotHeight

    var closest: GlucoseReading? = null
    var closestDist = DOT_HIT_RADIUS
    for (r in sorted) {
        val dx = xFor(r.ts) - finger.x
        val dy = yFor(r.sgv.toDouble()) - finger.y
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        if (dist < closestDist) {
            closestDist = dist
            closest = r
        }
    }
    return closest
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
    val marginRight = GRAPH_MARGIN_RIGHT
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
