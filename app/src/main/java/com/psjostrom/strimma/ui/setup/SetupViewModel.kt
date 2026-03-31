package com.psjostrom.strimma.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.data.GlucoseSource
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val settings: SettingsRepository
) : ViewModel() {

    val glucoseUnit: StateFlow<GlucoseUnit> = settings.glucoseUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GlucoseUnit.MMOL)

    val glucoseSource: StateFlow<GlucoseSource> = settings.glucoseSource
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GlucoseSource.COMPANION)

    val setupStep: StateFlow<Int> = settings.setupStep
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // LibreLinkUp
    val lluEmail: String get() = settings.getLluEmail()
    val lluPassword: String get() = settings.getLluPassword()

    fun setLluEmail(email: String) = settings.setLluEmail(email)
    fun setLluPassword(password: String) = settings.setLluPassword(password)

    fun setGlucoseUnit(unit: GlucoseUnit) = viewModelScope.launch { settings.setGlucoseUnit(unit) }
    fun setGlucoseSource(source: GlucoseSource) = viewModelScope.launch { settings.setGlucoseSource(source) }
    fun setSetupStep(step: Int) = viewModelScope.launch { settings.setSetupStep(step) }
    fun setSetupCompleted() = viewModelScope.launch { settings.setSetupCompleted(true) }
}
