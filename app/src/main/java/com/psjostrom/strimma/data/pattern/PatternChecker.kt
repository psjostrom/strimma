package com.psjostrom.strimma.data.pattern

import com.psjostrom.strimma.data.MS_PER_DAY
import com.psjostrom.strimma.data.MS_PER_HOUR
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.data.workout.Clock
import com.psjostrom.strimma.data.workout.SystemClock
import com.psjostrom.strimma.receiver.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PatternChecker @Inject constructor(
    private val readingDao: ReadingDao,
    private val exerciseDao: ExerciseDao,
    private val settings: SettingsRepository,
    private val clock: Clock = SystemClock()
) {
    companion object {
        const val LOOKBACK_DAYS = 7
        const val MIN_DATA_DAYS = 5
        const val CHECK_INTERVAL_MS = 6 * MS_PER_HOUR
    }

    private val _activePatterns = MutableStateFlow<List<GlucosePattern>>(emptyList())
    val activePatterns: StateFlow<List<GlucosePattern>> = _activePatterns.asStateFlow()

    private val mutex = Mutex()
    private var loopJob: Job? = null
    private var settingsJob: Job? = null

    fun start(scope: CoroutineScope, zone: ZoneId = ZoneId.systemDefault()): Job {
        loopJob?.cancel()
        settingsJob?.cancel()

        settingsJob = scope.launch {
            settings.patternAlertsEnabled.drop(1).collect { enabled ->
                if (enabled) {
                    checkNow(zone = zone)
                } else {
                    reset()
                }
            }
        }

        val job = scope.launch {
            while (isActive) {
                try {
                    checkNow(zone = zone)
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    DebugLog.log("Periodic pattern check failed: ${e.message}")
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
        loopJob = job
        return job
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        settingsJob?.cancel()
        settingsJob = null
    }

    suspend fun reset() = mutex.withLock {
        _activePatterns.value = emptyList()
    }

    /**
     * Executes pattern detection for the last 7 days and updates [activePatterns].
     */
    suspend fun checkNow(
        now: Instant = Instant.ofEpochMilli(clock.nowMs()),
        zone: ZoneId = ZoneId.systemDefault()
    ): PatternResult? = mutex.withLock {
        if (!settings.patternAlertsEnabled.first()) {
            _activePatterns.value = emptyList()
            return null
        }

        val readings = loadReadings(now, zone)
        if (readings == null) {
            _activePatterns.value = emptyList()
            return null
        }

        val nowMs = now.toEpochMilli()
        val startMs = now.atZone(zone).toLocalDate()
            .minusDays(LOOKBACK_DAYS.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val exercises = exerciseDao.getSessionsInRange(startMs, nowMs)
        val workoutPeriods = exercises.map { it.startTime..it.endTime }

        val result = PatternDetector.detect(
            readings = readings,
            bgLowMgdl = settings.bgLow.first().toDouble(),
            bgHighMgdl = settings.bgHigh.first().toDouble(),
            lookbackDays = LOOKBACK_DAYS,
            zone = zone,
            now = now,
            workoutPeriods = workoutPeriods
        )

        if (!settings.patternAlertsEnabled.first()) {
            _activePatterns.value = emptyList()
            return null
        }

        _activePatterns.value = result.patterns
        result
    }

    private suspend fun loadReadings(now: Instant, zone: ZoneId): List<com.psjostrom.strimma.data.GlucoseReading>? {
        val nowMs = now.toEpochMilli()
        val earliest = readingDao.earliestTs() ?: return null
        if ((nowMs - earliest) < MIN_DATA_DAYS * MS_PER_DAY) return null

        val startMs = now.atZone(zone).toLocalDate()
            .minusDays(LOOKBACK_DAYS.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val readings = readingDao.since(startMs)
        return readings.ifEmpty { null }
    }
}
