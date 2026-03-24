@file:Suppress("MatchingDeclarationName") // ViewModel is internal to the ExerciseHistoryScreen

package com.psjostrom.strimma.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.health.ExerciseBGAnalyzer
import com.psjostrom.strimma.data.health.ExerciseBGContext
import com.psjostrom.strimma.data.health.ExerciseCategory
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.data.health.StoredExerciseSession
import com.psjostrom.strimma.ui.theme.BelowLow
import com.psjostrom.strimma.ui.theme.ExerciseDefault
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private const val MS_PER_MINUTE = 60_000L
private const val PRE_WINDOW_MS = 30 * MS_PER_MINUTE
private const val POST_WINDOW_MS = 4 * 60 * MS_PER_MINUTE

@HiltViewModel
class ExerciseHistoryViewModel @Inject constructor(
    private val exerciseDao: ExerciseDao,
    private val readingDao: ReadingDao,
    private val exerciseBGAnalyzer: ExerciseBGAnalyzer,
    private val settings: SettingsRepository
) : ViewModel() {

    val sessions: StateFlow<List<StoredExerciseSession>> = exerciseDao.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val glucoseUnit: StateFlow<GlucoseUnit> = settings.glucoseUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GlucoseUnit.MMOL)

    val bgLow: StateFlow<Float> = settings.bgLow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 72f)

    suspend fun computeBGContext(session: StoredExerciseSession): ExerciseBGContext? {
        val preStart = session.startTime - PRE_WINDOW_MS
        val postEnd = session.endTime + POST_WINDOW_MS
        val readings = readingDao.readingsInRange(preStart, postEnd)
        val hrSamples = exerciseDao.getHeartRateForSession(session.id)
        return exerciseBGAnalyzer.analyze(session, readings, hrSamples, bgLow.value.toDouble())
    }

    suspend fun getSparklineReadings(session: StoredExerciseSession): List<GlucoseReading> {
        val preStart = session.startTime - PRE_WINDOW_MS
        val postEnd = session.endTime + POST_WINDOW_MS
        return readingDao.readingsInRange(preStart, postEnd)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseHistoryScreen(
    onBack: () -> Unit,
    viewModel: ExerciseHistoryViewModel = hiltViewModel()
) {
    val sessions by viewModel.sessions.collectAsState()
    val glucoseUnit by viewModel.glucoseUnit.collectAsState()

    var selectedExercise by remember { mutableStateOf<StoredExerciseSession?>(null) }
    var selectedBGContext by remember { mutableStateOf<ExerciseBGContext?>(null) }
    var bgContextLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(selectedExercise) {
        val session = selectedExercise
        if (session != null) {
            bgContextLoaded = false
            selectedBGContext = viewModel.computeBGContext(session)
            bgContextLoaded = true
        } else {
            selectedBGContext = null
            bgContextLoaded = false
        }
    }

    if (selectedExercise != null && bgContextLoaded) {
        ExerciseDetailSheet(
            session = selectedExercise!!,
            bgContext = selectedBGContext,
            glucoseUnit = glucoseUnit,
            onDismiss = { selectedExercise = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.exercise_history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back)
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
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.exercise_history_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    ExerciseCard(
                        session = session,
                        glucoseUnit = glucoseUnit,
                        viewModel = viewModel,
                        onClick = { selectedExercise = session }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExerciseCard(
    session: StoredExerciseSession,
    glucoseUnit: GlucoseUnit,
    viewModel: ExerciseHistoryViewModel,
    onClick: () -> Unit
) {
    val category = ExerciseCategory.fromHCType(session.type)
    val typeName = stringResource(category.labelRes)
    val durationMin = ((session.endTime - session.startTime) / MS_PER_MINUTE).toInt()
    val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dateFmt = SimpleDateFormat("d MMM", Locale.getDefault())
    val timeRange = "${timeFmt.format(Date(session.startTime))}\u2013${timeFmt.format(Date(session.endTime))}"
    val dateStr = dateFmt.format(Date(session.startTime))

    // Load sparkline readings and BG context
    var sparklineReadings by remember { mutableStateOf<List<GlucoseReading>>(emptyList()) }
    var bgContext by remember { mutableStateOf<ExerciseBGContext?>(null) }

    LaunchedEffect(session.id) {
        sparklineReadings = viewModel.getSparklineReadings(session)
        bgContext = viewModel.computeBGContext(session)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Top row: emoji + type + duration  |  sparkline
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${category.emoji} $typeName \u00B7 $durationMin min",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                if (sparklineReadings.isNotEmpty()) {
                    BGSparkline(
                        readings = sparklineReadings,
                        modifier = Modifier.size(80.dp, 28.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Subtitle: date + time range
            Text(
                text = "$dateStr \u00B7 $timeRange",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Stats row
            bgContext?.let { ctx ->
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ctx.entryBG?.let { entry ->
                        StatChip(
                            label = stringResource(R.string.exercise_detail_entry_bg),
                            value = glucoseUnit.format(entry)
                        )
                    }
                    ctx.lowestBG?.let { lowest ->
                        StatChip(
                            label = stringResource(R.string.exercise_detail_lowest_bg),
                            value = glucoseUnit.format(lowest),
                            valueColor = if (ctx.postExerciseHypo) BelowLow else null
                        )
                    }
                    ctx.avgHR?.let { hr ->
                        StatChip(
                            label = stringResource(R.string.exercise_detail_avg_hr),
                            value = "$hr"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color? = null
) {
    Column {
        Text(
            text = label,
            fontSize = 11.sp,
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

@Composable
private fun BGSparkline(
    readings: List<GlucoseReading>,
    modifier: Modifier = Modifier
) {
    val sparkColor = ExerciseDefault
    Canvas(modifier = modifier) {
        if (readings.size < 2) return@Canvas

        val sorted = readings.sortedBy { it.ts }
        val minTs = sorted.first().ts
        val maxTs = sorted.last().ts
        val tsRange = (maxTs - minTs).toFloat()
        if (tsRange <= 0f) return@Canvas

        val minSgv = sorted.minOf { it.sgv }.toFloat()
        val maxSgv = sorted.maxOf { it.sgv }.toFloat()
        val sgvRange = (maxSgv - minSgv).coerceAtLeast(20f)

        val w = size.width
        val h = size.height
        val pad = 2f

        var prevX = 0f
        var prevY = 0f
        for ((i, r) in sorted.withIndex()) {
            val x = pad + ((r.ts - minTs) / tsRange) * (w - 2 * pad)
            val y = pad + ((maxSgv - r.sgv) / sgvRange) * (h - 2 * pad)
            if (i > 0) {
                drawLine(
                    color = sparkColor,
                    start = Offset(prevX, prevY),
                    end = Offset(x, y),
                    strokeWidth = 2f
                )
            }
            prevX = x
            prevY = y
        }
    }
}
