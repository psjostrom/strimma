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
import androidx.compose.material.icons.outlined.NotificationsOff
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.Direction
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.IOBComputer
import com.psjostrom.strimma.data.calendar.GuidanceState
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.Treatment
import com.psjostrom.strimma.data.health.ExerciseBGContext
import com.psjostrom.strimma.data.health.ExerciseCategory
import com.psjostrom.strimma.data.health.StoredExerciseSession
import com.psjostrom.strimma.graph.CRITICAL_HIGH
import com.psjostrom.strimma.graph.BgStatus
import com.psjostrom.strimma.graph.bgStatusFor
import com.psjostrom.strimma.graph.CRITICAL_LOW
import com.psjostrom.strimma.graph.PredictionComputer
import com.psjostrom.strimma.graph.ThresholdCrossing
import com.psjostrom.strimma.graph.CrossingType
import com.psjostrom.strimma.graph.computeYAxisLabels
import com.psjostrom.strimma.graph.computeYRange
import com.psjostrom.strimma.network.FollowerStatus
import com.psjostrom.strimma.notification.AlertCategory
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.ui.components.PauseAlertsSheet
import com.psjostrom.strimma.ui.components.rememberCountdownText
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
    guidanceState: GuidanceState = GuidanceState.NoWorkout,
    onComputeBGContext: (suspend (StoredExerciseSession) -> ExerciseBGContext?)? = null,
    pauseLowExpiryMs: Long? = null,
    pauseHighExpiryMs: Long? = null,
    onPauseAlerts: (AlertCategory, Long) -> Unit = { _, _ -> },
    onCancelPause: (AlertCategory) -> Unit = {}
) {
    val mainWindowMs = graphWindowHours * 3600_000L

    val predictionMs = predictionMinutes * 60_000L
    var viewportEnd by remember { mutableLongStateOf(System.currentTimeMillis() + predictionMs) }
    var zoomScale by remember { mutableFloatStateOf(1f) }

    var showPauseSheet by remember { mutableStateOf(false) }
    var showGuidanceSheet by remember { mutableStateOf(false) }

    // Exercise detail sheet state
    var selectedExercise by remember { mutableStateOf<StoredExerciseSession?>(null) }
    var selectedBGContext by remember { mutableStateOf<ExerciseBGContext?>(null) }
    var bgContextLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(selectedExercise) {
        val session = selectedExercise
        if (session != null && onComputeBGContext != null) {
            bgContextLoaded = false
            selectedBGContext = onComputeBGContext(session)
            bgContextLoaded = true
        } else {
            selectedBGContext = null
            bgContextLoaded = false
        }
    }

    if (selectedExercise != null && bgContextLoaded) {
        val session = selectedExercise!!
        val preStart = session.startTime - 30 * 60_000L
        val postEnd = session.endTime + 4 * 3600_000L
        val exerciseReadings = readings.filter { it.ts in preStart..postEnd }
        ExerciseDetailSheet(
            session = session,
            bgContext = selectedBGContext,
            readings = exerciseReadings,
            glucoseUnit = glucoseUnit,
            bgLow = bgLow.toDouble(),
            bgHigh = bgHigh.toDouble(),
            onDismiss = { selectedExercise = null }
        )
    }

    if (showPauseSheet) {
        PauseAlertsSheet(
            pauseLowExpiryMs = pauseLowExpiryMs,
            pauseHighExpiryMs = pauseHighExpiryMs,
            onPause = onPauseAlerts,
            onCancel = onCancelPause,
            onDismiss = { showPauseSheet = false }
        )
    }

    // Auto-track "now" + prediction space when viewport is near current time
    LaunchedEffect(readings) {
        val now = System.currentTimeMillis()
        if (now + predictionMs - viewportEnd < 2 * 60 * 1000) {
            viewportEnd = now + predictionMs
        }
    }

    Scaffold(
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
            Box(modifier = Modifier.fillMaxWidth()) {
                BgHeader(
                    latestReading, bgLow, bgHigh, glucoseUnit, crossing, followerStatus, iob, treatments, iobTauMinutes,
                    pauseLowExpiryMs = pauseLowExpiryMs,
                    pauseHighExpiryMs = pauseHighExpiryMs,
                    onPausePillClick = { showPauseSheet = true },
                    modifier = Modifier.fillMaxWidth()
                )
                IconButton(
                    onClick = { showPauseSheet = true },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(
                        Icons.Outlined.NotificationsOff,
                        contentDescription = stringResource(R.string.pause_alerts),
                        tint = if (pauseLowExpiryMs != null || pauseHighExpiryMs != null)
                            InRange else MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val guidance = guidanceState
            if (guidance is GuidanceState.WorkoutApproaching) {
                PreActivityCard(
                    state = guidance,
                    onClick = { showGuidanceSheet = true }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (showGuidanceSheet && guidance is GuidanceState.WorkoutApproaching) {
                PlannedWorkoutSheet(
                    event = guidance.event,
                    guidance = guidance,
                    glucoseUnit = glucoseUnit,
                    onDismiss = { showGuidanceSheet = false }
                )
            }

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
                    onExerciseTap = { selectedExercise = it },
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
    iobTauMinutes: Double = 55.0,
    pauseLowExpiryMs: Long? = null,
    pauseHighExpiryMs: Long? = null,
    onPausePillClick: () -> Unit = {},
    modifier: Modifier = Modifier
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

    val isStale = minutesAgo > AlertManager.STALE_THRESHOLD_MINUTES
    val direction = reading?.let {
        Direction.parse(it.direction)
    } ?: Direction.NONE

    val bgColor = when {
        reading == null || isStale -> Stale
        reading.sgv < bgLow -> BelowLow
        reading.sgv > bgHigh -> AboveHigh
        else -> InRange
    }

    Column(
        modifier = modifier
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
            minutesAgo < 0 -> stringResource(R.string.main_waiting_for_reading)
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
                IobDetailSheet(treatments, iobTauMinutes, onDismiss = { showIobDetail = false })
            }
        }

        pauseHighExpiryMs?.let { expiry ->
            if (expiry > System.currentTimeMillis()) {
                val countdownText = rememberCountdownText(expiry)
                Spacer(modifier = Modifier.height(8.dp))
                val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                Surface(
                    onClick = onPausePillClick,
                    shape = RoundedCornerShape(100),
                    color = if (isDark) TintWarning else LightTintWarning
                ) {
                    Text(
                        text = stringResource(R.string.pause_high_active, countdownText),
                        color = AboveHigh,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }
            }
        }

        pauseLowExpiryMs?.let { expiry ->
            if (expiry > System.currentTimeMillis()) {
                val countdownText = rememberCountdownText(expiry)
                Spacer(modifier = Modifier.height(8.dp))
                val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                Surface(
                    onClick = onPausePillClick,
                    shape = RoundedCornerShape(100),
                    color = if (isDark) TintDanger else LightTintDanger
                ) {
                    Text(
                        text = stringResource(R.string.pause_low_active, countdownText),
                        color = BelowLow,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }
            }
        }

        if (followerStatus is FollowerStatus.Connecting || followerStatus is FollowerStatus.Disconnected) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when (followerStatus) {
                    is FollowerStatus.Connecting -> stringResource(R.string.main_follower_connecting)
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
private fun IobDetailSheet(treatments: List<Treatment>, tauMinutes: Double, onDismiss: () -> Unit) {
    val now = System.currentTimeMillis()
    val cutoff = now - IOBComputer.lookbackMs(tauMinutes)
    val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    val insulinTreatments = treatments
        .filter { (it.insulin ?: 0.0) > 0.0 && it.createdAt in cutoff..now }
        .sortedByDescending { it.createdAt }

    val currentIob = IOBComputer.computeIOB(treatments, now, tauMinutes)

    // Compute decay curve points (every 5 min for 5*tau)
    val decayStepMs = 5 * 60_000L
    val decayEndMs = IOBComputer.lookbackMs(tauMinutes)
    val decayPoints = (0..decayEndMs step decayStepMs).map { offset ->
        val futureMs = now + offset
        offset to IOBComputer.computeIOB(treatments, futureMs, tauMinutes)
    }

    // Trim tail at clear point (IOB < 0.1u) + one extra point for the zero line
    val clearIndex = decayPoints.indexOfFirst { it.second < 0.1 }
    val trimmedPoints = if (clearIndex > 0) decayPoints.take(clearIndex + 1) else decayPoints
    val clearTimeMinutes = if (clearIndex > 0) decayPoints[clearIndex].first / 60_000L else null

    StrimmaBottomSheet(onDismiss = onDismiss) {
        // Hero IOB
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = stringResource(R.string.main_iob_breakdown_title),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "%.1f".format(currentIob),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = InRange
                    )
                    Text(
                        text = "U",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = InRange,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
            if (clearTimeMinutes != null && clearTimeMinutes > 0) {
                val hours = clearTimeMinutes / 60
                val mins = clearTimeMinutes % 60
                val clearText = when {
                    hours > 0 && mins > 0 -> "${hours}h ${mins}min"
                    hours > 0 -> "${hours}h"
                    else -> "${mins}min"
                }
                Text(
                    text = stringResource(R.string.main_iob_clears_in, clearText),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Decay curve
        if (trimmedPoints.size >= 2 && currentIob > 0.0) {
            IobDecayCurve(
                points = trimmedPoints,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )
            Spacer(Modifier.height(16.dp))
        }

        // Treatment rows
        if (insulinTreatments.isNotEmpty()) {
            Text(
                text = stringResource(R.string.main_iob_active_boluses),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            insulinTreatments.forEach { t ->
                val dose = t.insulin ?: return@forEach
                val minutesSince = (now - t.createdAt) / 60_000.0
                val remainingIob = IOBComputer.iobForTreatment(dose, minutesSince, tauMinutes)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${timeFmt.format(java.util.Date(t.createdAt))}  \u00B7  " +
                                stringResource(R.string.main_bolus_label, dose),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.main_iob_remaining, remainingIob),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = InRange
                        )
                    }
                    t.carbs?.let { carbs ->
                        if (carbs > 0.0) {
                            Text(
                                text = stringResource(R.string.main_carb_label, carbs),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IobDecayCurve(
    points: List<Pair<Long, Double>>,
    modifier: Modifier = Modifier
) {
    val lineColor = InRange
    val fillColor = InRange.copy(alpha = 0.1f)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    val maxTimeMs = points.last().first

    Canvas(modifier = modifier) {
        val maxIob = points.maxOf { it.second }.toFloat().coerceAtLeast(0.5f)
        val maxTime = maxTimeMs.toFloat()
        if (maxTime <= 0f) return@Canvas

        val leftPad = 20f
        val bottomPad = 20f
        val pad = 4f
        val w = size.width - leftPad - pad
        val h = size.height - pad - bottomPad

        fun xFor(timeMs: Long) = leftPad + (timeMs.toFloat() / maxTime) * w
        fun yFor(iob: Double) = pad + ((maxIob - iob.toFloat()) / maxIob) * h

        // Y-axis labels + grid lines (every 1U up to 4, every 2U above)
        val maxInt = maxIob.toInt()
        val yStep = if (maxInt > 4) 2 else 1
        for (v in 0..maxInt step yStep) {
            val y = yFor(v.toDouble())
            val label = "$v"
            val result = textMeasurer.measure(label, labelStyle)
            drawText(result, topLeft = Offset(leftPad - result.size.width - 4f, y - result.size.height / 2f))
            if (v > 0) {
                drawLine(gridColor, Offset(leftPad, y), Offset(leftPad + w, y), strokeWidth = 1f)
            }
        }

        // X-axis: labels at whole hours
        val clockFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val nowMs = System.currentTimeMillis()
        val hourMs = 3600_000L
        val firstHour = ((nowMs / hourMs) + 1) * hourMs
        var tickMs = firstHour
        while (tickMs <= nowMs + maxTime.toLong()) {
            val offsetMs = tickMs - nowMs
            val x = xFor(offsetMs)
            val label = clockFmt.format(java.util.Date(tickMs))
            val result = textMeasurer.measure(label, labelStyle)
            val labelX = (x - result.size.width / 2f).coerceIn(leftPad, leftPad + w - result.size.width)
            drawText(result, topLeft = Offset(labelX, pad + h + 2f))
            tickMs += hourMs
        }

        // Fill area under curve
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(xFor(points.first().first), yFor(points.first().second))
            for (p in points.drop(1)) {
                lineTo(xFor(p.first), yFor(p.second))
            }
            lineTo(xFor(points.last().first), pad + h)
            lineTo(xFor(points.first().first), pad + h)
            close()
        }
        drawPath(path, fillColor)

        // Draw the curve line
        var prevX = 0f
        var prevY = 0f
        for ((i, p) in points.withIndex()) {
            val x = xFor(p.first)
            val y = yFor(p.second)
            if (i > 0) {
                drawLine(lineColor, Offset(prevX, prevY), Offset(x, y), strokeWidth = 2f)
            }
            prevX = x
            prevY = y
        }

        // Start dot
        drawCircle(lineColor, radius = 3.5f, center = Offset(xFor(points.first().first), yFor(points.first().second)))
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
    onViewportChange: (Long) -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    predictionMinutes: Int = 15,
    glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
    treatments: List<Treatment> = emptyList(),
    exerciseSessions: List<StoredExerciseSession> = emptyList(),
    onExerciseTap: (StoredExerciseSession) -> Unit = {},
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
    val currentExerciseSessions by rememberUpdatedState(exerciseSessions)
    val currentOnExerciseTap by rememberUpdatedState(onExerciseTap)
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
            textSize = 32f
            textAlign = android.graphics.Paint.Align.LEFT
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            color = ExerciseDefault.toArgb()
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
                            // Tap detection: if we never moved past touch slop and weren't scrubbing,
                            // check if the tap landed on an exercise band
                            if (!pastSlop && !isScrubbing) {
                                val tapX = down.position.x
                                val plotW = size.width - mLeft - mRight
                                for (session in currentExerciseSessions) {
                                    if (session.endTime < currentVisibleStart || session.startTime > currentViewportEnd) continue
                                    val xStart = (mLeft + ((session.startTime - currentVisibleStart).toFloat() / currentVisibleMs) * plotW)
                                        .coerceIn(mLeft, size.width - mRight)
                                    val xEnd = (mLeft + ((session.endTime - currentVisibleStart).toFloat() / currentVisibleMs) * plotW)
                                        .coerceIn(mLeft, size.width - mRight)
                                    if (xEnd > xStart && tapX in xStart..xEnd) {
                                        currentOnExerciseTap(session)
                                        break
                                    }
                                }
                            }
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
                                val minEnd = currentReadings.minOfOrNull { it.ts }?.plus(visMs.toLong()) ?: maxEnd
                                val newEnd = if (minEnd <= maxEnd) {
                                    (currentViewportEnd + timeShift).coerceIn(minEnd, maxEnd)
                                } else {
                                    maxEnd
                                }
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
            val direction = Direction.parse(sel.direction)
            val deltaStr = sel.delta?.let { glucoseUnit.formatDeltaCompact(it) }

            val line1 = "$valueStr ${direction.arrow}"
            val line2 = if (deltaStr != null) "$timeStr  $deltaStr" else timeStr

            val tooltipPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = tooltipText.toArgb()
                textSize = 44f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val subtextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = tooltipSubtext.toArgb()
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
            this.color = axisColor.toArgb()
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
        for (label in computeYAxisLabels(yr, glucoseUnit)) {
            val y = yFor(label.mgdl)
            if (y > marginTop + 10 && y < size.height - marginBottom - 10) {
                drawContext.canvas.nativeCanvas.drawText(
                    label.text, marginLeft - 6f, y + 8f, textPaint
                )
            }
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
                        val rangeMin0 = start0 + visibleMs
                        if (rangeMin0 <= now0) {
                            onViewportChange((time + visibleMs / 2).coerceIn(rangeMin0, now0))
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val pos = event.changes.firstOrNull()?.position ?: break
                            if (!event.changes.any { it.pressed }) break
                            val nowD = System.currentTimeMillis()
                            val startD = nowD - MINIMAP_WINDOW_MS
                            val dragTime = startD + ((pos.x.coerceIn(0f, size.width.toFloat()) / size.width) * MINIMAP_WINDOW_MS).toLong()
                            val rangeMinD = startD + visibleMs
                            if (rangeMinD <= nowD) {
                                onViewportChange((dragTime + visibleMs / 2).coerceIn(rangeMinD, nowD))
                            }
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
            this.color = miniAxisColor.toArgb()
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

private fun dotColor(mgdl: Double, bgLow: Double, bgHigh: Double): Color =
    when (bgStatusFor(mgdl, bgLow, bgHigh)) {
        BgStatus.IN_RANGE -> InRange
        BgStatus.HIGH -> AboveHigh
        BgStatus.LOW -> BelowLow
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
