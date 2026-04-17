package com.psjostrom.strimma.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.MS_PER_HOUR
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.IOBComputer
import com.psjostrom.strimma.data.InsulinType
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.calendar.CalendarPoller
import com.psjostrom.strimma.data.calendar.GuidanceState
import com.psjostrom.strimma.data.calendar.PreActivityAssessor
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import com.psjostrom.strimma.data.health.ExerciseBGAnalyzer
import com.psjostrom.strimma.data.health.ExerciseBGContext
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.data.health.StoredExerciseSession
import com.psjostrom.strimma.data.Treatment
import com.psjostrom.strimma.data.TreatmentDao
import com.psjostrom.strimma.graph.PredictionComputer
import com.psjostrom.strimma.network.IntegrationStatus
import com.psjostrom.strimma.network.LibreLinkUpFollower
import com.psjostrom.strimma.network.NightscoutFollower
import com.psjostrom.strimma.network.NightscoutPuller
import com.psjostrom.strimma.network.NightscoutPusher
import com.psjostrom.strimma.tidepool.TidepoolAuthManager
import com.psjostrom.strimma.tidepool.TidepoolUploader
import com.psjostrom.strimma.update.UpdateChecker
import com.psjostrom.strimma.update.UpdateInstaller
import com.psjostrom.strimma.update.UpdateInfo
import com.psjostrom.strimma.update.DownloadState
import android.content.Intent
import com.psjostrom.strimma.data.meal.MealAnalyzer
import com.psjostrom.strimma.data.meal.MealTimeSlotConfig
import com.psjostrom.strimma.data.story.toMillisRange
import com.psjostrom.strimma.network.TreatmentSyncer
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions", "LongParameterList") // One function per setting + reading/export logic; Hilt ViewModel needs all dependencies
@HiltViewModel
class MainViewModel @Inject constructor(
    private val dao: ReadingDao,
    private val treatmentDao: TreatmentDao,
    private val exerciseDao: ExerciseDao,
    private val exerciseBGAnalyzer: ExerciseBGAnalyzer,
    val settings: SettingsRepository,
    private val nightscoutFollower: NightscoutFollower,
    private val libreLinkUpFollower: LibreLinkUpFollower,
    private val nightscoutPuller: NightscoutPuller,
    private val nightscoutPusher: NightscoutPusher,
    private val treatmentSyncer: TreatmentSyncer,
    private val calendarPoller: CalendarPoller,
    val mealAnalyzer: MealAnalyzer,
    private val tidepoolAuthManager: TidepoolAuthManager,
    private val tidepoolUploader: TidepoolUploader,
    private val updateChecker: UpdateChecker,
    private val updateInstaller: UpdateInstaller
) : ViewModel() {

    companion object {
        private const val UP_TO_DATE_DISPLAY_MS = 3000L
        private const val HOURS_PER_DAY = 24
        private const val PRE_WINDOW_MINUTES = 30
        private const val POST_WINDOW_HOURS = 4
        internal const val FORECAST_HORIZON_MINUTES = 30
        private const val MIN_STORY_DAYS = 7

        internal fun computeGuidance(
            event: WorkoutEvent?,
            latest: GlucoseReading?,
            allReadings: List<GlucoseReading>,
            iob: Double,
            targetLow: Float,
            targetHigh: Float,
            bgLow: Double,
            bgHigh: Double,
            glucoseUnit: GlucoseUnit = GlucoseUnit.MMOL,
            nowMs: Long = System.currentTimeMillis()
        ): GuidanceState {
            if (event == null || latest == null) return GuidanceState.NoWorkout

            val timeToWorkout = event.startTime - nowMs
            if (timeToWorkout <= 0) return GuidanceState.NoWorkout

            val velocity = PredictionComputer.currentVelocity(allReadings)
            val prediction = PredictionComputer.compute(allReadings, FORECAST_HORIZON_MINUTES, bgLow, bgHigh)
            val forecastBg = prediction?.points?.lastOrNull()?.mgdl

            val direction = com.psjostrom.strimma.data.Direction.parse(latest.direction)

            val result = PreActivityAssessor.assess(
                currentBgMgdl = latest.sgv,
                velocityMgdlPerMin = velocity,
                iob = iob,
                forecastBgAt30minMgdl = forecastBg,
                timeToWorkoutMs = timeToWorkout,
                targetLowMgdl = targetLow,
                glucoseUnit = glucoseUnit
            )

            return GuidanceState.WorkoutApproaching(
                event = event,
                readiness = result.readiness,
                reasons = result.reasons,
                suggestions = result.suggestions,
                carbRecommendation = result.carbRecommendation,
                targetLowMgdl = targetLow,
                targetHighMgdl = targetHigh,
                currentBgMgdl = latest.sgv,
                trendArrow = direction.arrow,
                iob = iob
            )
        }
    }

    val setupCompleted: StateFlow<Boolean?> = settings.setupCompleted
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val setupStep: StateFlow<Int> = settings.setupStep
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val storyReady: StateFlow<Boolean> = settings.storyViewedMonth
        .map { viewedMonth ->
            val lastMonth = YearMonth.now().minusMonths(1)
            val lastMonthKey = "%d-%02d".format(lastMonth.year, lastMonth.monthValue)
            if (viewedMonth == lastMonthKey) return@map false
            val zone = ZoneId.systemDefault()
            val (start, end) = lastMonth.toMillisRange(zone)
            val readings = dao.readingsInRange(start, end)
            val days = readings.map {
                Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate()
            }.distinct().size
            days >= MIN_STORY_DAYS
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val latestReading: StateFlow<GlucoseReading?> = dao.latest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val nightscoutConfigured: StateFlow<Boolean> = combine(
        settings.nightscoutUrl,
        settings.secretVersion
    ) { nsUrl, _ ->
        nsUrl.isNotBlank() && settings.getNightscoutSecret().isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val graphWindowHours: StateFlow<Int> = settings.graphWindowHours
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 4)

    val bgLow: StateFlow<Float> = settings.bgLow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 72f)

    val bgHigh: StateFlow<Float> = settings.bgHigh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 180f)

    val readings: StateFlow<List<GlucoseReading>> = dao.latest()
        .map { _ ->
            val since = System.currentTimeMillis() - HOURS_PER_DAY * MS_PER_HOUR
            dao.since(since)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val predictionMinutes: StateFlow<Int> = settings.predictionMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)

    val glucoseUnit: StateFlow<GlucoseUnit> = settings.glucoseUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GlucoseUnit.MMOL)

    val pushStatus: StateFlow<IntegrationStatus> = nightscoutPusher.status

    val nsFollowerStatus: StateFlow<IntegrationStatus> = nightscoutFollower.status

    val lluFollowerStatus: StateFlow<IntegrationStatus> = libreLinkUpFollower.status

    val treatmentSyncStatus: StateFlow<IntegrationStatus> = treatmentSyncer.status

    suspend fun pullFromNightscout(days: Int): Result<Int> = nightscoutPuller.pullHistory(days)
    suspend fun pullTreatments(days: Int): Result<Int> = treatmentSyncer.pullHistory(days)

    val treatments: StateFlow<List<Treatment>> = settings.treatmentsSyncEnabled
        .flatMapLatest { enabled ->
            if (enabled) {
                val since = System.currentTimeMillis() - HOURS_PER_DAY * MS_PER_HOUR
                treatmentDao.since(since)
            } else {
                kotlinx.coroutines.flow.flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val iob: StateFlow<Double> = combine(
        treatments,
        settings.insulinType,
        settings.customDIA,
        settings.treatmentsSyncEnabled
    ) { treatments, insulinType, customDIA, enabled ->
        if (!enabled || treatments.isEmpty()) return@combine 0.0
        val tau = IOBComputer.tauForInsulinType(insulinType, customDIA)
        IOBComputer.computeIOB(treatments, System.currentTimeMillis(), tau)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    private val _tidepoolLoggedIn = MutableStateFlow(tidepoolAuthManager.isLoggedIn())
    val tidepoolLoggedIn: StateFlow<Boolean> = _tidepoolLoggedIn

    fun buildTidepoolAuthIntent(): Intent =
        tidepoolAuthManager.buildAuthIntent()

    suspend fun handleTidepoolAuthResult(intent: Intent) {
        val success = tidepoolAuthManager.handleAuthResponse(intent)
        if (!success) {
            settings.setTidepoolLastError("Login failed")
            return
        }

        val userId = tidepoolAuthManager.fetchUserId()
        if (userId == null) {
            settings.setTidepoolLastError("Failed to fetch user ID")
            tidepoolAuthManager.logout()
            return
        }

        settings.setTidepoolUserId(userId)
        settings.setTidepoolLastError("")
        _tidepoolLoggedIn.value = true
    }

    fun tidepoolLogout() {
        if (!_tidepoolLoggedIn.compareAndSet(expect = true, update = false)) return
        viewModelScope.launch {
            tidepoolAuthManager.logout()
        }
    }

    suspend fun tidepoolForceUpload(): Int = tidepoolUploader.forceUpload()

    val exerciseSessions: StateFlow<List<StoredExerciseSession>> = exerciseDao.getAllSessions()
        .map { sessions ->
            val since = System.currentTimeMillis() - HOURS_PER_DAY * MS_PER_HOUR
            sessions.filter { it.startTime >= since }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        calendarPoller.start(viewModelScope)
    }

    val guidanceState: StateFlow<GuidanceState> = combine(
        calendarPoller.nextEvent,
        latestReading,
        readings,
        iob,
        glucoseUnit
    ) { event, latest, allReadings, iobVal, unit ->
        val targetLow = event?.let { settings.exerciseTargetLow(it.category).first() } ?: 0f
        val targetHigh = event?.let { settings.exerciseTargetHigh(it.category).first() } ?: 0f
        computeGuidance(
            event, latest, allReadings, iobVal,
            targetLow, targetHigh,
            bgLow.value.toDouble(), bgHigh.value.toDouble(),
            unit
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GuidanceState.NoWorkout)

    suspend fun computeExerciseBGContext(session: StoredExerciseSession): ExerciseBGContext? {
        val preStart = session.startTime - PRE_WINDOW_MINUTES * MS_PER_MINUTE
        val postEnd = session.endTime + POST_WINDOW_HOURS * MS_PER_HOUR
        val readings = dao.readingsInRange(preStart, postEnd)
        val hrSamples = exerciseDao.getHeartRateForSession(session.id)
        return exerciseBGAnalyzer.analyze(session, readings, hrSamples, bgLow.value.toDouble())
    }

    suspend fun exportSettings(): String = settings.exportToJson()

    suspend fun importSettings(json: String) = settings.importFromJson(json)

    suspend fun readingsForPeriod(hours: Int): List<GlucoseReading> {
        val since = System.currentTimeMillis() - hours * MS_PER_HOUR
        return dao.since(since)
    }

    suspend fun exportCsv(hours: Int): String {
        val readings = readingsForPeriod(hours)
        return buildString {
            appendLine("timestamp,datetime,sgv,direction,delta_mgdl")
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
            for (r in readings) {
                appendLine("${r.ts},${sdf.format(java.util.Date(r.ts))},${r.sgv},${r.direction},${r.delta ?: ""}")
            }
        }
    }

    suspend fun carbTreatmentsInRange(start: Long, end: Long): List<Treatment> {
        return treatmentDao.carbsInRange(start, end)
    }

    suspend fun allTreatmentsSince(start: Long): List<Treatment> {
        return treatmentDao.allSince(start)
    }

    val tauMinutes: StateFlow<Double> = combine(
        settings.insulinType,
        settings.customDIA
    ) { type, dia -> IOBComputer.tauForInsulinType(type, dia) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InsulinType.FIASP.tauMinutes)

    val mealTimeSlotConfig: StateFlow<MealTimeSlotConfig> = combine(
        settings.mealBreakfastStart,
        settings.mealBreakfastEnd,
        settings.mealLunchStart,
        settings.mealLunchEnd,
        settings.mealDinnerStart,
        settings.mealDinnerEnd
    ) { values ->
        MealTimeSlotConfig(
            breakfastStart = values[0] as Int,
            breakfastEnd = values[1] as Int,
            lunchStart = values[2] as Int,
            lunchEnd = values[3] as Int,
            dinnerStart = values[4] as Int,
            dinnerEnd = values[5] as Int
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MealTimeSlotConfig())

    // Auto-update
    val updateInfo: StateFlow<UpdateInfo?> = updateChecker.updateInfo
    val updateDismissed: StateFlow<Boolean> = updateChecker.dismissed
    val downloadState: StateFlow<DownloadState> = updateInstaller.state

    private val _updateCheckState = MutableStateFlow(UpdateCheckState.IDLE)
    val updateCheckState: StateFlow<UpdateCheckState> = _updateCheckState

    fun dismissUpdate() = updateChecker.dismiss()

    fun downloadUpdate(info: UpdateInfo) {
        updateInstaller.download(info.apkUrl, info.version)
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _updateCheckState.value = UpdateCheckState.CHECKING
            updateChecker.resetDismissed() // reset so dialog shows if update found
            updateChecker.check()
            _updateCheckState.value = if (updateChecker.updateInfo.value != null) {
                UpdateCheckState.IDLE
            } else {
                UpdateCheckState.UP_TO_DATE
            }
            if (_updateCheckState.value == UpdateCheckState.UP_TO_DATE) {
                delay(UP_TO_DATE_DISPLAY_MS)
                _updateCheckState.value = UpdateCheckState.IDLE
            }
        }
    }

    enum class UpdateCheckState { IDLE, CHECKING, UP_TO_DATE }

    // Beta updates
    val betaUpdateInfo: StateFlow<UpdateInfo?> = updateChecker.betaUpdateInfo

    private val _betaCheckState = MutableStateFlow(UpdateCheckState.IDLE)
    val betaCheckState: StateFlow<UpdateCheckState> = _betaCheckState

    fun checkForBeta() {
        viewModelScope.launch {
            _betaCheckState.value = UpdateCheckState.CHECKING
            updateChecker.resetDismissed()
            updateChecker.checkBeta()
            _betaCheckState.value = if (updateChecker.betaUpdateInfo.value != null) {
                UpdateCheckState.IDLE
            } else {
                UpdateCheckState.UP_TO_DATE
            }
            if (_betaCheckState.value == UpdateCheckState.UP_TO_DATE) {
                delay(UP_TO_DATE_DISPLAY_MS)
                _betaCheckState.value = UpdateCheckState.IDLE
            }
        }
    }
}
