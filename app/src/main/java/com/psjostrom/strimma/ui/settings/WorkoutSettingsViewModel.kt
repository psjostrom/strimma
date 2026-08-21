package com.psjostrom.strimma.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.data.SettingsRepository
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

    val maxHours: StateFlow<Int> = settings.workoutModeMaxHours
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_WORKOUT_MODE_MAX_HOURS)

    fun setMaxHours(hours: Int) { viewModelScope.launch { settings.setWorkoutModeMaxHours(hours) } }
}
