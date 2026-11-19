package com.psjostrom.strimma.data

import com.psjostrom.strimma.data.health.ExerciseDao
import javax.inject.Inject

/**
 * Statistics, Story and pattern detection must leave probable compression lows out, and they
 * must agree about which lows those are. [CompressionLowDetector] needs 30 minutes of readings
 * on each side of a low, plus the carbs and exercise around it. This class loads all of that for
 * a period, so a low near the start or the end of the period is judged the same way everywhere.
 */
class StatsReadings @Inject constructor(
    private val readingDao: ReadingDao,
    private val treatmentDao: TreatmentDao,
    private val exerciseDao: ExerciseDao
) {
    /** Readings with [start] <= ts <= [end], without probable compression lows. */
    suspend fun inRange(start: Long, end: Long): List<GlucoseReading> {
        val from = start - CONTEXT_MS
        val to = end + CONTEXT_MS
        val carbTimes = treatmentDao.carbsInRange(from, to).map { it.createdAt }
        val exercisePeriods = exerciseDao.getSessionsInRange(from, to).map { it.startTime..it.endTime }
        return CompressionLowDetector
            .withoutCompressionLows(readingDao.readingsInRange(from, to), carbTimes, exercisePeriods)
            .filter { it.ts in start..end }
    }

    private companion object {
        const val CONTEXT_MS = 30 * 60_000L
    }
}
