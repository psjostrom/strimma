package com.psjostrom.strimma.data

import com.psjostrom.strimma.data.health.ExerciseDao
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Statistics, Story and pattern detection must leave probable compression lows out, and they
 * must agree about which lows those are. [CompressionLowDetector] needs 30 minutes of readings
 * on each side of a low, plus the carbs and exercise around it. This class loads all of that for
 * a period, so a low near the start or the end of the period is judged the same way everywhere.
 * The main graph draws the spans this class returns, so the graph and the statistics always agree.
 */
class StatsReadings @Inject constructor(
    private val readingDao: ReadingDao,
    private val treatmentDao: TreatmentDao,
    private val exerciseDao: ExerciseDao,
    private val settings: SettingsRepository
) {
    /**
     * Readings with [start] <= ts <= [end]. Probable compression lows are left out unless the user
     * turned compression low detection off.
     */
    suspend fun inRange(start: Long, end: Long): List<GlucoseReading> {
        val readings = readingDao.readingsInRange(start, end)
        val spans = compressionSpans(start, end)
        if (spans.isEmpty()) return readings
        return readings.filter { r -> spans.none { r.ts in it } }
    }

    /** Spans of probable compression lows that touch [start]..[end]. Empty when detection is off. */
    suspend fun compressionSpans(start: Long, end: Long): List<LongRange> {
        if (!settings.compressionLowDetection.first()) return emptyList()
        val from = start - CONTEXT_MS
        val to = end + CONTEXT_MS
        val carbTimes = treatmentDao.carbsInRange(from, to).map { it.createdAt }
        val exercisePeriods = exerciseDao.getSessionsInRange(from, to).map { it.startTime..it.endTime }
        return CompressionLowDetector.detect(readingDao.readingsInRange(from, to), carbTimes, exercisePeriods)
            .filter { it.first <= end && start <= it.last }
    }

    private companion object {
        // A low that touches the period can have its nadir up to an hour outside it, and the
        // detector needs 30 minutes of readings and 20 minutes of carbs beyond that nadir.
        const val CONTEXT_MS = 2 * 60 * 60_000L
    }
}
