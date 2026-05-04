package com.psjostrom.strimma.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.notification.AlertCategory
import com.psjostrom.strimma.notification.AlertManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    val pauseLowExpiryMs: StateFlow<Long?> = alertManager.pauseLowExpiryMs
    val pauseHighExpiryMs: StateFlow<Long?> = alertManager.pauseHighExpiryMs

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
}
