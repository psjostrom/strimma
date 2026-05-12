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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

private const val SAVED_KEY_YEAR = "year"
private const val SAVED_KEY_MONTH = "month"

// Periodic re-evaluation of the upper bound so a screen left open across
// midnight on the 1st of a new month sees the now-completed month become
// reachable via the forward arrow.
private const val BOUNDS_TICK_MS = 60_000L

/**
 * Historical monthly analysis. **Reads thresholds from SettingsRepository directly,
 * NOT from WorkoutModeManager.effectiveThresholds** — Story computes TIR / AGP /
 * meal stats over a window that long predates the user's current workout-mode
 * state, and presenting last-month's TIR against today's transient workout-mode
 * thresholds would silently corrupt the analysis.
 *
 * Holds the displayed month as mutable state so the user can navigate to other
 * months via prev/next without leaving the screen. Bounds are reactive:
 *
 * - Lower bound: month of the earliest reading on disk, derived from
 *   [ReadingDao.earliestTsFlow]. Re-emits when a Nightscout pull backfills
 *   older history, so the back-arrow becomes available without re-opening
 *   the screen.
 * - Upper bound: last completed month, recomputed every minute so a screen
 *   left open across midnight on the 1st of a new month sees the
 *   newly-completed month become reachable via the forward arrow.
 *
 * Current month survives process death via SavedStateHandle.
 */
@HiltViewModel
class StoryViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val readingDao: ReadingDao,
    private val treatmentDao: TreatmentDao,
    private val settings: SettingsRepository,
    private val mealAnalyzer: MealAnalyzer,
) : ViewModel() {

    private val zone = ZoneId.systemDefault()

    private val _currentMonth: MutableStateFlow<YearMonth> = run {
        val year: Int = savedStateHandle[SAVED_KEY_YEAR] ?: YearMonth.now().minusMonths(1).year
        val month: Int = savedStateHandle[SAVED_KEY_MONTH] ?: YearMonth.now().minusMonths(1).monthValue
        MutableStateFlow(YearMonth.of(year, month))
    }
    val currentMonth: StateFlow<YearMonth> = _currentMonth

    private val earliestMonth: StateFlow<YearMonth?> = readingDao.earliestTsFlow()
        .map { ts -> ts?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(zone)) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val boundsTicker = flow {
        while (currentCoroutineContext().isActive) {
            emit(Unit)
            delay(BOUNDS_TICK_MS)
        }
    }

    val canGoBack: StateFlow<Boolean> = combine(_currentMonth, earliestMonth) { current, earliest ->
        earliest != null && current.isAfter(earliest)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val canGoForward: StateFlow<Boolean> = combine(_currentMonth, boundsTicker) { current, _ ->
        current.isBefore(YearMonth.now().minusMonths(1))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _story = MutableStateFlow<StoryData?>(null)
    val story: StateFlow<StoryData?> = _story

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Cancelled before launching a new load so rapid prev/next taps cannot
    // produce a "last-launched-wins" race where a slow stale load overwrites
    // a fresh one's result. The user always sees the month they last picked.
    private var loadJob: Job? = null

    init {
        loadJob = viewModelScope.launch { loadStory(_currentMonth.value) }
    }

    fun goToPreviousMonth() {
        if (canGoBack.value) navigateTo(_currentMonth.value.minusMonths(1))
    }

    fun goToNextMonth() {
        if (canGoForward.value) navigateTo(_currentMonth.value.plusMonths(1))
    }

    private fun navigateTo(target: YearMonth) {
        _currentMonth.value = target
        savedStateHandle[SAVED_KEY_YEAR] = target.year
        savedStateHandle[SAVED_KEY_MONTH] = target.monthValue
        // Set loading + clear story synchronously so the UI flips to the spinner
        // before the launched coroutine runs — prevents one render frame showing
        // the previous month's data with the new month's header.
        _loading.value = true
        _story.value = null
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadStory(target) }
    }

    @Suppress("TooGenericExceptionCaught") // Multiple data sources — DB, DataStore, computation
    private suspend fun loadStory(month: YearMonth) {
        try {
            _error.value = null
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
