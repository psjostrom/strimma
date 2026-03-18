package com.psjostrom.strimma.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val dao: ReadingDao,
    private val settings: SettingsRepository
) : ViewModel() {

    val latestReading: StateFlow<GlucoseReading?> = dao.latest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val springaUrl: StateFlow<String> = settings.springaUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val graphWindowHours: StateFlow<Int> = settings.graphWindowHours
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 4)

    val bgLow: StateFlow<Float> = settings.bgLow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 4.0f)

    val bgHigh: StateFlow<Float> = settings.bgHigh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 10.0f)

    val apiSecret: String get() = settings.getApiSecret()

    val readings: StateFlow<List<GlucoseReading>> = dao.latest()
        .map { _ ->
            val since = System.currentTimeMillis() - 24 * 3600_000L
            dao.since(since)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSpringaUrl(url: String) = viewModelScope.launch { settings.setSpringaUrl(url) }
    fun setApiSecret(secret: String) = settings.setApiSecret(secret)
    fun setGraphWindowHours(hours: Int) = viewModelScope.launch { settings.setGraphWindowHours(hours) }
    fun setBgLow(value: Float) = viewModelScope.launch { settings.setBgLow(value) }
    fun setBgHigh(value: Float) = viewModelScope.launch { settings.setBgHigh(value) }
}
