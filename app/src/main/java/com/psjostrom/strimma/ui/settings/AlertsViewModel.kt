package com.psjostrom.strimma.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.notification.SnoozeDuration
import com.psjostrom.strimma.data.workout.AlertProtocol
import com.psjostrom.strimma.notification.AlertCategory
import com.psjostrom.strimma.notification.AlertManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@Suppress("TooManyFunctions") // One getter+setter per alert setting
@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val alertManager: AlertManager
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

    val alertCooldownMinutes: StateFlow<Int> = settings.alertCooldownMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val alertSnoozeDuration: StateFlow<SnoozeDuration> = settings.alertSnoozeDuration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SnoozeDuration.M30)

    val pauseLowExpiryMs: StateFlow<Long?> = alertManager.pauseLowExpiryMs
    val pauseHighExpiryMs: StateFlow<Long?> = alertManager.pauseHighExpiryMs

    private val _validationError = MutableSharedFlow<ValidationError>(extraBufferCapacity = 1)
    val validationError: SharedFlow<ValidationError> = _validationError
    private val exerciseThresholdMutex = Mutex()

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
    fun setAlertLow(value: Float) = viewModelScope.launch { settings.setAlertLow(value) }
    fun setAlertHigh(value: Float) = viewModelScope.launch { settings.setAlertHigh(value) }
    fun setAlertUrgentLow(value: Float) = viewModelScope.launch { settings.setAlertUrgentLow(value) }
    fun setAlertUrgentHighEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAlertUrgentHighEnabled(enabled) }
    fun setAlertUrgentHigh(value: Float) = viewModelScope.launch { settings.setAlertUrgentHigh(value) }
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

    fun setExerciseAlertLow(value: Float) = updateExerciseThreshold(low = value) {
        settings.setExerciseAlertLow(value)
    }

    fun setExerciseAlertHigh(value: Float) = updateExerciseThreshold(high = value) {
        settings.setExerciseAlertHigh(value)
    }

    fun setExerciseAlertUrgentLow(value: Float) = updateExerciseThreshold(urgentLow = value) {
        settings.setExerciseAlertUrgentLow(value)
    }

    fun setExerciseAlertUrgentHigh(value: Float) = updateExerciseThreshold(urgentHigh = value) {
        settings.setExerciseAlertUrgentHigh(value)
    }

    private fun updateExerciseThreshold(
        urgentLow: Float? = null,
        low: Float? = null,
        high: Float? = null,
        urgentHigh: Float? = null,
        onValid: suspend () -> Unit,
    ): Job = viewModelScope.launch {
        exerciseThresholdMutex.withLock {
            val current = settings.exerciseAlertProtocol.first()
            val nextUrgentLow = urgentLow ?: current.urgentLowMgdl
            val nextLow = low ?: current.lowMgdl
            val nextHigh = high ?: current.highMgdl
            val nextUrgentHigh = urgentHigh ?: current.urgentHighMgdl
            if (nextUrgentLow <= nextLow && nextLow <= nextHigh && nextHigh <= nextUrgentHigh) {
                onValid()
            } else {
                _validationError.tryEmit(ValidationError.Order)
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

    enum class ValidationError { Order }
}
