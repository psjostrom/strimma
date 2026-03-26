@file:Suppress("MatchingDeclarationName") // ViewModel is internal to the ExerciseSettings screen

package com.psjostrom.strimma.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.R
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.calendar.CalendarInfo
import com.psjostrom.strimma.data.calendar.CalendarReader
import com.psjostrom.strimma.data.calendar.WorkoutCategory
import com.psjostrom.strimma.data.health.HealthConnectManager
import com.psjostrom.strimma.data.health.HealthConnectStatus
import com.psjostrom.strimma.ui.theme.AboveHigh
import com.psjostrom.strimma.ui.theme.InRange
import com.psjostrom.strimma.ui.theme.Stale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class ExerciseSettingsViewModel @Inject constructor(
    val healthConnectManager: HealthConnectManager,
    private val settings: SettingsRepository,
    val calendarReader: CalendarReader
) : ViewModel() {

    val hcWriteEnabled: StateFlow<Boolean> = settings.hcWriteEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val hcLastSync: StateFlow<Long> = settings.hcLastSync
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _hasPermissions = MutableStateFlow(false)
    val hasPermissions: StateFlow<Boolean> = _hasPermissions

    val workoutCalendarId: StateFlow<Long> = settings.workoutCalendarId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1L)
    val workoutCalendarName: StateFlow<String> = settings.workoutCalendarName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val workoutLookaheadHours: StateFlow<Int> = settings.workoutLookaheadHours
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)
    val workoutTriggerMinutes: StateFlow<Int> = settings.workoutTriggerMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 120)
    val glucoseUnit: StateFlow<GlucoseUnit> = settings.glucoseUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlucoseUnit.MMOL)

    init {
        viewModelScope.launch {
            _hasPermissions.value = healthConnectManager.hasPermissions()
        }
    }

    fun setHcWriteEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setHcWriteEnabled(enabled) }
    }

    fun refreshPermissions() {
        viewModelScope.launch {
            _hasPermissions.value = healthConnectManager.hasPermissions()
        }
    }

    fun setWorkoutCalendar(id: Long, name: String) {
        viewModelScope.launch { settings.setWorkoutCalendarId(id); settings.setWorkoutCalendarName(name) }
    }
    fun clearWorkoutCalendar() {
        viewModelScope.launch { settings.setWorkoutCalendarId(-1L); settings.setWorkoutCalendarName("") }
    }
    fun setWorkoutLookaheadHours(hours: Int) { viewModelScope.launch { settings.setWorkoutLookaheadHours(hours) } }
    fun setWorkoutTriggerMinutes(minutes: Int) { viewModelScope.launch { settings.setWorkoutTriggerMinutes(minutes) } }
    fun setWorkoutTarget(category: WorkoutCategory, low: Float, high: Float) {
        viewModelScope.launch { settings.setWorkoutTarget(category, low, high) }
    }
    fun workoutTargetLow(category: WorkoutCategory) = settings.workoutTargetLow(category)
    fun workoutTargetHigh(category: WorkoutCategory) = settings.workoutTargetHigh(category)
}

@Composable
fun ExerciseSettings(
    onBack: () -> Unit,
    viewModel: ExerciseSettingsViewModel = hiltViewModel()
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline
    val context = LocalContext.current

    val hcStatus = remember { viewModel.healthConnectManager.isAvailable() }
    val hasPermissions by viewModel.hasPermissions.collectAsState()
    val hcWriteEnabled by viewModel.hcWriteEnabled.collectAsState()
    val hcLastSync by viewModel.hcLastSync.collectAsState()

    val workoutCalendarId by viewModel.workoutCalendarId.collectAsState()
    val workoutCalendarName by viewModel.workoutCalendarName.collectAsState()
    val lookaheadHours by viewModel.workoutLookaheadHours.collectAsState()
    val triggerMinutes by viewModel.workoutTriggerMinutes.collectAsState()
    val glucoseUnit by viewModel.glucoseUnit.collectAsState()

    val permissionContract = remember { viewModel.healthConnectManager.createPermissionContract() }
    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) {
        viewModel.refreshPermissions()
    }

    var showCalendarPicker by remember { mutableStateOf(false) }
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showCalendarPicker = true
    }

    SettingsScaffold(title = stringResource(R.string.exercise_settings_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.exercise_hc_status_label)) {
            // HC status row
            val (dotColor, statusText) = when {
                hcStatus == HealthConnectStatus.NOT_SUPPORTED ->
                    Stale to stringResource(R.string.exercise_hc_status_not_supported)
                hcStatus == HealthConnectStatus.NOT_INSTALLED ->
                    Stale to stringResource(R.string.exercise_hc_status_not_installed)
                hasPermissions ->
                    InRange to stringResource(R.string.exercise_hc_status_connected)
                else ->
                    AboveHigh to stringResource(R.string.exercise_hc_status_permissions)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(statusText, color = onBg, fontSize = 14.sp)
            }

            if (hcStatus == HealthConnectStatus.NOT_INSTALLED) {
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                data = "market://details?id=com.google.android.apps.healthdata".toUri()
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.exercise_hc_install_prompt))
                }
            } else if (hcStatus == HealthConnectStatus.AVAILABLE && !hasPermissions) {
                Button(
                    onClick = {
                        permissionLauncher.launch(viewModel.healthConnectManager.permissions)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.exercise_hc_status_permissions))
                }
            }

            // Write toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        stringResource(R.string.exercise_hc_write_toggle),
                        color = onBg,
                        fontSize = 14.sp
                    )
                    Text(
                        stringResource(R.string.exercise_hc_write_subtitle),
                        color = outline,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = hcWriteEnabled,
                    onCheckedChange = viewModel::setHcWriteEnabled,
                    enabled = hcStatus == HealthConnectStatus.AVAILABLE && hasPermissions
                )
            }

            // Last sync
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.exercise_hc_last_sync),
                    color = onBg,
                    fontSize = 14.sp
                )
                val syncText = if (hcLastSync == 0L) {
                    stringResource(R.string.exercise_hc_last_sync_never)
                } else {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(hcLastSync))
                }
                Text(syncText, color = outline, fontSize = 14.sp)
            }
        }

        SettingsSection(stringResource(R.string.workout_settings_title)) {
            // Calendar picker row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (viewModel.calendarReader.hasPermission()) {
                            showCalendarPicker = true
                        } else {
                            calendarPermissionLauncher.launch(android.Manifest.permission.READ_CALENDAR)
                        }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.workout_calendar_label), color = onBg, fontSize = 14.sp)
                Text(
                    workoutCalendarName.ifEmpty { stringResource(R.string.workout_calendar_none) },
                    color = outline,
                    fontSize = 14.sp
                )
            }

            // Lookahead slider
            Column {
                Text("Lookahead: ${lookaheadHours}h", color = onBg, fontSize = 14.sp)
                Slider(
                    value = lookaheadHours.toFloat(),
                    onValueChange = { viewModel.setWorkoutLookaheadHours(it.toInt()) },
                    valueRange = 1f..6f,
                    steps = 4
                )
            }

            // Trigger slider
            Column {
                Text("Guidance trigger: ${triggerMinutes}min before", color = onBg, fontSize = 14.sp)
                Slider(
                    value = triggerMinutes.toFloat(),
                    onValueChange = { viewModel.setWorkoutTriggerMinutes(it.toInt()) },
                    valueRange = 30f..240f,
                    steps = 6
                )
            }

            // Per-category target ranges
            val settableCategories = listOf(
                WorkoutCategory.EASY,
                WorkoutCategory.INTERVAL,
                WorkoutCategory.LONG,
                WorkoutCategory.STRENGTH
            )
            for (category in settableCategories) {
                WorkoutTargetRow(
                    category = category,
                    glucoseUnit = glucoseUnit,
                    viewModel = viewModel,
                    textColor = onBg
                )
            }
        }
    }

    if (showCalendarPicker) {
        var calendars by remember { mutableStateOf(emptyList<CalendarInfo>()) }
        LaunchedEffect(Unit) { calendars = viewModel.calendarReader.getCalendars() }
        AlertDialog(
            onDismissRequest = { showCalendarPicker = false },
            title = { Text(stringResource(R.string.workout_calendar_picker_title)) },
            text = {
                Column {
                    // Option to clear selection
                    if (workoutCalendarId >= 0) {
                        TextButton(onClick = {
                            viewModel.clearWorkoutCalendar()
                            showCalendarPicker = false
                        }) {
                            Text(stringResource(R.string.workout_calendar_none), color = onBg)
                        }
                    }
                    for (cal in calendars) {
                        TextButton(onClick = {
                            viewModel.setWorkoutCalendar(cal.id, cal.displayName)
                            showCalendarPicker = false
                        }) {
                            Text(cal.displayName, color = onBg)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCalendarPicker = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun WorkoutTargetRow(
    category: WorkoutCategory,
    glucoseUnit: GlucoseUnit,
    viewModel: ExerciseSettingsViewModel,
    textColor: androidx.compose.ui.graphics.Color
) {
    val low by viewModel.workoutTargetLow(category).collectAsState(initial = category.defaultTargetLowMgdl)
    val high by viewModel.workoutTargetHigh(category).collectAsState(initial = category.defaultTargetHighMgdl)

    var lowText by remember(low, glucoseUnit) { mutableStateOf(glucoseUnit.formatThreshold(low)) }
    var highText by remember(high, glucoseUnit) { mutableStateOf(glucoseUnit.formatThreshold(high)) }

    Column {
        Text(category.name.lowercase().replaceFirstChar { it.uppercase() }, color = textColor, fontSize = 14.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = lowText,
                onValueChange = { v ->
                    lowText = v
                    glucoseUnit.parseThreshold(v)?.let { parsed ->
                        if (parsed < high) viewModel.setWorkoutTarget(category, parsed, high)
                    }
                },
                label = { Text("Low (${glucoseUnit.label})") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            OutlinedTextField(
                value = highText,
                onValueChange = { v ->
                    highText = v
                    glucoseUnit.parseThreshold(v)?.let { parsed ->
                        if (parsed > low) viewModel.setWorkoutTarget(category, low, parsed)
                    }
                },
                label = { Text("High (${glucoseUnit.label})") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        }
    }
}
