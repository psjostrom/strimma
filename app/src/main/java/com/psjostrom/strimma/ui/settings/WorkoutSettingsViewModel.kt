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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    fun setLow(mgdl: Float) { viewModelScope.launch { settings.setWorkoutAlertLow(mgdl) } }
    fun setUrgentLow(mgdl: Float) { viewModelScope.launch { settings.setWorkoutAlertUrgentLow(mgdl) } }
    fun setHigh(mgdl: Float) { viewModelScope.launch { settings.setWorkoutAlertHigh(mgdl) } }
    fun setUrgentHigh(mgdl: Float) { viewModelScope.launch { settings.setWorkoutAlertUrgentHigh(mgdl) } }
    fun setMaxHours(hours: Int) { viewModelScope.launch { settings.setWorkoutModeMaxHours(hours) } }

    fun resetToDefaults() {
        viewModelScope.launch {
            settings.setWorkoutAlertLow(DEFAULT_WORKOUT_ALERT_LOW)
            settings.setWorkoutAlertUrgentLow(DEFAULT_WORKOUT_ALERT_URGENT_LOW)
            settings.setWorkoutAlertHigh(DEFAULT_WORKOUT_ALERT_HIGH)
            settings.setWorkoutAlertUrgentHigh(DEFAULT_WORKOUT_ALERT_URGENT_HIGH)
        }
    }
}
