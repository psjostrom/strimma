package com.psjostrom.strimma.data.pattern

import com.psjostrom.strimma.data.MS_PER_DAY
import com.psjostrom.strimma.data.MS_PER_HOUR
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.notification.PatternNotifier
import com.psjostrom.strimma.receiver.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PatternChecker @Inject constructor(
    private val readingDao: ReadingDao,
    private val exerciseDao: ExerciseDao,
    private val settings: SettingsRepository,
    private val notifier: PatternNotifier
) {
    companion object {
        const val LOOKBACK_DAYS = 7
        const val MIN_DATA_DAYS = 5
        const val TARGET_CHECK_HOUR = 21 // 21:00 local time
        const val DEDUP_COOLDOWN_HOURS = 72L

        internal fun millisUntilNextTargetHour(
            targetHour: Int = TARGET_CHECK_HOUR,
            zone: ZoneId = ZoneId.systemDefault(),
            now: Instant = Instant.now()
        ): Long {
            val zdt = now.atZone(zone)
            var nextTarget = zdt.withHour(targetHour).withMinute(0).withSecond(0).withNano(0)
            if (!nextTarget.isAfter(zdt)) {
                nextTarget = nextTarget.plusDays(1)
            }
            return Duration.between(zdt, nextTarget).toMillis()
        }
    }

    private val _activePatterns = MutableStateFlow<List<GlucosePattern>>(emptyList())
    val activePatterns: StateFlow<List<GlucosePattern>> = _activePatterns.asStateFlow()

    private var loopJob: Job? = null

    fun start(scope: CoroutineScope): Job {
        loopJob?.cancel()
        val job = scope.launch {
            try {
                checkNow(notify = false)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) { // DAO/DataStore — multiple types
                DebugLog.log("Initial pattern check failed: ${e.message}")
            }

            while (isActive) {
                val delayMs = millisUntilNextTargetHour()
                delay(delayMs)
                try {
                    checkNow()
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) { // DAO/DataStore — multiple types
                    DebugLog.log("Periodic pattern check failed: ${e.message}")
                }
            }
        }
        loopJob = job
        return job
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    suspend fun reset() {
        _activePatterns.value = emptyList()
        notifier.cancel()
        settings.setPatternLastHash("")
    }

    /**
     * Executes pattern detection for the last 7 days.
     * Can be invoked directly from tests or on-demand.
     *
     * @param notify when false, updates [activePatterns] state but skips notification dispatch.
     *               Startup uses false to populate the UI card without alerting at arbitrary hours.
     */
    suspend fun checkNow(
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        notify: Boolean = true
    ): PatternResult? {
        if (!settings.patternAlertsEnabled.first()) {
            reset()
            return null
        }

        val readings = loadReadings(now, zone)
        if (readings == null) {
            reset()
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

        return applyResult(result, nowMs, notify)
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

    private suspend fun applyResult(result: PatternResult, nowMs: Long, notify: Boolean): PatternResult {
        val newHash = result.stableHash()
        val lastHash = settings.patternLastHash.first()

        if (newHash.isEmpty()) {
            _activePatterns.value = emptyList()
            if (lastHash.isNotEmpty()) {
                settings.setPatternLastHash("")
                notifier.cancel()
            }
            return result
        }

        _activePatterns.value = result.patterns

        if (notify) {
            val cooldownMs = DEDUP_COOLDOWN_HOURS * MS_PER_HOUR
            val lastNotifiedTs = settings.patternLastNotifiedTs.first()
            val inCooldown = (newHash == lastHash) && ((nowMs - lastNotifiedTs) < cooldownMs)

            if (!inCooldown) {
                val unit = settings.glucoseUnit.first()
                notifier.notifyPatterns(result, unit)
                settings.setPatternLastHash(newHash)
                settings.setPatternLastNotifiedTs(nowMs)
            }
        }

        return result
    }
}
