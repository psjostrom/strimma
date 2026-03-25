package com.psjostrom.strimma.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.data.GlucoseSource
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.network.NightscoutClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Suppress("TooManyFunctions") // One getter+setter per setting
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val nightscoutClient: NightscoutClient
) : ViewModel() {

    val glucoseUnit: StateFlow<GlucoseUnit> = settings.glucoseUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GlucoseUnit.MMOL)

    val glucoseSource: StateFlow<GlucoseSource> = settings.glucoseSource
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GlucoseSource.COMPANION)

    val setupStep: StateFlow<Int> = settings.setupStep
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Nightscout push
    val nightscoutUrl: StateFlow<String> = settings.nightscoutUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val nightscoutSecret: String get() = settings.getNightscoutSecret()

    private val _pushEnabled = MutableStateFlow(false)
    val pushEnabled: StateFlow<Boolean> = _pushEnabled.asStateFlow()

    private val _connectionTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val connectionTestState: StateFlow<ConnectionTestState> = _connectionTestState.asStateFlow()

    // Follower (inline in step 2 when NIGHTSCOUT_FOLLOWER selected)
    val followerUrl: StateFlow<String> = settings.followerUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val followerSecret: String get() = settings.getFollowerSecret()

    private val _followerTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val followerTestState: StateFlow<ConnectionTestState> = _followerTestState.asStateFlow()

    // Alert settings
    val alertLowEnabled: StateFlow<Boolean> = settings.alertLowEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertHighEnabled: StateFlow<Boolean> = settings.alertHighEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertUrgentLowEnabled: StateFlow<Boolean> = settings.alertUrgentLowEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertUrgentHighEnabled: StateFlow<Boolean> = settings.alertUrgentHighEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertLow: StateFlow<Float> = settings.alertLow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 72f)
    val alertHigh: StateFlow<Float> = settings.alertHigh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 180f)
    val alertUrgentLow: StateFlow<Float> = settings.alertUrgentLow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 54f)
    val alertUrgentHigh: StateFlow<Float> = settings.alertUrgentHigh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 234f)
    val alertStaleEnabled: StateFlow<Boolean> = settings.alertStaleEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertLowSoonEnabled: StateFlow<Boolean> = settings.alertLowSoonEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val alertHighSoonEnabled: StateFlow<Boolean> = settings.alertHighSoonEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setGlucoseUnit(unit: GlucoseUnit) = viewModelScope.launch { settings.setGlucoseUnit(unit) }
    fun setGlucoseSource(source: GlucoseSource) = viewModelScope.launch { settings.setGlucoseSource(source) }
    fun setSetupStep(step: Int) = viewModelScope.launch { settings.setSetupStep(step) }
    fun setSetupCompleted() = viewModelScope.launch { settings.setSetupCompleted(true) }

    fun setNightscoutUrl(url: String) = viewModelScope.launch { settings.setNightscoutUrl(url) }
    fun setNightscoutSecret(secret: String) = settings.setNightscoutSecret(secret)
    fun setPushEnabled(enabled: Boolean) {
        _pushEnabled.value = enabled
        if (!enabled) {
            _connectionTestState.value = ConnectionTestState.Idle
            // Clear credentials so NightscoutPusher won't push
            viewModelScope.launch { settings.setNightscoutUrl("") }
            settings.setNightscoutSecret("")
        }
    }

    fun setFollowerUrl(url: String) = viewModelScope.launch { settings.setFollowerUrl(url) }
    fun setFollowerSecret(secret: String) = settings.setFollowerSecret(secret)

    fun testConnection() {
        viewModelScope.launch {
            _connectionTestState.value = ConnectionTestState.Testing
            val result = nightscoutClient.testConnection(nightscoutUrl.value, settings.getNightscoutSecret())
            _connectionTestState.value = if (result.success) {
                ConnectionTestState.Success(result.serverName)
            } else {
                ConnectionTestState.Failed(result.error ?: "Unknown error")
            }
        }
    }

    fun testFollowerConnection() {
        viewModelScope.launch {
            _followerTestState.value = ConnectionTestState.Testing
            val result = nightscoutClient.testConnection(followerUrl.value, settings.getFollowerSecret())
            _followerTestState.value = if (result.success) {
                ConnectionTestState.Success(result.serverName)
            } else {
                ConnectionTestState.Failed(result.error ?: "Unknown error")
            }
        }
    }

    // Alert setters
    fun setAlertLowEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAlertLowEnabled(enabled) }
    fun setAlertHighEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAlertHighEnabled(enabled) }
    fun setAlertUrgentLowEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAlertUrgentLowEnabled(enabled) }
    fun setAlertUrgentHighEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAlertUrgentHighEnabled(enabled) }
    fun setAlertLow(value: Float) = viewModelScope.launch { settings.setAlertLow(value) }
    fun setAlertHigh(value: Float) = viewModelScope.launch { settings.setAlertHigh(value) }
    fun setAlertUrgentLow(value: Float) = viewModelScope.launch { settings.setAlertUrgentLow(value) }
    fun setAlertUrgentHigh(value: Float) = viewModelScope.launch { settings.setAlertUrgentHigh(value) }
    fun setAlertStaleEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAlertStaleEnabled(enabled) }
    fun setAlertLowSoonEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAlertLowSoonEnabled(enabled) }
    fun setAlertHighSoonEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAlertHighSoonEnabled(enabled) }
}

sealed class ConnectionTestState {
    data object Idle : ConnectionTestState()
    data object Testing : ConnectionTestState()
    data class Success(val serverName: String?) : ConnectionTestState()
    data class Failed(val error: String) : ConnectionTestState()
}
