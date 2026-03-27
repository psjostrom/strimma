package com.psjostrom.strimma.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.data.GlucoseReading
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
import com.psjostrom.strimma.network.FollowerStatus
import com.psjostrom.strimma.network.LibreLinkUpFollower
import com.psjostrom.strimma.network.NightscoutFollower
import com.psjostrom.strimma.network.NightscoutPuller
import com.psjostrom.strimma.notification.AlertManager
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
    private val alertManager: AlertManager,
    private val nightscoutFollower: NightscoutFollower,
    private val libreLinkUpFollower: LibreLinkUpFollower,
    private val nightscoutPuller: NightscoutPuller,
    private val calendarReader: CalendarReader
) : ViewModel() {

    companion object {
        private const val HOURS_PER_DAY = 24
        private const val MS_PER_HOUR = 3600_000L
        private const val PRE_WINDOW_MINUTES = 30
        private const val POST_WINDOW_HOURS = 4
        private const val MS_PER_MINUTE = 60_000L
        internal const val FORECAST_HORIZON_MINUTES = 30

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

            val direction = try {
                com.psjostrom.strimma.data.Direction.valueOf(latest.direction)
            } catch (_: Exception) {
                com.psjostrom.strimma.data.Direction.NONE
            }

            val result = PreActivityAssessor.assess(
                currentBgMgdl = latest.sgv,
                velocityMgdlPerMin = velocity,
                iob = iob,
                forecastBgAt30minMgdl = forecastBg,
                timeToWorkoutMs = timeToWorkout,
                targetLowMgdl = targetLow,
                targetHighMgdl = targetHigh,
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

    val latestReading: StateFlow<GlucoseReading?> = dao.latest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val nightscoutUrl: StateFlow<String> = settings.nightscoutUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

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

    // Alert settings
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

    fun setNightscoutUrl(url: String) = viewModelScope.launch { settings.setNightscoutUrl(url) }
    fun setNightscoutSecret(secret: String) = settings.setNightscoutSecret(secret)
    fun setGraphWindowHours(hours: Int) = viewModelScope.launch { settings.setGraphWindowHours(hours) }
    fun setBgLow(value: Float) = viewModelScope.launch { settings.setBgLow(value) }
    fun setBgHigh(value: Float) = viewModelScope.launch { settings.setBgHigh(value) }

    // Alert setters
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

    val themeMode: StateFlow<String> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "System")
    fun setThemeMode(mode: String) = viewModelScope.launch { settings.setThemeMode(mode) }

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

    val language: StateFlow<String> = settings.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    fun setLanguage(tag: String) = viewModelScope.launch { settings.setLanguage(tag) }

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

    val followerStatus: StateFlow<FollowerStatus> = glucoseSource.flatMapLatest { source ->
        when (source) {
            GlucoseSource.NIGHTSCOUT_FOLLOWER -> nightscoutFollower.status
            GlucoseSource.LIBRELINKUP -> libreLinkUpFollower.status
            else -> kotlinx.coroutines.flow.flowOf(FollowerStatus.Idle)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FollowerStatus.Idle)

    val followerUrl: StateFlow<String> = settings.followerUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    fun setFollowerUrl(url: String) = viewModelScope.launch { settings.setFollowerUrl(url) }

    val followerSecret: String get() = settings.getFollowerSecret()
    fun setFollowerSecret(secret: String) = settings.setFollowerSecret(secret)

    val followerPollSeconds: StateFlow<Int> = settings.followerPollSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)
    fun setFollowerPollSeconds(seconds: Int) = viewModelScope.launch { settings.setFollowerPollSeconds(seconds) }

    val lluEmail: String get() = settings.getLluEmail()
    fun setLluEmail(email: String) = settings.setLluEmail(email)

    val lluPassword: String get() = settings.getLluPassword()
    fun setLluPassword(password: String) = settings.setLluPassword(password)

    suspend fun pullFromNightscout(days: Int): Result<Int> = nightscoutPuller.pullHistory(days)

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

    init {
        viewModelScope.launch {
            while (currentCoroutineContext().isActive) {
                try {
                    val calId = workoutCalendarId.value
                    _cachedEvent.value = if (calId >= 0) {
                        val lookaheadMs = workoutLookaheadHours.value.toLong() * MS_PER_HOUR
                        calendarReader.getNextWorkout(calId, lookaheadMs)
                    } else null
                } catch (
                    @Suppress("TooGenericExceptionCaught")
                    e: Exception
                ) {
                    DebugLog.log("Calendar poll failed: ${e.message}")
                    _cachedEvent.value = null
                }
                delay(MS_PER_MINUTE)
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
}
