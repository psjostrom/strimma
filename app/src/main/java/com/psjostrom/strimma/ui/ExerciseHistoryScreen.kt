@file:Suppress("MatchingDeclarationName") // ViewModel is internal to the ExerciseHistoryScreen

package com.psjostrom.strimma.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.MS_PER_DAY
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.TreatmentDao
import com.psjostrom.strimma.data.fetchCurrentIOB
import com.psjostrom.strimma.data.calendar.CalendarReader
import com.psjostrom.strimma.data.calendar.GuidanceState
import com.psjostrom.strimma.data.calendar.ReadinessLevel
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import com.psjostrom.strimma.data.health.ExerciseBGAnalyzer
import com.psjostrom.strimma.data.health.BGBand
import com.psjostrom.strimma.data.health.CategoryStats
import com.psjostrom.strimma.data.health.CategoryStatsCalculator
import com.psjostrom.strimma.data.health.ExerciseBGContext
import com.psjostrom.strimma.data.health.ExerciseCategory
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.data.health.StoredExerciseSession
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.BelowLow
import com.psjostrom.strimma.ui.theme.InRange
import com.psjostrom.strimma.ui.theme.LightTintDanger
import com.psjostrom.strimma.ui.theme.LightTintInRange
import com.psjostrom.strimma.ui.theme.LightTintWarning
import com.psjostrom.strimma.ui.theme.TintDanger
import com.psjostrom.strimma.ui.theme.TintInRange
import com.psjostrom.strimma.ui.theme.TintWarning
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

private val PRE_WINDOW_MS = 30 * MS_PER_MINUTE
private val POST_WINDOW_MS = 4 * 60 * MS_PER_MINUTE
private const val PLANNED_LOOKAHEAD_DAYS = 365
private val PLANNED_POLL_MS = 5 * MS_PER_MINUTE
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

    val bgHigh: StateFlow<Float> = settings.bgHigh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 180f)

    val maxHeartRate: StateFlow<Int?> = settings.maxHeartRate
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

    suspend fun computeAllBGContexts(): List<Pair<StoredExerciseSession, ExerciseBGContext>> {
        val allSessions = exerciseDao.getAllSessionsList()
        return allSessions.mapNotNull { session ->
            computeBGContext(session)?.let { ctx -> session to ctx }
        }
    }

    suspend fun computePlannedGuidance(event: WorkoutEvent): GuidanceState {
        val now = System.currentTimeMillis()
        val readings = readingDao.readingsInRange(now - MS_PER_DAY, now)
        val latest = readings.maxByOrNull { it.ts }
        val iob = fetchCurrentIOB(settings, treatmentDao, now)
        val targetLow = settings.exerciseTargetLow(event.category).first()
        val targetHigh = settings.exerciseTargetHigh(event.category).first()

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
    onBack: (() -> Unit)? = null,
    onNavigateToExerciseSettings: () -> Unit = {},
    viewModel: ExerciseHistoryViewModel = hiltViewModel()
) {
    val sessions by viewModel.sessions.collectAsState()
    val glucoseUnit by viewModel.glucoseUnit.collectAsState()
    val bgLow by viewModel.bgLow.collectAsState()
    val bgHigh by viewModel.bgHigh.collectAsState()
    val calendarId by viewModel.workoutCalendarId.collectAsState()
    val upcomingWorkouts by viewModel.upcomingWorkouts.collectAsState()

    val tabs = listOf(
        stringResource(R.string.exercise_tab_planned),
        stringResource(R.string.exercise_tab_completed),
        stringResource(R.string.exercise_tab_patterns)
    )
    val pagerState = rememberPagerState { tabs.size }

    var selectedExercise by remember { mutableStateOf<StoredExerciseSession?>(null) }
    var selectedBGContext by remember { mutableStateOf<ExerciseBGContext?>(null) }
    var selectedReadings by remember { mutableStateOf<List<GlucoseReading>>(emptyList()) }
    var bgContextLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(selectedExercise) {
        val session = selectedExercise
        if (session != null) {
            bgContextLoaded = false
            selectedBGContext = viewModel.computeBGContext(session)
            selectedReadings = viewModel.getSparklineReadings(session)
            bgContextLoaded = true
        } else {
            selectedBGContext = null
            selectedReadings = emptyList()
            bgContextLoaded = false
        }
    }

    if (selectedExercise != null && bgContextLoaded) {
        ExerciseDetailSheet(
            session = selectedExercise!!,
            bgContext = selectedBGContext,
            readings = selectedReadings,
            glucoseUnit = glucoseUnit,
            bgLow = bgLow.toDouble(),
            bgHigh = bgHigh.toDouble(),
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
        PlannedWorkoutSheet(
            event = selectedPlannedWorkout!!,
            guidance = plannedGuidance,
            glucoseUnit = glucoseUnit,
            onDismiss = { selectedPlannedWorkout = null }
        )
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
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_content_desc_back)
                            )
                        }
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
                        onSessionClick = { selectedExercise = it },
                        onConnectHealthConnect = onNavigateToExerciseSettings
                    )
                    2 -> PatternsTab(viewModel = viewModel, glucoseUnit = glucoseUnit)
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.exercise_planned_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.weight(1f))
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
    val categoryName = event.category.displayName
    val targetLow = glucoseUnit.format(event.metabolicProfile.defaultTargetLowMgdl.toDouble())
    val targetHigh = glucoseUnit.format(event.metabolicProfile.defaultTargetHighMgdl.toDouble())

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
    onSessionClick: (StoredExerciseSession) -> Unit,
    onConnectHealthConnect: () -> Unit
) {
    if (sessions.isEmpty()) {
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
                        text = stringResource(R.string.exercise_history_empty_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.exercise_history_empty),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onConnectHealthConnect,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.exercise_history_empty_button))
                    }
                }
            }
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

            // Subtitle: date + time range + metabolic profile
            val maxHR by viewModel.maxHeartRate.collectAsState()
            val profileName = bgContext?.let { ctx ->
                val profile = CategoryStatsCalculator.resolveProfile(session, ctx, maxHR)
                profile.displayName
            }
            val subtitle = buildString {
                append("$dateStr \u00B7 $timeRange")
                profileName?.let { append(" \u00B7 $it") }
            }
            Text(
                text = subtitle,
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
internal fun PlannedWorkoutSheet(
    event: WorkoutEvent,
    guidance: GuidanceState?,
    glucoseUnit: GlucoseUnit,
    onDismiss: () -> Unit
) {
    StrimmaBottomSheet(onDismiss = onDismiss) {

        if (guidance is GuidanceState.WorkoutApproaching) {
            // Guidance in a tinted inner card
            val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val (tintBg, badgeColor, badgeText) = when (guidance.readiness) {
                ReadinessLevel.READY -> Triple(if (isDark) TintInRange else LightTintInRange, InRange, "READY")
                ReadinessLevel.CAUTION -> Triple(if (isDark) TintWarning else LightTintWarning, AboveHigh, "HEADS UP")
                ReadinessLevel.WAIT -> Triple(if (isDark) TintDanger else LightTintDanger, BelowLow, "HOLD ON")
            }
            val timeText = formatTimeUntil(event.startTime - System.currentTimeMillis())
            val targetLow = glucoseUnit.format(guidance.targetLowMgdl.toDouble())
            val targetHigh = glucoseUnit.format(guidance.targetHighMgdl.toDouble())

            Text(
                text = "${event.title} in $timeText",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = tintBg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Target: $targetLow\u2013$targetHigh ${glucoseUnit.label}",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 13.sp
                    )
                    val currentLine = buildString {
                        append("Current: ${glucoseUnit.format(guidance.currentBgMgdl)} ${guidance.trendArrow}")
                        if (guidance.iob > 0.0) append("  \u00B7  IOB ${"%.1f".format(guidance.iob)}u")
                    }
                    Text(currentLine, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp)

                    for (reason in guidance.reasons) {
                        Spacer(Modifier.height(4.dp))
                        Text(reason.message, color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
                    }
                    if (guidance.suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        for (suggestion in guidance.suggestions) {
                            Text(
                                text = suggestion,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        } else {
            val dateFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
            val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
            val dateStr = dateFmt.format(Date(event.startTime))
            val timeRange = "${timeFmt.format(Date(event.startTime))}\u2013${timeFmt.format(Date(event.endTime))}"
            val durationMin = ((event.endTime - event.startTime) / MS_PER_MINUTE).toInt()
            val categoryName = event.category.displayName
            val targetLow = glucoseUnit.format(event.metabolicProfile.defaultTargetLowMgdl.toDouble())
            val targetHigh = glucoseUnit.format(event.metabolicProfile.defaultTargetHighMgdl.toDouble())

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatternsTab(
    viewModel: ExerciseHistoryViewModel,
    glucoseUnit: GlucoseUnit
) {
    val bgLow by viewModel.bgLow.collectAsState()
    val maxHR by viewModel.maxHeartRate.collectAsState()

    var selectedIndex by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var allData by remember {
        mutableStateOf<List<Pair<StoredExerciseSession, ExerciseBGContext>>>(emptyList())
    }

    LaunchedEffect(Unit) {
        allData = viewModel.computeAllBGContexts()
        isLoading = false
    }

    val categories = remember(allData, bgLow, maxHR, selectedIndex) {
        if (allData.isEmpty()) emptyList()
        else if (selectedIndex == 0) {
            CategoryStatsCalculator.computeByCategory(allData, bgLow.toDouble())
        } else {
            CategoryStatsCalculator.computeByProfile(allData, bgLow.toDouble(), maxHR)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val options = listOf(
                stringResource(R.string.exercise_patterns_by_activity),
                stringResource(R.string.exercise_patterns_by_profile)
            )
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = selectedIndex == index,
                    onClick = { selectedIndex = index },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    )
                ) {
                    Text(label, fontSize = 13.sp)
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (categories.isEmpty()) {
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
                            text = stringResource(R.string.exercise_patterns_empty_title),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.exercise_patterns_empty),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(categories, key = { "${it.category}_${it.metabolicProfile}" }) { stats ->
                    PatternCard(
                        stats = stats,
                        isProfileView = selectedIndex == 1,
                        glucoseUnit = glucoseUnit
                    )
                }
            }
        }
    }
}

private const val HYPO_SOME_RISK_THRESHOLD = 0.10
private const val HYPO_HIGH_RISK_THRESHOLD = 0.25
private const val BAND_HYPO_WARNING_THRESHOLD = 0.20

@Composable
private fun PatternCard(
    stats: CategoryStats,
    isProfileView: Boolean,
    glucoseUnit: GlucoseUnit
) {
    val title = if (isProfileView) {
        val profile = stats.metabolicProfile
        profile?.name?.lowercase()?.replaceFirstChar { it.uppercase() }?.replace('_', ' ')
            ?: "Unknown"
    } else {
        "${stats.category.emoji} ${stats.category.displayName}"
    }

    // Risk badge
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (badgeText, badgeColor, badgeBg) = when {
        stats.hypoRate >= HYPO_HIGH_RISK_THRESHOLD -> Triple(
            stringResource(R.string.exercise_patterns_high_risk), BelowLow, if (isDark) TintDanger else LightTintDanger
        )
        stats.hypoRate >= HYPO_SOME_RISK_THRESHOLD -> Triple(
            stringResource(R.string.exercise_patterns_some_risk), AboveHigh, if (isDark) TintWarning else LightTintWarning
        )
        else -> Triple(
            stringResource(R.string.exercise_patterns_low_risk), InRange, if (isDark) TintInRange else LightTintInRange
        )
    }

    // Total drop: entry BG → lowest during exercise
    val totalDrop = stats.avgEntryBG - stats.avgMinBG

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: title + session count + risk badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(100),
                    color = badgeBg
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }

            // Session count
            Text(
                text = "${stats.sessionCount} sessions",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))

            // Hero: total drop in plain language
            if (totalDrop > 0) {
                Text(
                    text = stringResource(
                        R.string.exercise_patterns_drop_summary,
                        glucoseUnit.format(totalDrop)
                    ),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Hypo summary in natural frequencies
            if (stats.hypoCount > 0) {
                Text(
                    text = stringResource(
                        R.string.exercise_patterns_went_low,
                        stats.hypoCount,
                        stats.sessionCount
                    ),
                    fontSize = 14.sp,
                    color = if (stats.hypoRate >= HYPO_HIGH_RISK_THRESHOLD) BelowLow
                        else MaterialTheme.colorScheme.onSurface
                )
                // Actionable tip based on risk level
                if (stats.hypoRate >= HYPO_HIGH_RISK_THRESHOLD) {
                    Text(
                        text = stringResource(R.string.exercise_patterns_tip_high_risk),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (stats.hypoRate >= HYPO_SOME_RISK_THRESHOLD) {
                    Text(
                        text = stringResource(R.string.exercise_patterns_tip_some_risk),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.exercise_patterns_never_low),
                    fontSize = 14.sp,
                    color = InRange
                )
            }

            // Entry BG band breakdown
            if (stats.statsByEntryBand.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))

                for ((band, bandStats) in stats.statsByEntryBand.entries.sortedBy { it.key.ordinal }) {
                    val bandHypoPercent = (bandStats.hypoRate * 100).toInt()
                    val hasRisk = bandStats.hypoRate >= BAND_HYPO_WARNING_THRESHOLD
                    val bandColor = when (band) {
                        BGBand.LOW -> BelowLow
                        BGBand.LOW_RANGE -> BelowLow.copy(alpha = 0.7f)
                        BGBand.MID_RANGE -> InRange
                        BGBand.HIGH -> AboveHigh
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Colored dot
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(bandColor, RoundedCornerShape(100))
                        )
                        Spacer(Modifier.width(8.dp))
                        // Band label + count
                        Text(
                            text = "${band.label} (${bandStats.sessionCount})",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        // Outcome
                        if (bandHypoPercent > 0) {
                            Text(
                                text = "low $bandHypoPercent%",
                                fontSize = 13.sp,
                                fontWeight = if (hasRisk) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (hasRisk) BelowLow else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = "\u2713",
                                fontSize = 13.sp,
                                color = InRange
                            )
                        }
                    }
                }
            }
        }
    }
}
