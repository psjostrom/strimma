package com.psjostrom.strimma.data.story

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.MS_PER_MINUTE
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

object FlatlineComputer {
    private const val MAX_CONSECUTIVE_DIFF_MGDL = 2.0
    private const val MIN_FLATLINE_MINUTES = 30
    private const val MIN_READINGS_PER_DAY = 20
    private const val PERCENT = 100

    fun findFlatlines(readings: List<GlucoseReading>): List<FlatlineStretch> {
        if (readings.size < 2) return emptyList()
        val sorted = readings.sortedBy { it.ts }
        val result = mutableListOf<FlatlineStretch>()
        var stretchStart = 0
        var maxDiff = 0.0
        for (i in 1 until sorted.size) {
            val diff = abs(sorted[i].sgv - sorted[i - 1].sgv).toDouble()
            if (diff <= MAX_CONSECUTIVE_DIFF_MGDL) {
                maxDiff = maxOf(maxDiff, diff)
            } else {
                emitIfLongEnough(sorted, stretchStart, i - 1, maxDiff, result)
                stretchStart = i
                maxDiff = 0.0
            }
        }
        emitIfLongEnough(sorted, stretchStart, sorted.lastIndex, maxDiff, result)
        return result
    }

    private fun emitIfLongEnough(
        sorted: List<GlucoseReading>,
        startIdx: Int,
        endIdx: Int,
        maxDiff: Double,
        out: MutableList<FlatlineStretch>
    ) {
        if (startIdx >= endIdx) return
        val durationMin = ((sorted[endIdx].ts - sorted[startIdx].ts) / MS_PER_MINUTE).toInt()
        if (durationMin >= MIN_FLATLINE_MINUTES) {
            out.add(FlatlineStretch(sorted[startIdx].ts, sorted[endIdx].ts, durationMin, maxDiff))
        }
    }

    fun longestInRangeStreak(
        readings: List<GlucoseReading>,
        lowMgdl: Double,
        highMgdl: Double
    ): InRangeStreak? {
        if (readings.isEmpty()) return null
        val sorted = readings.sortedBy { it.ts }
        var bestStart = -1L
        var bestEnd = -1L
        var bestDuration = 0
        var currentStart = -1L
        for (i in sorted.indices) {
            val inRange = sorted[i].sgv >= lowMgdl && sorted[i].sgv <= highMgdl
            if (inRange) {
                if (currentStart == -1L) currentStart = sorted[i].ts
            } else {
                if (currentStart != -1L && i > 0) {
                    val duration = ((sorted[i - 1].ts - currentStart) / MS_PER_MINUTE).toInt()
                    if (duration > bestDuration) {
                        bestStart = currentStart
                        bestEnd = sorted[i - 1].ts
                        bestDuration = duration
                    }
                    currentStart = -1L
                }
            }
        }
        if (currentStart != -1L) {
            val duration = ((sorted.last().ts - currentStart) / MS_PER_MINUTE).toInt()
            if (duration > bestDuration) {
                bestStart = currentStart
                bestEnd = sorted.last().ts
                bestDuration = duration
            }
        }
        return if (bestDuration > 0) InRangeStreak(bestStart, bestEnd, bestDuration) else null
    }

    fun steadiestDay(
        readings: List<GlucoseReading>,
        bgLow: Double,
        bgHigh: Double,
        zone: ZoneId
    ): SteadiestDay? {
        val byDay = readings.groupBy {
            Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate()
        }
        return byDay.entries
            .filter { it.value.size >= MIN_READINGS_PER_DAY }
            .minByOrNull { entry ->
                val values = entry.value.map { it.sgv.toDouble() }
                val mean = values.average()
                val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
                sqrt(variance) / mean * PERCENT
            }?.let { entry ->
                val values = entry.value.map { it.sgv.toDouble() }
                val mean = values.average()
                val stdDev = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
                val cv = stdDev / mean * PERCENT
                val inRange = entry.value.count { it.sgv >= bgLow && it.sgv <= bgHigh }
                val tir = inRange.toDouble() / entry.value.size * PERCENT
                SteadiestDay(entry.key, cv, tir)
            }
    }
}
