@file:Suppress("MatchingDeclarationName") // ViewModel is internal to the ExerciseHistoryScreen

package com.psjostrom.strimma.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.IOBComputer
import com.psjostrom.strimma.data.InsulinType
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.TreatmentDao
import com.psjostrom.strimma.data.calendar.CalendarReader
import com.psjostrom.strimma.data.calendar.GuidanceState
import com.psjostrom.strimma.data.calendar.WorkoutCategory
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import com.psjostrom.strimma.data.health.ExerciseBGAnalyzer
import com.psjostrom.strimma.data.health.ExerciseBGContext
import com.psjostrom.strimma.data.health.ExerciseCategory
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.data.health.StoredExerciseSession
import com.psjostrom.strimma.ui.theme.BelowLow
import com.psjostrom.strimma.ui.theme.ExerciseDefault
import com.psjostrom.strimma.ui.theme.InRange
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_DAY = 86_400_000L
private const val PRE_WINDOW_MS = 30 * MS_PER_MINUTE
private const val POST_WINDOW_MS = 4 * 60 * MS_PER_MINUTE
private const val PLANNED_LOOKAHEAD_DAYS = 365
private const val PLANNED_POLL_MS = 5 * MS_PER_MINUTE
private const val DATASTORE_PROPAGATION_MS = 100L

@HiltViewModel
class ExerciseHistoryViewModel @Inject constructor(
    private val exerciseDao: ExerciseDao,
    private val readingDao: ReadingDao,
    private val treatmentDao: TreatmentDao,
    private val exerciseBGAnalyzer: ExerciseBGAnalyzer,
    private val settings: SettingsRepository,
    val calendarReader: CalendarReader
) : ViewModel() {

    val sessions: StateFlow<List<StoredExerciseSession>> = exerciseDao.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val glucoseUnit: StateFlow<GlucoseUnit> = settings.glucoseUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GlucoseUnit.MMOL)

    val bgLow: StateFlow<Float> = settings.bgLow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 72f)

    val workoutCalendarId: StateFlow<Long> = settings.workoutCalendarId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1L)

    val workoutCalendarName: StateFlow<String> = settings.workoutCalendarName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _upcomingWorkouts = MutableStateFlow<List<WorkoutEvent>>(emptyList())
    val upcomingWorkouts: StateFlow<List<WorkoutEvent>> = _upcomingWorkouts

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    init {
        // React to calendarId changes from DataStore — fixes race where
        // the init poll runs before DataStore emits the real value
        viewModelScope.launch {
            workoutCalendarId.collect { calId ->
                _upcomingWorkouts.value = if (calId >= 0) {
                    calendarReader.getUpcomingWorkouts(calId, PLANNED_LOOKAHEAD_DAYS.toLong() * MS_PER_DAY)
                } else emptyList()
            }
        }
        // Background poll for calendar changes (events added/removed externally)
        viewModelScope.launch {
            while (currentCoroutineContext().isActive) {
                delay(PLANNED_POLL_MS)
                refreshUpcoming()
            }
        }
    }

    fun onPullToRefresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            calendarReader.requestCalendarSync()
            refreshUpcoming()
            _isRefreshing.value = false
        }
    }

    private suspend fun refreshUpcoming() {
        val calId = workoutCalendarId.value
        _upcomingWorkouts.value = if (calId >= 0) {
            calendarReader.getUpcomingWorkouts(calId, PLANNED_LOOKAHEAD_DAYS.toLong() * MS_PER_DAY)
        } else emptyList()
    }

    fun onCalendarSelected(id: Long, name: String) {
        viewModelScope.launch {
            settings.setWorkoutCalendarId(id)
            settings.setWorkoutCalendarName(name)
            // Refresh immediately after selection
            delay(DATASTORE_PROPAGATION_MS)
            refreshUpcoming()
        }
    }

    suspend fun computeBGContext(session: StoredExerciseSession): ExerciseBGContext? {
        val preStart = session.startTime - PRE_WINDOW_MS
        val postEnd = session.endTime + POST_WINDOW_MS
        val readings = readingDao.readingsInRange(preStart, postEnd)
        val hrSamples = exerciseDao.getHeartRateForSession(session.id)
        return exerciseBGAnalyzer.analyze(session, readings, hrSamples, bgLow.value.toDouble())
    }

    suspend fun computePlannedGuidance(event: WorkoutEvent): GuidanceState {
        val now = System.currentTimeMillis()
        val readings = readingDao.readingsInRange(now - MS_PER_DAY, now)
        val latest = readings.maxByOrNull { it.ts }

        val treatmentsEnabled = settings.treatmentsSyncEnabled.first()
        val iob = if (treatmentsEnabled) {
            val insulinType = settings.insulinType.first()
            val customDIA = settings.customDIA.first()
            val tau = IOBComputer.tauForInsulinType(insulinType, customDIA)
            val treatments = treatmentDao.insulinSince(now - IOBComputer.lookbackMs(tau))
            IOBComputer.computeIOB(treatments, now, tau)
        } else 0.0

        val targetLow = settings.workoutTargetLow(event.category).first()
        val targetHigh = settings.workoutTargetHigh(event.category).first()

        return MainViewModel.computeGuidance(
            event, latest, readings, iob,
            targetLow, targetHigh,
            bgLow.value.toDouble(), settings.bgHigh.first().toDouble()
        )
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
    val bgLow by viewModel.bgLow.collectAsState()
    val calendarId by viewModel.workoutCalendarId.collectAsState()
    val upcomingWorkouts by viewModel.upcomingWorkouts.collectAsState()

    val tabs = listOf(
        stringResource(R.string.exercise_tab_planned),
        stringResource(R.string.exercise_tab_completed)
    )
    val pagerState = rememberPagerState { tabs.size }

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

    // Planned workout detail sheet
    var selectedPlannedWorkout by remember { mutableStateOf<WorkoutEvent?>(null) }
    var plannedGuidance by remember { mutableStateOf<GuidanceState?>(null) }
    var plannedGuidanceLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(selectedPlannedWorkout) {
        val event = selectedPlannedWorkout
        if (event != null) {
            plannedGuidanceLoaded = false
            plannedGuidance = viewModel.computePlannedGuidance(event)
            plannedGuidanceLoaded = true
        } else {
            plannedGuidance = null
            plannedGuidanceLoaded = false
        }
    }

    if (selectedPlannedWorkout != null && plannedGuidanceLoaded) {
        val guidance = plannedGuidance
        if (guidance is GuidanceState.WorkoutApproaching) {
            PlannedWorkoutSheet(
                state = guidance,
                glucoseUnit = glucoseUnit,
                onDismiss = { selectedPlannedWorkout = null }
            )
        } else {
            PlannedWorkoutSheet(
                event = selectedPlannedWorkout!!,
                glucoseUnit = glucoseUnit,
                onDismiss = { selectedPlannedWorkout = null }
            )
        }
    }

    // Calendar permission launcher for connect button
    var showCalendarPicker by remember { mutableStateOf(false) }
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showCalendarPicker = true
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val coroutineScope = rememberCoroutineScope()
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        text = {
                            Text(
                                title,
                                fontWeight = if (pagerState.currentPage == index) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 14.sp
                            )
                        }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> PlannedTab(
                        calendarId = calendarId,
                        workouts = upcomingWorkouts,
                        glucoseUnit = glucoseUnit,
                        isRefreshing = viewModel.isRefreshing.collectAsState().value,
                        onRefresh = viewModel::onPullToRefresh,
                        onWorkoutClick = { selectedPlannedWorkout = it },
                        onConnectCalendar = {
                            if (viewModel.calendarReader.hasPermission()) {
                                showCalendarPicker = true
                            } else {
                                calendarPermissionLauncher.launch(android.Manifest.permission.READ_CALENDAR)
                            }
                        }
                    )
                    1 -> CompletedTab(
                        sessions = sessions,
                        glucoseUnit = glucoseUnit,
                        bgLow = bgLow,
                        viewModel = viewModel,
                        onSessionClick = { selectedExercise = it }
                    )
                }
            }
        }
    }

    if (showCalendarPicker) {
        CalendarPickerDialog(
            calendarReader = viewModel.calendarReader,
            onSelect = { id, name ->
                viewModel.onCalendarSelected(id, name)
                showCalendarPicker = false
            },
            onDismiss = { showCalendarPicker = false }
        )
    }
}

@Composable
private fun CalendarPickerDialog(
    calendarReader: CalendarReader,
    onSelect: (Long, String) -> Unit,
    onDismiss: () -> Unit
) {
    var calendars by remember { mutableStateOf(emptyList<com.psjostrom.strimma.data.calendar.CalendarInfo>()) }
    LaunchedEffect(Unit) { calendars = calendarReader.getCalendars() }

    // Auto-refresh when CalendarProvider changes (new calendar synced)
    LaunchedEffect(Unit) {
        calendarReader.observeCalendars().collect { calendars = calendarReader.getCalendars() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workout_calendar_picker_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for (cal in calendars) {
                    TextButton(onClick = { onSelect(cal.id, cal.displayName) }) {
                        Text(
                            cal.displayName,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (calendars.isEmpty()) {
                    Text(
                        stringResource(R.string.exercise_no_calendars),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.exercise_calendar_missing_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlannedTab(
    calendarId: Long,
    workouts: List<WorkoutEvent>,
    glucoseUnit: GlucoseUnit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onWorkoutClick: (WorkoutEvent) -> Unit,
    onConnectCalendar: () -> Unit
) {
    if (calendarId < 0) {
        // No calendar connected — show prominent CTA
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, InRange.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.exercise_connect_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.exercise_connect_subtitle),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onConnectCalendar,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.exercise_connect_button))
                    }
                }
            }
        }
    } else {
        val pullToRefreshState = rememberPullToRefreshState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pullToRefresh(
                    isRefreshing = isRefreshing,
                    state = pullToRefreshState,
                    onRefresh = onRefresh
                )
        ) {
            if (workouts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.exercise_planned_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(workouts, key = { "${it.startTime}_${it.title}" }) { event ->
                        PlannedWorkoutCard(
                            event = event,
                            glucoseUnit = glucoseUnit,
                            onClick = { onWorkoutClick(event) }
                        )
                    }
                }
            }

            PullToRefreshDefaults.Indicator(
                state = pullToRefreshState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
private fun PlannedWorkoutCard(
    event: WorkoutEvent,
    glucoseUnit: GlucoseUnit,
    onClick: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateStr = dateFmt.format(Date(event.startTime))
    val timeRange = "${timeFmt.format(Date(event.startTime))}\u2013${timeFmt.format(Date(event.endTime))}"
    val durationMin = ((event.endTime - event.startTime) / MS_PER_MINUTE).toInt()
    val categoryName = event.category.name.lowercase().replaceFirstChar { it.uppercase() }
    val targetLow = glucoseUnit.format(event.category.defaultTargetLowMgdl.toDouble())
    val targetHigh = glucoseUnit.format(event.category.defaultTargetHighMgdl.toDouble())

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "${event.title} \u00B7 ${durationMin}min",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$dateStr \u00B7 $timeRange",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatChip(
                    label = stringResource(R.string.exercise_planned_category),
                    value = categoryName
                )
                StatChip(
                    label = stringResource(R.string.exercise_planned_target),
                    value = "$targetLow\u2013$targetHigh"
                )
            }
        }
    }
}

@Composable
private fun CompletedTab(
    sessions: List<StoredExerciseSession>,
    glucoseUnit: GlucoseUnit,
    bgLow: Float,
    viewModel: ExerciseHistoryViewModel,
    onSessionClick: (StoredExerciseSession) -> Unit
) {
    if (sessions.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(sessions, key = { it.id }) { session ->
                ExerciseCard(
                    session = session,
                    glucoseUnit = glucoseUnit,
                    bgLow = bgLow,
                    viewModel = viewModel,
                    onClick = { onSessionClick(session) }
                )
            }
        }
    }
}

@Composable
private fun ExerciseCard(
    session: StoredExerciseSession,
    glucoseUnit: GlucoseUnit,
    bgLow: Float,
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
                    ctx.minBG?.let { min ->
                        StatChip(
                            label = stringResource(R.string.exercise_detail_lowest_bg),
                            value = glucoseUnit.format(min),
                            valueColor = if (min < bgLow.toInt()) BelowLow else null
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
private fun PlannedWorkoutSheet(
    state: GuidanceState.WorkoutApproaching,
    glucoseUnit: GlucoseUnit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 48.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                PreActivityCard(state = state, glucoseUnit = glucoseUnit)
            }
        }
    }
}

@Composable
private fun PlannedWorkoutSheet(
    event: WorkoutEvent,
    glucoseUnit: GlucoseUnit,
    onDismiss: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateStr = dateFmt.format(Date(event.startTime))
    val timeRange = "${timeFmt.format(Date(event.startTime))}\u2013${timeFmt.format(Date(event.endTime))}"
    val durationMin = ((event.endTime - event.startTime) / MS_PER_MINUTE).toInt()
    val categoryName = event.category.name.lowercase().replaceFirstChar { it.uppercase() }
    val targetLow = glucoseUnit.format(event.category.defaultTargetLowMgdl.toDouble())
    val targetHigh = glucoseUnit.format(event.category.defaultTargetHighMgdl.toDouble())

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 48.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Text(
                    text = event.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$dateStr \u00B7 $timeRange \u00B7 ${durationMin}min",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatChip(label = stringResource(R.string.exercise_planned_category), value = categoryName)
                    StatChip(label = stringResource(R.string.exercise_planned_target), value = "$targetLow\u2013$targetHigh")
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.exercise_no_bg_data),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
