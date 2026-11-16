package com.psjostrom.strimma.data

import javax.inject.Inject

/**
 * CGM apps report direction inconsistently, so Strimma computes delta and direction from stored
 * readings. The delta is the change over 5 minutes, the convention every CGM app shares.
 *
 * Each end of the delta is the average of the readings in the 2.5 minutes up to it, the window
 * Nightscout uses for its newest bucket. On 1-minute sensors this averages about 3 readings per end
 * and calms the arrow. On 5-minute sensors each window holds one reading, so the delta is the raw
 * change. The difference is divided by the real time between the two windows' average timestamps,
 * so 1-minute and 5-minute data give the same delta for the same rise. Nightscout skips that
 * division, which makes its delta about 20% low on 1-minute data.
 */
class DirectionComputer @Inject constructor() {

    companion object {
        private const val TARGET_MINUTES = 5.0
        private const val WINDOW_MINUTES = 2.5

        // Ends closer than this would multiply sensor noise when scaled to 5 minutes.
        private const val MIN_LOOKBACK_MINUTES = 2.5
        private const val MAX_LOOKBACK_MINUTES = 15.0

        // EASD/ISPAD direction thresholds (mg/dL per minute)
        private const val THRESHOLD_DOUBLE_DOWN = -3.0
        private const val THRESHOLD_SINGLE_DOWN = -2.0
        private const val THRESHOLD_FORTY_FIVE_DOWN = -1.1
        private const val THRESHOLD_FORTY_FIVE_UP = 1.1
        private const val THRESHOLD_SINGLE_UP = 2.0
        private const val THRESHOLD_DOUBLE_UP = 3.0
    }

    /** Returns the direction and the delta in mg/dL per 5 minutes, or NONE and null without a usable past reading. */
    fun compute(readings: List<GlucoseReading>, currentReading: GlucoseReading): Pair<Direction, Double?> {
        val history = readings.filter { it.ts < currentReading.ts }
        val pastReading = history
            .filter { minutesBetween(it, currentReading) in MIN_LOOKBACK_MINUTES..MAX_LOOKBACK_MINUTES }
            .minByOrNull { kotlin.math.abs(minutesBetween(it, currentReading) - TARGET_MINUTES) }
            ?: return Pair(Direction.NONE, null)

        val now = windowAverage(history + currentReading, currentReading)
        val past = windowAverage(history, pastReading)
        val minutes = (now.ts - past.ts) / MS_PER_MINUTE
        if (minutes < MIN_LOOKBACK_MINUTES) return Pair(Direction.NONE, null)

        val deltaMgdl = (now.sgv - past.sgv) * TARGET_MINUTES / minutes
        return Pair(directionFor(deltaMgdl / TARGET_MINUTES), deltaMgdl)
    }

    private class Average(val sgv: Double, val ts: Double)

    /** Average SGV and timestamp of the readings in the [WINDOW_MINUTES] up to and including [end]. */
    private fun windowAverage(readings: List<GlucoseReading>, end: GlucoseReading): Average {
        val window = readings.filter { it.ts <= end.ts && minutesBetween(it, end) < WINDOW_MINUTES }
        return Average(window.map { it.sgv }.average(), window.map { it.ts.toDouble() }.average())
    }

    private fun directionFor(mgdlPerMin: Double): Direction = when {
        mgdlPerMin <= THRESHOLD_DOUBLE_DOWN -> Direction.DoubleDown
        mgdlPerMin <= THRESHOLD_SINGLE_DOWN -> Direction.SingleDown
        mgdlPerMin <= THRESHOLD_FORTY_FIVE_DOWN -> Direction.FortyFiveDown
        mgdlPerMin <= THRESHOLD_FORTY_FIVE_UP -> Direction.Flat
        mgdlPerMin <= THRESHOLD_SINGLE_UP -> Direction.FortyFiveUp
        mgdlPerMin <= THRESHOLD_DOUBLE_UP -> Direction.SingleUp
        else -> Direction.DoubleUp
    }

    private fun minutesBetween(past: GlucoseReading, current: GlucoseReading): Double =
        (current.ts - past.ts) / MS_PER_MINUTE.toDouble()
}
