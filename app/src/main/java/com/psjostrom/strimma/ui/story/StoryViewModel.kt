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
import com.psjostrom.strimma.data.story.StoryParams
import com.psjostrom.strimma.data.story.toMillisRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/**
 * Historical monthly analysis. **Reads thresholds from SettingsRepository directly,
 * NOT from WorkoutModeManager.effectiveThresholds** — Story computes TIR / AGP /
 * meal stats over a window that long predates the user's current workout-mode
 * state, and presenting last-month's TIR against today's transient workout-mode
 * thresholds would silently corrupt the analysis.
 *
 * Holds the displayed month as mutable state so the user can navigate to other
 * months via prev/next without leaving the screen. Bounds are: the month of the
 * earliest reading on disk on the lower end, and the last completed month on
 * the upper end (the current in-progress month is hidden because partial-month
 * stats are misleading — same rule as the Stats > Story entry card).
 */
@HiltViewModel
class StoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val readingDao: ReadingDao,
    private val treatmentDao: TreatmentDao,
    private val settings: SettingsRepository,
    private val mealAnalyzer: MealAnalyzer,
) : ViewModel() {

    private val zone = ZoneId.systemDefault()
    private val initialYear: Int = savedStateHandle["year"] ?: YearMonth.now().minusMonths(1).year
    private val initialMonth: Int = savedStateHandle["month"] ?: YearMonth.now().minusMonths(1).monthValue

    private val _currentMonth = MutableStateFlow(YearMonth.of(initialYear, initialMonth))
    val currentMonth: StateFlow<YearMonth> = _currentMonth

    // Lower bound is recomputed when the on-disk minimum changes (e.g. after a
    // pull from Nightscout backfills older history). Upper bound never moves
    // mid-session — the screen's lifetime is short enough.
    private val _earliestMonth = MutableStateFlow<YearMonth?>(null)
    private val latestMonth: YearMonth = YearMonth.now().minusMonths(1)

    val canGoBack: StateFlow<Boolean> = combine(_currentMonth, _earliestMonth) { current, earliest ->
        earliest != null && current.isAfter(earliest)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val canGoForward: StateFlow<Boolean> = _currentMonth
        .map { it.isBefore(latestMonth) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _story = MutableStateFlow<StoryData?>(null)
    val story: StateFlow<StoryData?> = _story

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        viewModelScope.launch {
            // Best-effort: a DB error here just leaves earliestMonth null, which
            // disables the back arrow. The Story load itself owns its own error
            // surface and must always run so _loading flips off.
            @Suppress("TooGenericExceptionCaught")
            try {
                readingDao.earliestTs()?.let { ts ->
                    _earliestMonth.value = YearMonth.from(Instant.ofEpochMilli(ts).atZone(zone))
                }
            } catch (_: Exception) { /* leave earliest null */ }
            loadStory(_currentMonth.value)
        }
    }

    fun goToPreviousMonth() {
        if (canGoBack.value) {
            val target = _currentMonth.value.minusMonths(1)
            _currentMonth.value = target
            viewModelScope.launch { loadStory(target) }
        }
    }

    fun goToNextMonth() {
        if (canGoForward.value) {
            val target = _currentMonth.value.plusMonths(1)
            _currentMonth.value = target
            viewModelScope.launch { loadStory(target) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Multiple data sources — DB, DataStore, computation
    private suspend fun loadStory(month: YearMonth) {
        try {
            _loading.value = true
            _error.value = null
            _story.value = null
            val prevMonth = month.minusMonths(1)

            val (curStart, curEnd) = month.toMillisRange(zone)
            val (prevStart, prevEnd) = prevMonth.toMillisRange(zone)

            val readings = readingDao.readingsInRange(curStart, curEnd)
            val prevReadings = readingDao.readingsInRange(prevStart, prevEnd)
            val carbTreatments = treatmentDao.carbsInRange(curStart, curEnd)
            val allTreatments = treatmentDao.allSince(curStart)

            // Use the user's standard targets, NOT workout-mode-affected runtime
            // thresholds. Workout mode is a moment-in-time runtime state; historical
            // analysis must be invariant to whether the user happens to be exercising
            // when they open the screen.
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
                StoryParams(
                    month = month,
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
            )
            _story.value = result
            if (result != null) {
                settings.setStoryViewedMonth("%d-%02d".format(month.year, month.monthValue))
            }
        } catch (e: Exception) {
            _error.value = e.message
        } finally {
            _loading.value = false
        }
    }
}
