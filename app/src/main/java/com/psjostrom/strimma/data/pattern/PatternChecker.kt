package com.psjostrom.strimma.data.pattern

import com.psjostrom.strimma.data.MS_PER_DAY
import com.psjostrom.strimma.data.MS_PER_HOUR
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.notification.PatternNotifier
import com.psjostrom.strimma.receiver.DebugLog
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
                checkNow()
            } catch (e: Exception) {
                DebugLog.log("Initial pattern check failed: ${e.message}")
            }

            while (isActive) {
                val delayMs = millisUntilNextTargetHour()
                delay(delayMs)
                try {
                    checkNow()
                } catch (e: Exception) {
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

    /**
     * Executes pattern detection for the last 7 days.
     * Can be invoked directly from tests or on-demand.
     */
    suspend fun checkNow(
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): PatternResult? {
        val enabled = settings.patternAlertsEnabled.first()
        if (!enabled) {
            _activePatterns.value = emptyList()
            return null
        }

        val nowMs = now.toEpochMilli()
        val earliest = readingDao.earliestTs()
        if (earliest == null || (nowMs - earliest) < MIN_DATA_DAYS * MS_PER_DAY) {
            return null
        }

        val today = now.atZone(zone).toLocalDate()
        val startMs = today.minusDays(LOOKBACK_DAYS.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val readings = readingDao.since(startMs)
        if (readings.isEmpty()) return null

        val exercises = exerciseDao.getSessionsInRange(startMs, nowMs)
        val workoutPeriods = exercises.map { it.startTime..it.endTime }

        val bgLow = settings.bgLow.first().toDouble()
        val bgHigh = settings.bgHigh.first().toDouble()

        val result = PatternDetector.detect(
            readings = readings,
            bgLowMgdl = bgLow,
            bgHighMgdl = bgHigh,
            lookbackDays = LOOKBACK_DAYS,
            zone = zone,
            now = now,
            workoutPeriods = workoutPeriods
        )

        val newHash = result.stableHash()
        val lastHash = settings.patternLastHash.first()
        val lastNotifiedTs = settings.patternLastNotifiedTs.first()

        if (newHash.isEmpty()) {
            _activePatterns.value = emptyList()
            if (lastHash.isNotEmpty()) {
                settings.setPatternLastHash("")
                notifier.cancel()
            }
            return result
        }

        _activePatterns.value = result.patterns

        val cooldownMs = DEDUP_COOLDOWN_HOURS * MS_PER_HOUR
        val inCooldown = (newHash == lastHash) && ((nowMs - lastNotifiedTs) < cooldownMs)

        if (!inCooldown) {
            val unit = settings.glucoseUnit.first()
            notifier.notifyPatterns(result, unit)
            settings.setPatternLastHash(newHash)
            settings.setPatternLastNotifiedTs(nowMs)
        }

        return result
    }
}
