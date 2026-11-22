package com.psjostrom.strimma.data

import java.time.Instant
import java.time.ZoneId

/**
 * Lying on a CGM sensor squeezes the tissue around it, and the sensor then reports a false low.
 * The trace falls faster than insulin can drive glucose down, and it rebounds as soon as the
 * pressure goes away. These episodes are artifacts, so statistics must not count them as lows.
 *
 * This detector finds those V-shaped episodes after the rebound. [StatsReadings] calls [detect] and
 * leaves the readings of an episode out of statistics, Story and pattern detection, so they count as
 * missing data. The main graph marks the spans that [StatsReadings] returns. Alerts do not use this
 * detector: a low alert must fire before anyone can know whether the low is real.
 *
 * The glucose curve alone cannot tell a compression low from a real low treated with sweets that
 * nobody logged: both fall, sit at the bottom and climb back fast. People lie on the sensor while
 * they sleep, so the detector only flags lows whose nadir falls between 23:00 and 09:00 local time.
 * It also never flags a low with carbs logged near the nadir, or a low during exercise.
 *
 * The fall is measured into the first reading at the nadir and the rebound out of the last one.
 * A compression low often sits on the sensor's 40 mg/dL floor for many minutes, and a slow drift
 * often comes before the fall, so rates measured from the window peaks would miss it.
 *
 * The thresholds come from 125 days of Libre 3 data, mostly at 1-minute intervals. Without the
 * night window the rule flagged 56 of 356 lows. The person wearing the sensor confirmed that the
 * 6 daytime lows among them were not compression lows. A limit longer than 30 minutes below
 * 70 mg/dL flagged no further lows.
 */
object CompressionLowDetector {

    private const val LOW_MGDL = 70
    private const val MS_PER_MINUTE = 60_000L
    private const val WINDOW_MS = 30 * MS_PER_MINUTE
    private const val CARB_WINDOW_MS = 20 * MS_PER_MINUTE
    private const val EPISODE_MERGE_GAP_MS = 15 * MS_PER_MINUTE
    private const val MIN_WINDOW_READINGS = 4
    private const val MIN_MOVE_MGDL = 30
    private const val MIN_FALL_MGDL_PER_MIN = 3.0
    private const val MIN_RISE_MGDL_PER_MIN = 3.0
    private const val MIN_RECOVERY_FRACTION = 0.6
    private const val MAX_BELOW_LOW_MS = 30 * MS_PER_MINUTE
    private const val MIN_SPAN_STEP_MGDL_PER_MIN = 1.5
    private const val NIGHT_START_HOUR = 23
    private const val NIGHT_END_HOUR = 9

    /**
     * Returns the time span of each probable compression low. Both ends are inclusive.
     *
     * [carbTimes] are the timestamps of carb treatments. [exercisePeriods] are the time spans of
     * exercise sessions. [zone] decides which hours are night.
     */
    fun detect(
        readings: List<GlucoseReading>,
        carbTimes: List<Long> = emptyList(),
        exercisePeriods: List<LongRange> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<LongRange> {
        val sorted = readings.sortedBy { it.ts }
        return lowEpisodes(sorted)
            .mapNotNull { episode -> compressionSpan(sorted, episode, carbTimes, zone) }
            .filter { span -> exercisePeriods.none { it.first <= span.last && span.first <= it.last } }
    }

    /** Groups readings below [LOW_MGDL] into episodes. A gap over 15 minutes between two low readings starts a new episode. */
    private fun lowEpisodes(sorted: List<GlucoseReading>): List<List<GlucoseReading>> {
        val episodes = mutableListOf<MutableList<GlucoseReading>>()
        for (r in sorted) {
            if (r.sgv >= LOW_MGDL) continue
            val current = episodes.lastOrNull()
            if (current != null && r.ts - current.last().ts <= EPISODE_MERGE_GAP_MS) {
                current.add(r)
            } else {
                episodes.add(mutableListOf(r))
            }
        }
        return episodes
    }

    private fun compressionSpan(
        sorted: List<GlucoseReading>,
        episode: List<GlucoseReading>,
        carbTimes: List<Long>,
        zone: ZoneId
    ): LongRange? {
        val nadir = episode.minOf { it.sgv }
        val nadirFirst = episode.first { it.sgv == nadir }
        val nadirLast = episode.last { it.sgv == nadir }
        val hour = Instant.ofEpochMilli(nadirFirst.ts).atZone(zone).hour
        val isNight = hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
        val isShort = episode.last().ts - episode.first().ts <= MAX_BELOW_LOW_MS
        // The episode is over only once a reading at or above LOW_MGDL follows its last low reading.
        val next = sorted.getOrNull(sorted.binarySearchBy(episode.last().ts) { it.ts } + 1)
        val hasEnded = next != null && next.sgv >= LOW_MGDL
        val isTreated = carbTimes.any { it in (nadirFirst.ts - CARB_WINDOW_MS)..(nadirLast.ts + CARB_WINDOW_MS) }
        val isCompression = isNight && isShort && hasEnded && !isTreated &&
            hasCompressionShape(sorted, nadir, nadirFirst, nadirLast)
        return if (isCompression) span(sorted, episode, nadirFirst, nadirLast) else null
    }

    private fun hasCompressionShape(
        sorted: List<GlucoseReading>,
        nadir: Int,
        nadirFirst: GlucoseReading,
        nadirLast: GlucoseReading
    ): Boolean {
        val before = window(sorted, nadirFirst.ts - WINDOW_MS, nadirFirst.ts - 1)
        val after = window(sorted, nadirLast.ts + 1, nadirLast.ts + WINDOW_MS)
        if (before.size < MIN_WINDOW_READINGS || after.size < MIN_WINDOW_READINGS) return false

        // The steepest fall into the nadir and the steepest rise out of it. A reading counts only
        // when it lies at least MIN_MOVE_MGDL above the nadir, so sensor noise next to the nadir
        // cannot pass as a steep slope.
        val fallRate = before.filter { it.sgv - nadir >= MIN_MOVE_MGDL }
            .maxOfOrNull { (it.sgv - nadir) / minutesBetween(it.ts, nadirFirst.ts) } ?: 0.0
        val riseRate = after.filter { it.sgv - nadir >= MIN_MOVE_MGDL }
            .maxOfOrNull { (it.sgv - nadir) / minutesBetween(nadirLast.ts, it.ts) } ?: 0.0
        val recovery = (after.maxOf { it.sgv } - nadir).toDouble() / maxOf(1, before.maxOf { it.sgv } - nadir)

        return fallRate >= MIN_FALL_MGDL_PER_MIN &&
            riseRate >= MIN_RISE_MGDL_PER_MIN &&
            recovery >= MIN_RECOVERY_FRACTION
    }

    /**
     * The span reaches from the nadir out to where the steep fall starts and the steep rebound ends.
     * A slow drift before the fall and a slow overshoot after the rebound stay in the statistics.
     * The span always covers every low reading of the episode.
     */
    private fun span(
        sorted: List<GlucoseReading>,
        episode: List<GlucoseReading>,
        nadirFirst: GlucoseReading,
        nadirLast: GlucoseReading
    ): LongRange {
        var startIndex = sorted.binarySearchBy(nadirFirst.ts) { it.ts }
        while (startIndex > 0 && isSteepStep(sorted[startIndex], sorted[startIndex - 1])) startIndex--
        var endIndex = sorted.binarySearchBy(nadirLast.ts) { it.ts }
        while (endIndex < sorted.lastIndex && isSteepStep(sorted[endIndex], sorted[endIndex + 1])) endIndex++
        return minOf(sorted[startIndex].ts, episode.first().ts)..maxOf(sorted[endIndex].ts, episode.last().ts)
    }

    /** True when [outer], the reading further from the nadir, is higher than [inner] by a steep slope. */
    private fun isSteepStep(inner: GlucoseReading, outer: GlucoseReading): Boolean {
        val minutes = minutesBetween(minOf(inner.ts, outer.ts), maxOf(inner.ts, outer.ts))
        return (outer.sgv - inner.sgv) / minutes >= MIN_SPAN_STEP_MGDL_PER_MIN
    }

    /** Returns the readings with [from] <= ts <= [to]. [sorted] must be ordered by ts. */
    private fun window(sorted: List<GlucoseReading>, from: Long, to: Long): List<GlucoseReading> {
        val start = sorted.binarySearchBy(from) { it.ts }.let { if (it < 0) -it - 1 else it }
        val end = sorted.binarySearchBy(to) { it.ts }.let { if (it < 0) -it - 1 else it + 1 }
        return sorted.subList(start, end)
    }

    private fun minutesBetween(from: Long, to: Long): Double =
        maxOf(1.0, (to - from).toDouble() / MS_PER_MINUTE)
}
