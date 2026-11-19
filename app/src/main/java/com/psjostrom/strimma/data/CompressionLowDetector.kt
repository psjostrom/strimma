package com.psjostrom.strimma.data

/**
 * Lying on a CGM sensor squeezes the tissue around it, and the sensor then reports a false low.
 * The trace falls faster than insulin can drive glucose down, and it rebounds as soon as the
 * pressure goes away. These episodes are artifacts, so statistics must not count them as lows.
 *
 * This detector finds those V-shaped episodes after the rebound. [StatsReadings] leaves the readings
 * of an episode out of statistics, Story and pattern detection, so they count as missing data. The
 * main graph calls [detect] to mark the episodes. Alerts do not use this detector: a low alert must
 * fire before anyone can know whether the low is real.
 *
 * A low with carbs logged near the nadir is a low that someone treated, and a low during exercise
 * can fall and rebound fast for real. The detector never flags either.
 *
 * The thresholds come from 120 days of 1-minute Libre 3 data. A rule that requires a fast fall,
 * a fast rebound and a short time below 70 mg/dL flagged 28 of 342 lows. All 28 started between
 * 23:45 and 08:48, and none had carbs logged within 20 minutes, against 18% of the other lows.
 */
object CompressionLowDetector {

    private const val LOW_MGDL = 70
    private const val MS_PER_MINUTE = 60_000L
    private const val WINDOW_MS = 30 * MS_PER_MINUTE
    private const val CARB_WINDOW_MS = 20 * MS_PER_MINUTE
    private const val EPISODE_MERGE_GAP_MS = 15 * MS_PER_MINUTE
    private const val MIN_WINDOW_READINGS = 5
    private const val MIN_FALL_MGDL_PER_MIN = 3.0
    private const val MIN_RISE_MGDL_PER_MIN = 3.0
    private const val MIN_RECOVERY_FRACTION = 0.6
    private const val MAX_BELOW_LOW_MS = 20 * MS_PER_MINUTE
    private const val MIN_SPAN_STEP_MGDL_PER_MIN = 1.5

    /**
     * Returns the time span of each probable compression low. Both ends are inclusive.
     *
     * [carbTimes] are the timestamps of carb treatments. [exercisePeriods] are the time spans of
     * exercise sessions.
     */
    fun detect(
        readings: List<GlucoseReading>,
        carbTimes: List<Long> = emptyList(),
        exercisePeriods: List<LongRange> = emptyList()
    ): List<LongRange> {
        val sorted = readings.sortedBy { it.ts }
        return lowEpisodes(sorted)
            .mapNotNull { episode -> compressionSpan(sorted, episode, carbTimes) }
            .filter { span -> exercisePeriods.none { it.first <= span.last && span.first <= it.last } }
    }

    /** Returns [readings] without the readings that lie inside a probable compression low. */
    fun withoutCompressionLows(
        readings: List<GlucoseReading>,
        carbTimes: List<Long> = emptyList(),
        exercisePeriods: List<LongRange> = emptyList()
    ): List<GlucoseReading> {
        val spans = detect(readings, carbTimes, exercisePeriods)
        if (spans.isEmpty()) return readings
        return readings.filter { r -> spans.none { r.ts in it } }
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
        carbTimes: List<Long>
    ): LongRange? {
        if (episode.last().ts - episode.first().ts > MAX_BELOW_LOW_MS) return null

        val nadir = episode.minBy { it.sgv }
        if (carbTimes.any { it in (nadir.ts - CARB_WINDOW_MS)..(nadir.ts + CARB_WINDOW_MS) }) return null

        val before = window(sorted, nadir.ts - WINDOW_MS, nadir.ts)
        val after = window(sorted, nadir.ts, nadir.ts + WINDOW_MS)
        if (before.size < MIN_WINDOW_READINGS || after.size < MIN_WINDOW_READINGS) return null

        // The last reading at the pre-fall peak and the first reading at the rebound peak.
        val maxBefore = before.maxOf { it.sgv }
        val maxAfter = after.maxOf { it.sgv }
        val peakBefore = before.last { it.sgv == maxBefore }
        val peakAfter = after.first { it.sgv == maxAfter }

        val drop = peakBefore.sgv - nadir.sgv
        val rebound = peakAfter.sgv - nadir.sgv
        val fallRate = drop / minutesBetween(peakBefore.ts, nadir.ts)
        val riseRate = rebound / minutesBetween(nadir.ts, peakAfter.ts)
        val recovery = rebound.toDouble() / maxOf(1, drop)

        val isCompression = fallRate >= MIN_FALL_MGDL_PER_MIN &&
            riseRate >= MIN_RISE_MGDL_PER_MIN &&
            recovery >= MIN_RECOVERY_FRACTION
        if (!isCompression) return null

        // The span reaches from the nadir out to where the steep fall starts and the steep rebound
        // ends. A slow drift before the fall and a slow overshoot after the rebound stay in the
        // statistics. The span always covers every low reading of the episode.
        val nadirIndex = sorted.binarySearchBy(nadir.ts) { it.ts }
        var startIndex = nadirIndex
        while (startIndex > 0 && isSteepStep(sorted[startIndex], sorted[startIndex - 1])) startIndex--
        var endIndex = nadirIndex
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
