package com.psjostrom.strimma.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.MS_PER_HOUR
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.ui.theme.ThemeMode
import com.psjostrom.strimma.data.GlucoseSource
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.data.HbA1cUnit
import com.psjostrom.strimma.data.IOBComputer
import com.psjostrom.strimma.data.InsulinType
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.data.calendar.CalendarReader
import com.psjostrom.strimma.data.calendar.GuidanceState
import com.psjostrom.strimma.data.calendar.PreActivityAssessor
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import com.psjostrom.strimma.data.health.ExerciseCategory
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
import com.psjostrom.strimma.notification.AlertManager
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
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
    private val calendarReader: CalendarReader,
    val mealAnalyzer: MealAnalyzer,
    private val tidepoolAuthManager: TidepoolAuthManager,
    private val tidepoolUploader: TidepoolUploader,
    private val updateChecker: UpdateChecker,
    private val updateInstaller: UpdateInstaller
) : ViewModel() {

    companion object {
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

    val nightscoutUrl: StateFlow<String> = settings.nightscoutUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

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

    val nightscoutSecret: String get() = settings.getNightscoutSecret()

    val readings: StateFlow<List<GlucoseReading>> = dao.latest()
        .map { _ ->
            val since = System.currentTimeMillis() - HOURS_PER_DAY * MS_PER_HOUR
            dao.since(since)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setNightscoutUrl(url: String) = viewModelScope.launch { settings.setNightscoutUrl(url) }
    fun setNightscoutSecret(secret: String) = settings.setNightscoutSecret(secret)
    fun setGraphWindowHours(hours: Int) = viewModelScope.launch { settings.setGraphWindowHours(hours) }
    fun setBgLow(value: Float) = viewModelScope.launch { settings.setBgLow(value) }
    fun setBgHigh(value: Float) = viewModelScope.launch { settings.setBgHigh(value) }

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.System)
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }

    val notifGraphMinutes: StateFlow<Int> = settings.notifGraphMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)
    fun setNotifGraphMinutes(minutes: Int) = viewModelScope.launch { settings.setNotifGraphMinutes(minutes) }

    val predictionMinutes: StateFlow<Int> = settings.predictionMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)
    fun setPredictionMinutes(minutes: Int) = viewModelScope.launch { settings.setPredictionMinutes(minutes) }

    val glucoseUnit: StateFlow<GlucoseUnit> = settings.glucoseUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GlucoseUnit.MMOL)
    fun setGlucoseUnit(unit: GlucoseUnit) = viewModelScope.launch { settings.setGlucoseUnit(unit) }

    val hbA1cUnit: StateFlow<HbA1cUnit> = settings.hbA1cUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HbA1cUnit.MMOL_MOL)
    fun setHbA1cUnit(unit: HbA1cUnit) = viewModelScope.launch { settings.setHbA1cUnit(unit) }

    val startOnBoot: StateFlow<Boolean> = settings.startOnBoot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun setStartOnBoot(enabled: Boolean) = viewModelScope.launch { settings.setStartOnBoot(enabled) }


    val bgBroadcastEnabled: StateFlow<Boolean> = settings.bgBroadcastEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun setBgBroadcastEnabled(enabled: Boolean) = viewModelScope.launch { settings.setBgBroadcastEnabled(enabled) }

    val webServerEnabled: StateFlow<Boolean> = settings.webServerEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun setWebServerEnabled(enabled: Boolean) = viewModelScope.launch { settings.setWebServerEnabled(enabled) }

    val webServerSecret: String get() = settings.getWebServerSecret()
    fun setWebServerSecret(secret: String) = settings.setWebServerSecret(secret)

    val glucoseSource: StateFlow<GlucoseSource> = settings.glucoseSource
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GlucoseSource.COMPANION)
    fun setGlucoseSource(source: GlucoseSource) = viewModelScope.launch { settings.setGlucoseSource(source) }

    val pushStatus: StateFlow<IntegrationStatus> = nightscoutPusher.status

    val nsFollowerStatus: StateFlow<IntegrationStatus> = nightscoutFollower.status

    val lluFollowerStatus: StateFlow<IntegrationStatus> = libreLinkUpFollower.status

    val treatmentSyncStatus: StateFlow<IntegrationStatus> = treatmentSyncer.status

    val followerPollSeconds: StateFlow<Int> = settings.followerPollSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)
    fun setFollowerPollSeconds(seconds: Int) = viewModelScope.launch { settings.setFollowerPollSeconds(seconds) }

    val lluEmail: String get() = settings.getLluEmail()
    fun setLluEmail(email: String) = settings.setLluEmail(email)

    val lluPassword: String get() = settings.getLluPassword()
    fun setLluPassword(password: String) = settings.setLluPassword(password)

    suspend fun pullFromNightscout(days: Int): Result<Int> = nightscoutPuller.pullHistory(days)
    suspend fun pullTreatments(days: Int): Result<Int> = treatmentSyncer.pullHistory(days)


    // Treatments
    val treatmentsSyncEnabled: StateFlow<Boolean> = settings.treatmentsSyncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun setTreatmentsSyncEnabled(enabled: Boolean) = viewModelScope.launch { settings.setTreatmentsSyncEnabled(enabled) }

    val insulinType: StateFlow<InsulinType> = settings.insulinType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InsulinType.FIASP)
    fun setInsulinType(type: InsulinType) = viewModelScope.launch { settings.setInsulinType(type) }

    val customDIA: StateFlow<Float> = settings.customDIA
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 5.0f)
    fun setCustomDIA(hours: Float) = viewModelScope.launch { settings.setCustomDIA(hours) }

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

    // Tidepool
    val tidepoolEnabled: StateFlow<Boolean> = settings.tidepoolEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val tidepoolLastUploadTime: StateFlow<Long> = settings.tidepoolLastUploadTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val tidepoolLastError: StateFlow<String> = settings.tidepoolLastError
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun setTidepoolEnabled(enabled: Boolean) = viewModelScope.launch { settings.setTidepoolEnabled(enabled) }

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

    private val _cachedEvent = MutableStateFlow<WorkoutEvent?>(null)

    val workoutCalendarId: StateFlow<Long> = settings.workoutCalendarId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1L)
    val workoutCalendarName: StateFlow<String> = settings.workoutCalendarName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val workoutLookaheadHours: StateFlow<Int> = settings.workoutLookaheadHours
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)
    val workoutTriggerMinutes: StateFlow<Int> = settings.workoutTriggerMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 120)

    fun setWorkoutCalendar(id: Long, name: String) = viewModelScope.launch {
        settings.setWorkoutCalendarId(id)
        settings.setWorkoutCalendarName(name)
    }
    fun setWorkoutLookaheadHours(hours: Int) = viewModelScope.launch { settings.setWorkoutLookaheadHours(hours) }
    fun setWorkoutTriggerMinutes(minutes: Int) = viewModelScope.launch { settings.setWorkoutTriggerMinutes(minutes) }
    fun setExerciseTarget(category: ExerciseCategory, low: Float, high: Float) =
        viewModelScope.launch { settings.setExerciseTarget(category, low, high) }

    private suspend fun fetchNextEvent(calId: Long, lookaheadHours: Int): WorkoutEvent? =
        if (calId >= 0) {
            calendarReader.getNextWorkout(calId, lookaheadHours.toLong() * MS_PER_HOUR)
        } else null

    init {
        // React to calendarId/lookahead changes from DataStore
        viewModelScope.launch {
            combine(workoutCalendarId, workoutLookaheadHours) { calId, hours -> calId to hours }
                .collect { (calId, hours) ->
                    _cachedEvent.value = try {
                        fetchNextEvent(calId, hours)
                    } catch (
                        @Suppress("TooGenericExceptionCaught")
                        e: Exception
                    ) {
                        DebugLog.log("Calendar poll failed: ${e.message}")
                        null
                    }
                }
        }
        // Background poll for calendar changes (events added/removed externally)
        viewModelScope.launch {
            while (currentCoroutineContext().isActive) {
                delay(MS_PER_MINUTE)
                _cachedEvent.value = try {
                    fetchNextEvent(workoutCalendarId.value, workoutLookaheadHours.value)
                } catch (
                    @Suppress("TooGenericExceptionCaught")
                    e: Exception
                ) {
                    DebugLog.log("Calendar poll failed: ${e.message}")
                    null
                }
            }
        }
    }

    val guidanceState: StateFlow<GuidanceState> = combine(
        _cachedEvent,
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

    fun currentTauMinutes(): Double {
        return IOBComputer.tauForInsulinType(insulinType.value, customDIA.value)
    }

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

    suspend fun setMealSlotBoundary(key: String, minutes: Int) {
        settings.setMealSlotBoundary(key, minutes)
    }

    // Auto-update
    val updateInfo: StateFlow<UpdateInfo?> = updateChecker.updateInfo
    val updateDismissed: StateFlow<Boolean> = updateChecker.dismissed
    val downloadState: StateFlow<DownloadState> = updateInstaller.state

    fun dismissUpdate() = updateChecker.dismiss()

    fun downloadUpdate(info: UpdateInfo) {
        updateInstaller.download(info.apkUrl, info.version)
    }
}
