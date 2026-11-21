package com.psjostrom.strimma.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import com.psjostrom.strimma.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.notification.SnoozeDuration
import com.psjostrom.strimma.data.workout.AlertProtocol
import com.psjostrom.strimma.data.workout.AlertSet
import com.psjostrom.strimma.data.workout.NightWindow
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import com.psjostrom.strimma.notification.AlertCategory
import com.psjostrom.strimma.notification.AlertManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

@Suppress("TooManyFunctions") // One getter+setter per alert setting
@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val alertManager: AlertManager,
    workoutModeManager: WorkoutModeManager,
    // Threshold saves run here, not in viewModelScope: a threshold field saves when the screen closes,
    // and the ViewModel is cleared before that save finishes.
    private val appScope: CoroutineScope,
    // Rejections show a toast from here, because a save that runs after the screen closed has no screen to report to.
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val alertLowEnabled: StateFlow<Boolean> = settings.alertLowEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertHighEnabled: StateFlow<Boolean> = settings.alertHighEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertUrgentLowEnabled: StateFlow<Boolean> = settings.alertUrgentLowEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertLow: StateFlow<Float> = settings.alertLow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 72f)
    val alertHigh: StateFlow<Float> = settings.alertHigh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 180f)
    val alertUrgentLow: StateFlow<Float> = settings.alertUrgentLow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 54f)
    val alertUrgentHighEnabled: StateFlow<Boolean> = settings.alertUrgentHighEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertUrgentHigh: StateFlow<Float> = settings.alertUrgentHigh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 234f)
    val alertStaleEnabled: StateFlow<Boolean> = settings.alertStaleEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertLowSoonEnabled: StateFlow<Boolean> = settings.alertLowSoonEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertHighSoonEnabled: StateFlow<Boolean> = settings.alertHighSoonEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val patternAlertsEnabled: StateFlow<Boolean> = settings.patternAlertsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setPatternAlertsEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setPatternAlertsEnabled(enabled)
    }

    val regularAlertProtocol: StateFlow<AlertProtocol?> = settings.regularAlertProtocol
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null
        )

    val exerciseAlertProtocol: StateFlow<AlertProtocol?> = settings.exerciseAlertProtocol
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null
        )

    val nightAlertProtocol: StateFlow<AlertProtocol?> = settings.nightAlertProtocol
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val nightAlertsEnabled: StateFlow<Boolean?> = settings.nightAlertsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val nightWindow: StateFlow<NightWindow?> = settings.nightWindow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The set in force right now, or null until [WorkoutModeManager] has loaded. */
    val activeSet: StateFlow<AlertSet?> = workoutModeManager.effectiveThresholds
        .map { if (it === WorkoutModeManager.PLACEHOLDER_THRESHOLDS) null else it.activeSet }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val alertCooldownMinutes: StateFlow<Int> = settings.alertCooldownMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val alertSnoozeDuration: StateFlow<SnoozeDuration> = settings.alertSnoozeDuration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SnoozeDuration.M30)

    val pauseLowExpiryMs: StateFlow<Long?> = alertManager.pauseLowExpiryMs
    val pauseHighExpiryMs: StateFlow<Long?> = alertManager.pauseHighExpiryMs

    private val dayThresholdMutex = Mutex()
    private val exerciseThresholdMutex = Mutex()
    private val nightThresholdMutex = Mutex()
    private val nightWindowMutex = Mutex()

    /**
     * Single source of truth for "is the active pause unified?". Equal to the shared
     * expiry timestamp when both categories are paused with identical expiries (the
     * state [pauseAllAlerts] produces); null otherwise. Both the header pill and the
     * pause sheet read this so the unified rule lives in exactly one place.
     *
     * Started eagerly so first-render readers (Compose `collectAsState`) see the
     * already-computed value instead of the placeholder `null`. Without this, a user
     * navigating to Main with a persisted unified pause sees a one-frame flash of the
     * per-category pills before the pill collapses to "All alerts paused".
     */
    val unifiedPauseExpiryMs: StateFlow<Long?> = combine(
        alertManager.pauseLowExpiryMs,
        alertManager.pauseHighExpiryMs
    ) { low, high -> if (low != null && low == high) low else null }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            alertManager.pauseLowExpiryMs.value
                ?.takeIf { it == alertManager.pauseHighExpiryMs.value }
        )

    fun setAlertLowEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAlertLowEnabled(enabled) }
    fun setAlertHighEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAlertHighEnabled(enabled) }
    fun setAlertUrgentLowEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAlertUrgentLowEnabled(enabled) }
    fun setAlertLow(value: Float) =
        updateThreshold(settings.regularAlertProtocol, dayThresholdMutex, { it.copy(lowMgdl = value) }) {
            settings.setAlertLow(value)
        }
    fun setAlertHigh(value: Float) =
        updateThreshold(settings.regularAlertProtocol, dayThresholdMutex, { it.copy(highMgdl = value) }) {
            settings.setAlertHigh(value)
        }
    fun setAlertUrgentLow(value: Float) =
        updateThreshold(settings.regularAlertProtocol, dayThresholdMutex, { it.copy(urgentLowMgdl = value) }) {
            settings.setAlertUrgentLow(value)
        }
    fun setAlertUrgentHighEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAlertUrgentHighEnabled(enabled) }
    fun setAlertUrgentHigh(value: Float) =
        updateThreshold(settings.regularAlertProtocol, dayThresholdMutex, { it.copy(urgentHighMgdl = value) }) {
            settings.setAlertUrgentHigh(value)
        }
    fun setAlertStaleEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAlertStaleEnabled(enabled) }
    fun setAlertLowSoonEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAlertLowSoonEnabled(enabled) }
    fun setAlertHighSoonEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAlertHighSoonEnabled(enabled) }
    fun setAlertCooldownMinutes(minutes: Int) = viewModelScope.launch { settings.setAlertCooldownMinutes(minutes) }
    fun setAlertSnoozeDuration(duration: SnoozeDuration) = viewModelScope.launch { settings.setAlertSnoozeDuration(duration) }

    fun setExerciseAlertLowEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setExerciseAlertLowEnabled(enabled) }

    fun setExerciseAlertHighEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setExerciseAlertHighEnabled(enabled) }

    fun setExerciseAlertUrgentLowEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setExerciseAlertUrgentLowEnabled(enabled) }

    fun setExerciseAlertUrgentHighEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setExerciseAlertUrgentHighEnabled(enabled) }

    fun setExerciseAlertLowSoonEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setExerciseAlertLowSoonEnabled(enabled) }

    fun setExerciseAlertHighSoonEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setExerciseAlertHighSoonEnabled(enabled) }

    fun setExerciseAlertStaleEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setExerciseAlertStaleEnabled(enabled) }

    fun setExerciseAlertLow(value: Float) =
        updateThreshold(settings.exerciseAlertProtocol, exerciseThresholdMutex, { it.copy(lowMgdl = value) }) {
            settings.setExerciseAlertLow(value)
        }

    fun setExerciseAlertHigh(value: Float) =
        updateThreshold(settings.exerciseAlertProtocol, exerciseThresholdMutex, { it.copy(highMgdl = value) }) {
            settings.setExerciseAlertHigh(value)
        }

    fun setExerciseAlertUrgentLow(value: Float) =
        updateThreshold(settings.exerciseAlertProtocol, exerciseThresholdMutex, { it.copy(urgentLowMgdl = value) }) {
            settings.setExerciseAlertUrgentLow(value)
        }

    fun setExerciseAlertUrgentHigh(value: Float) =
        updateThreshold(settings.exerciseAlertProtocol, exerciseThresholdMutex, { it.copy(urgentHighMgdl = value) }) {
            settings.setExerciseAlertUrgentHigh(value)
        }

    fun setNightAlertsEnabled(enabled: Boolean) = viewModelScope.launch { settings.setNightAlertsEnabled(enabled) }

    fun setNightWindowStart(minute: Int) = updateNightWindow { it.copy(startMinute = minute) }
    fun setNightWindowEnd(minute: Int) = updateNightWindow { it.copy(endMinute = minute) }

    // Reads the stored window under a mutex so two quick edits cannot overwrite each other's half.
    private fun updateNightWindow(edit: (NightWindow) -> NightWindow): Job = viewModelScope.launch {
        nightWindowMutex.withLock {
            val next = edit(settings.nightWindow.first())
            if (next.startMinute == next.endMinute) {
                showError(R.string.settings_alerts_night_empty_window)
            } else {
                settings.setNightWindow(next)
            }
        }
    }

    fun setNightAlertLowEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setNightAlertLowEnabled(enabled) }

    fun setNightAlertHighEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setNightAlertHighEnabled(enabled) }

    fun setNightAlertUrgentLowEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setNightAlertUrgentLowEnabled(enabled) }

    fun setNightAlertUrgentHighEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setNightAlertUrgentHighEnabled(enabled) }

    fun setNightAlertLowSoonEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setNightAlertLowSoonEnabled(enabled) }

    fun setNightAlertHighSoonEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setNightAlertHighSoonEnabled(enabled) }

    fun setNightAlertStaleEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setNightAlertStaleEnabled(enabled) }

    fun setNightAlertLow(value: Float) =
        updateThreshold(settings.nightAlertProtocol, nightThresholdMutex, { it.copy(lowMgdl = value) }) {
            settings.setNightAlertLow(value)
        }

    fun setNightAlertHigh(value: Float) =
        updateThreshold(settings.nightAlertProtocol, nightThresholdMutex, { it.copy(highMgdl = value) }) {
            settings.setNightAlertHigh(value)
        }

    fun setNightAlertUrgentLow(value: Float) =
        updateThreshold(settings.nightAlertProtocol, nightThresholdMutex, { it.copy(urgentLowMgdl = value) }) {
            settings.setNightAlertUrgentLow(value)
        }

    fun setNightAlertUrgentHigh(value: Float) =
        updateThreshold(settings.nightAlertProtocol, nightThresholdMutex, { it.copy(urgentHighMgdl = value) }) {
            settings.setNightAlertUrgentHigh(value)
        }

    private fun updateThreshold(
        protocol: Flow<AlertProtocol>,
        mutex: Mutex,
        change: (AlertProtocol) -> AlertProtocol,
        onValid: suspend () -> Unit,
    ): Job = appScope.launch {
        mutex.withLock {
            val current = protocol.first()
            if (change(current).keepsOrderOf(current)) {
                onValid()
            } else {
                showError(R.string.alert_threshold_order_error)
            }
        }
    }

    fun openAlertChannelSettings(channelId: String) = alertManager.openChannelSettings(channelId)

    fun pauseAlerts(category: AlertCategory, durationMs: Long) {
        alertManager.pauseAlertCategory(category, durationMs)
    }

    fun pauseAllAlerts(durationMs: Long) {
        alertManager.pauseAllAlerts(durationMs)
    }

    fun cancelAlertPause(category: AlertCategory) {
        alertManager.cancelAlertPause(category)
    }

    fun cancelAllAlertPauses() {
        alertManager.cancelAllAlerts()
    }

    private suspend fun showError(@StringRes message: Int) = withContext(Dispatchers.Main) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
