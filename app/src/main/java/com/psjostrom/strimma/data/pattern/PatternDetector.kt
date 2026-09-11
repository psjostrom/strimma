package com.psjostrom.strimma.data.pattern

import com.psjostrom.strimma.data.GlucoseReading
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Detects recurring time-of-day glucose patterns from historical readings.
 *
 * Pure computation — no Android dependencies. Classifies each hour-of-day bucket
 * across multiple calendar days and reports patterns where the user is consistently
 * out of range at the same clock time.
 */
object PatternDetector {

    /** Minimum readings in an hour-bucket on a given day to count that day. */
    private const val MIN_READINGS_PER_BUCKET = 3

    /** Fraction of readings in an hour that must be out of range to flag that day-bucket. */
    private const val OUT_OF_RANGE_FRACTION = 0.5

    /** Minimum flagged days to declare a pattern. */
    private const val MIN_FLAGGED_DAYS = 4

    /** Minimum evaluated days (with sufficient coverage) to declare a pattern. */
    private const val MIN_EVALUATED_DAYS = 5

    private const val HOURS_PER_DAY = 24

    fun detect(
        readings: List<GlucoseReading>,
        bgLowMgdl: Double,
        bgHighMgdl: Double,
        lookbackDays: Int = 7,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
        workoutPeriods: List<LongRange> = emptyList()
    ): PatternResult {
        val today = now.atZone(zone).toLocalDate()
        val startDate = today.minusDays(lookbackDays.toLong())

        // Filter to lookback window and exclude workout periods
        val startMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = now.toEpochMilli()
        val filtered = readings
            .filter { it.ts in startMs..endMs }
            .filter { r -> workoutPeriods.none { r.ts in it } }

        // Group by (calendar day, hour-of-day)
        val byDayHour: Map<Pair<LocalDate, Int>, List<GlucoseReading>> = filtered.groupBy { r ->
            val zdt = Instant.ofEpochMilli(r.ts).atZone(zone)
            zdt.toLocalDate() to zdt.hour
        }

        // For each hour bucket, evaluate across days
        val rawPatterns = mutableListOf<GlucosePattern>()
        for (hour in 0 until HOURS_PER_DAY) {
            evaluateHour(hour, startDate, lookbackDays, byDayHour, bgLowMgdl, bgHighMgdl, rawPatterns)
        }

        return PatternResult(
            patterns = mergeAdjacent(rawPatterns),
            analysisDate = today,
            lookbackDays = lookbackDays
        )
    }

    @Suppress("LongParameterList") // Private helper extracted from detect — all params domain-essential
    private fun evaluateHour(
        hour: Int,
        startDate: LocalDate,
        lookbackDays: Int,
        byDayHour: Map<Pair<LocalDate, Int>, List<GlucoseReading>>,
        bgLowMgdl: Double,
        bgHighMgdl: Double,
        out: MutableList<GlucosePattern>
    ) {
        var lowDays = 0
        var highDays = 0
        var evaluatedDays = 0
        val lowBgValues = mutableListOf<Int>()
        val highBgValues = mutableListOf<Int>()

        for (dayOffset in 0 until lookbackDays) {
            val day = startDate.plusDays(dayOffset.toLong())
            val bucket = byDayHour[day to hour] ?: continue
            if (bucket.size < MIN_READINGS_PER_BUCKET) continue

            evaluatedDays++
            val belowCount = bucket.count { it.sgv < bgLowMgdl }
            val aboveCount = bucket.count { it.sgv > bgHighMgdl }

            if (belowCount.toDouble() / bucket.size >= OUT_OF_RANGE_FRACTION) {
                lowDays++
                lowBgValues.addAll(bucket.map { it.sgv })
            }
            if (aboveCount.toDouble() / bucket.size >= OUT_OF_RANGE_FRACTION) {
                highDays++
                highBgValues.addAll(bucket.map { it.sgv })
            }
        }

        if (evaluatedDays >= MIN_EVALUATED_DAYS) {
            if (lowDays >= MIN_FLAGGED_DAYS) {
                out.add(GlucosePattern(
                    startHour = hour, endHour = hour + 1, type = PatternType.LOW,
                    daysDetected = lowDays, daysEvaluated = evaluatedDays,
                    avgBgMgdl = lowBgValues.average(), worstBgMgdl = lowBgValues.min()
                ))
            }
            if (highDays >= MIN_FLAGGED_DAYS) {
                out.add(GlucosePattern(
                    startHour = hour, endHour = hour + 1, type = PatternType.HIGH,
                    daysDetected = highDays, daysEvaluated = evaluatedDays,
                    avgBgMgdl = highBgValues.average(), worstBgMgdl = highBgValues.max()
                ))
            }
        }
    }

    /**
     * Merge adjacent same-type patterns into wider time spans.
     * E.g. HIGH at hours 14, 15, 16 becomes a single HIGH 14–17.
     */
    internal fun mergeAdjacent(raw: List<GlucosePattern>): List<GlucosePattern> {
        if (raw.isEmpty()) return emptyList()

        val sorted = raw.sortedWith(compareBy({ it.type }, { it.startHour }))
        val merged = mutableListOf<GlucosePattern>()
        var current = sorted.first()

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.type == current.type && next.startHour == current.endHour) {
                // Extend: merge stats
                val combinedDaysDetected = maxOf(current.daysDetected, next.daysDetected)
                val combinedDaysEvaluated = maxOf(current.daysEvaluated, next.daysEvaluated)
                val combinedAvg = (current.avgBgMgdl + next.avgBgMgdl) / 2.0
                val combinedWorst = when (current.type) {
                    PatternType.LOW -> minOf(current.worstBgMgdl, next.worstBgMgdl)
                    PatternType.HIGH -> maxOf(current.worstBgMgdl, next.worstBgMgdl)
                }
                current = current.copy(
                    endHour = next.endHour,
                    daysDetected = combinedDaysDetected,
                    daysEvaluated = combinedDaysEvaluated,
                    avgBgMgdl = combinedAvg,
                    worstBgMgdl = combinedWorst
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }
}

enum class PatternType { LOW, HIGH }

data class GlucosePattern(
    val startHour: Int,
    val endHour: Int,
    val type: PatternType,
    val daysDetected: Int,
    val daysEvaluated: Int,
    val avgBgMgdl: Double,
    val worstBgMgdl: Int
) {
    /** Stable identity for dedup hashing — type and time window only. */
    fun stableKey(): String = "$type:$startHour-$endHour"
}

data class PatternResult(
    val patterns: List<GlucosePattern>,
    val analysisDate: LocalDate,
    val lookbackDays: Int
) {
    /** Hash of the pattern set for deduplication. Empty string when no patterns. */
    fun stableHash(): String {
        if (patterns.isEmpty()) return ""
        return patterns.joinToString("|") { it.stableKey() }
    }
}
