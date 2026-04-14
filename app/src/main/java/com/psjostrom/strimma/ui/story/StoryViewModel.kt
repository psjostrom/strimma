package com.psjostrom.strimma.ui.story

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.data.IOBComputer
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.TreatmentDao
import com.psjostrom.strimma.data.meal.MealAnalyzer
import com.psjostrom.strimma.data.meal.MealTimeSlotConfig
import com.psjostrom.strimma.data.story.StoryComputer
import com.psjostrom.strimma.data.story.StoryData
import com.psjostrom.strimma.data.story.toMillisRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class StoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val readingDao: ReadingDao,
    private val treatmentDao: TreatmentDao,
    private val settings: SettingsRepository,
    private val mealAnalyzer: MealAnalyzer
) : ViewModel() {

    private val year: Int = savedStateHandle["year"] ?: YearMonth.now().minusMonths(1).year
    private val month: Int = savedStateHandle["month"] ?: YearMonth.now().minusMonths(1).monthValue

    private val _story = MutableStateFlow<StoryData?>(null)
    val story: StateFlow<StoryData?> = _story

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        viewModelScope.launch { loadStory() }
    }

    @Suppress("TooGenericExceptionCaught") // Multiple data sources — DB, DataStore, computation
    private suspend fun loadStory() {
        try {
            _loading.value = true
            _error.value = null
            val zone = ZoneId.systemDefault()
            val currentMonth = YearMonth.of(year, month)
            val prevMonth = currentMonth.minusMonths(1)

            val (curStart, curEnd) = currentMonth.toMillisRange(zone)
            val (prevStart, prevEnd) = prevMonth.toMillisRange(zone)

            val readings = readingDao.readingsInRange(curStart, curEnd)
            val prevReadings = readingDao.readingsInRange(prevStart, prevEnd)
            val carbTreatments = treatmentDao.carbsInRange(curStart, curEnd)
            val allTreatments = treatmentDao.allSince(curStart)

            val bgLow = settings.bgLow.first()
            val bgHigh = settings.bgHigh.first()
            val insulinType = settings.insulinType.first()
            val customDIA = settings.customDIA.first()
            val tauMinutes = IOBComputer.tauForInsulinType(insulinType, customDIA)

            val mealConfig = MealTimeSlotConfig(
                breakfastStart = settings.mealBreakfastStart.first(),
                breakfastEnd = settings.mealBreakfastEnd.first(),
                lunchStart = settings.mealLunchStart.first(),
                lunchEnd = settings.mealLunchEnd.first(),
                dinnerStart = settings.mealDinnerStart.first(),
                dinnerEnd = settings.mealDinnerEnd.first()
            )

            val result = StoryComputer.compute(
                month = currentMonth,
                readings = readings,
                previousReadings = prevReadings,
                carbTreatments = carbTreatments,
                allTreatments = allTreatments,
                bgLow = bgLow.toDouble(),
                bgHigh = bgHigh.toDouble(),
                tauMinutes = tauMinutes,
                zone = zone,
                mealAnalyzer = mealAnalyzer,
                mealTimeSlotConfig = mealConfig
            )
            _story.value = result
            // DEV: reset viewed flag so card always shows during development
            settings.setStoryViewedMonth("")
        } catch (e: Exception) {
            _error.value = e.message
        } finally {
            _loading.value = false
        }
    }
}
