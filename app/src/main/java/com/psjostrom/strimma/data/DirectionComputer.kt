package com.psjostrom.strimma.data

import javax.inject.Inject

class DirectionComputer @Inject constructor() {

    companion object {
        private const val LOOKBACK_MINUTES = 5
        private const val MAX_TIME_GAP_MINUTES = 10
        private const val MINUTES_TO_MS = 60 * 1000L

        // EASD/ISPAD direction thresholds (mg/dL per minute)
        private const val THRESHOLD_DOUBLE_DOWN = -3.0
        private const val THRESHOLD_SINGLE_DOWN = -2.0
        private const val THRESHOLD_FORTY_FIVE_DOWN = -1.1
        private const val THRESHOLD_FORTY_FIVE_UP = 1.1
        private const val THRESHOLD_SINGLE_UP = 2.0
        private const val THRESHOLD_DOUBLE_UP = 3.0
    }

    fun compute(readings: List<GlucoseReading>, currentReading: GlucoseReading): Pair<Direction, Double?> {
        // Build full list including current reading, sorted by timestamp ASC
        val allReadings = (readings + currentReading).sortedBy { it.ts }
        val currentIndex = allReadings.indexOfFirst { it.ts == currentReading.ts }

        if (currentIndex == -1) return Pair(Direction.NONE, null)

        // Find reading closest to 5 minutes before current
        val targetTs = currentReading.ts - (LOOKBACK_MINUTES * MINUTES_TO_MS)
        val pastReading = allReadings.take(currentIndex).minByOrNull {
            kotlin.math.abs(it.ts - targetTs)
        }

        // If no reading within 10 minutes, return NONE
        if (pastReading == null || kotlin.math.abs(pastReading.ts - targetTs) > MAX_TIME_GAP_MINUTES * MINUTES_TO_MS) {
            return Pair(Direction.NONE, null)
        }

        val pastIndex = allReadings.indexOf(pastReading)

        // Compute 3-point averaged SGV for both readings
        val avgNow = avgSgv(allReadings, currentIndex)
        val avgPast = avgSgv(allReadings, pastIndex)

        // Calculate time difference in minutes
        val timeMinutes = (currentReading.ts - pastReading.ts) / MINUTES_TO_MS.toDouble()
        if (timeMinutes == 0.0) return Pair(Direction.NONE, null)

        // Calculate delta in mg/dL per minute
        val deltaMgdlPerMin = (avgNow - avgPast) / timeMinutes

        // Map to Direction using thresholds
        val direction = when {
            deltaMgdlPerMin <= THRESHOLD_DOUBLE_DOWN -> Direction.DoubleDown
            deltaMgdlPerMin <= THRESHOLD_SINGLE_DOWN -> Direction.SingleDown
            deltaMgdlPerMin <= THRESHOLD_FORTY_FIVE_DOWN -> Direction.FortyFiveDown
            deltaMgdlPerMin <= THRESHOLD_FORTY_FIVE_UP -> Direction.Flat
            deltaMgdlPerMin <= THRESHOLD_SINGLE_UP -> Direction.FortyFiveUp
            deltaMgdlPerMin <= THRESHOLD_DOUBLE_UP -> Direction.SingleUp
            else -> Direction.DoubleUp
        }

        // Delta in mg/dL (total change over the lookback window)
        val deltaMgdl = avgNow - avgPast

        return Pair(direction, deltaMgdl)
    }

    private fun avgSgv(readings: List<GlucoseReading>, index: Int): Double {
        val prevIndex = (index - 1).coerceAtLeast(0)
        val nextIndex = (index + 1).coerceAtMost(readings.lastIndex)

        val values = mutableListOf(readings[index].sgv)
        if (prevIndex != index) values.add(readings[prevIndex].sgv)
        if (nextIndex != index) values.add(readings[nextIndex].sgv)

        return values.average()
    }
}
