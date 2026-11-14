package com.psjostrom.strimma.data

import javax.inject.Inject

/**
 * CGM apps report direction inconsistently, so Strimma computes delta and direction from stored
 * readings. The delta is the raw change from the reading closest to 5 minutes ago, scaled to a
 * 5-minute window. Scaling by the real time between the two readings makes the result independent
 * of the sensor interval: 1-minute Libre data and 5-minute Dexcom data give the same delta for the
 * same rise. AAPS computes its default delta the same way, without smoothing.
 */
class DirectionComputer @Inject constructor() {

    companion object {
        private const val TARGET_MINUTES = 5.0

        // A past reading closer than this would multiply sensor noise when scaled to 5 minutes.
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
        val pastReading = readings
            .filter {
                val minutesAgo = minutesBetween(it, currentReading)
                minutesAgo in MIN_LOOKBACK_MINUTES..MAX_LOOKBACK_MINUTES
            }
            .minByOrNull { kotlin.math.abs(minutesBetween(it, currentReading) - TARGET_MINUTES) }
            ?: return Pair(Direction.NONE, null)

        val deltaMgdl = (currentReading.sgv - pastReading.sgv) * TARGET_MINUTES /
            minutesBetween(pastReading, currentReading)
        val deltaMgdlPerMin = deltaMgdl / TARGET_MINUTES

        val direction = when {
            deltaMgdlPerMin <= THRESHOLD_DOUBLE_DOWN -> Direction.DoubleDown
            deltaMgdlPerMin <= THRESHOLD_SINGLE_DOWN -> Direction.SingleDown
            deltaMgdlPerMin <= THRESHOLD_FORTY_FIVE_DOWN -> Direction.FortyFiveDown
            deltaMgdlPerMin <= THRESHOLD_FORTY_FIVE_UP -> Direction.Flat
            deltaMgdlPerMin <= THRESHOLD_SINGLE_UP -> Direction.FortyFiveUp
            deltaMgdlPerMin <= THRESHOLD_DOUBLE_UP -> Direction.SingleUp
            else -> Direction.DoubleUp
        }

        return Pair(direction, deltaMgdl)
    }

    private fun minutesBetween(past: GlucoseReading, current: GlucoseReading): Double =
        (current.ts - past.ts) / MS_PER_MINUTE.toDouble()
}
