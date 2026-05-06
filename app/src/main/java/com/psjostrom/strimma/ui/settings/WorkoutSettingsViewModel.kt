package com.psjostrom.strimma.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_WORKOUT_ALERT_HIGH
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_WORKOUT_ALERT_LOW
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_WORKOUT_ALERT_URGENT_HIGH
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_WORKOUT_ALERT_URGENT_LOW
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_WORKOUT_MODE_MAX_HOURS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkoutSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository
) : ViewModel() {

    val workoutLow: StateFlow<Float> = settings.workoutAlertLow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_WORKOUT_ALERT_LOW)
    val workoutUrgentLow: StateFlow<Float> = settings.workoutAlertUrgentLow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_WORKOUT_ALERT_URGENT_LOW)
    val workoutHigh: StateFlow<Float> = settings.workoutAlertHigh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_WORKOUT_ALERT_HIGH)
    val workoutUrgentHigh: StateFlow<Float> = settings.workoutAlertUrgentHigh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_WORKOUT_ALERT_URGENT_HIGH)
    val maxHours: StateFlow<Int> = settings.workoutModeMaxHours
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_WORKOUT_MODE_MAX_HOURS)

    /**
     * Validation errors are surfaced through this SharedFlow rather than through
     * a StateFlow because they're transient one-shot events the UI should toast
     * once and forget — a StateFlow would re-fire the error on every recomposition.
     */
    private val _validationError = MutableSharedFlow<ValidationError>(extraBufferCapacity = 1)
    val validationError: SharedFlow<ValidationError> = _validationError

    fun setLow(mgdl: Float) = updateWithOrderingCheck {
        if (!isOrderValid(low = mgdl)) ValidationError.Order
        else { settings.setWorkoutAlertLow(mgdl); null }
    }

    fun setUrgentLow(mgdl: Float) = updateWithOrderingCheck {
        if (!isOrderValid(urgentLow = mgdl)) ValidationError.Order
        else { settings.setWorkoutAlertUrgentLow(mgdl); null }
    }

    fun setHigh(mgdl: Float) = updateWithOrderingCheck {
        if (!isOrderValid(high = mgdl)) ValidationError.Order
        else { settings.setWorkoutAlertHigh(mgdl); null }
    }

    fun setUrgentHigh(mgdl: Float) = updateWithOrderingCheck {
        if (!isOrderValid(urgentHigh = mgdl)) ValidationError.Order
        else { settings.setWorkoutAlertUrgentHigh(mgdl); null }
    }

    fun setMaxHours(hours: Int) { viewModelScope.launch { settings.setWorkoutModeMaxHours(hours) } }

    /**
     * Resets the four threshold values to their defaults but **deliberately leaves
     * `maxHours` untouched** — the user's auto-off preference is independent of
     * their threshold preferences and is set once based on workout duration habits.
     * Wiping it on every "reset thresholds" tap would be a destructive surprise.
     */
    fun resetToDefaults() {
        viewModelScope.launch {
            settings.setWorkoutAlertLow(DEFAULT_WORKOUT_ALERT_LOW)
            settings.setWorkoutAlertUrgentLow(DEFAULT_WORKOUT_ALERT_URGENT_LOW)
            settings.setWorkoutAlertHigh(DEFAULT_WORKOUT_ALERT_HIGH)
            settings.setWorkoutAlertUrgentHigh(DEFAULT_WORKOUT_ALERT_URGENT_HIGH)
        }
    }

    /**
     * Verify the proposed change keeps invariant `urgent_low ≤ low ≤ high ≤ urgent_high`.
     * Reads the *current* persisted values for the unchanged fields and substitutes the
     * proposed value for the field being edited. Any null parameter falls back to the
     * current persisted value.
     */
    private suspend fun isOrderValid(
        urgentLow: Float? = null,
        low: Float? = null,
        high: Float? = null,
        urgentHigh: Float? = null,
    ): Boolean {
        val ul = urgentLow ?: settings.workoutAlertUrgentLow.first()
        val l = low ?: settings.workoutAlertLow.first()
        val h = high ?: settings.workoutAlertHigh.first()
        val uh = urgentHigh ?: settings.workoutAlertUrgentHigh.first()
        return ul <= l && l <= h && h <= uh
    }

    private fun updateWithOrderingCheck(action: suspend () -> ValidationError?) {
        viewModelScope.launch {
            val error = action()
            if (error != null) _validationError.tryEmit(error)
        }
    }

    enum class ValidationError { Order }
}
