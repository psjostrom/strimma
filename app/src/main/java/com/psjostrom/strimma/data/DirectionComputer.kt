package com.psjostrom.strimma.data

import javax.inject.Inject

class DirectionComputer @Inject constructor() {

    fun compute(readings: List<GlucoseReading>, currentReading: GlucoseReading): Pair<Direction, Double?> {
        // Build full list including current reading, sorted by timestamp ASC
        val allReadings = (readings + currentReading).sortedBy { it.ts }
        val currentIndex = allReadings.indexOfFirst { it.ts == currentReading.ts }

        if (currentIndex == -1) return Pair(Direction.NONE, null)

        // Find reading closest to 5 minutes before current
        val targetTs = currentReading.ts - (5 * 60 * 1000)
        val pastReading = allReadings.take(currentIndex).minByOrNull {
            kotlin.math.abs(it.ts - targetTs)
        }

        // If no reading within 10 minutes, return NONE
        if (pastReading == null || kotlin.math.abs(pastReading.ts - targetTs) > 10 * 60 * 1000) {
            return Pair(Direction.NONE, null)
        }

        val pastIndex = allReadings.indexOf(pastReading)

        // Compute 3-point averaged SGV for both readings
        val avgNow = avgSgv(allReadings, currentIndex)
        val avgPast = avgSgv(allReadings, pastIndex)

        // Calculate time difference in minutes
        val timeMinutes = (currentReading.ts - pastReading.ts) / (60.0 * 1000.0)
        if (timeMinutes == 0.0) return Pair(Direction.NONE, null)

        // Calculate delta in mg/dL per minute
        val deltaMgdlPerMin = (avgNow - avgPast) / timeMinutes

        // Map to Direction using thresholds
        val direction = when {
            deltaMgdlPerMin <= -3.0 -> Direction.DoubleDown
            deltaMgdlPerMin <= -2.0 -> Direction.SingleDown
            deltaMgdlPerMin <= -1.1 -> Direction.FortyFiveDown
            deltaMgdlPerMin <= 1.1 -> Direction.Flat
            deltaMgdlPerMin <= 2.0 -> Direction.FortyFiveUp
            deltaMgdlPerMin <= 3.0 -> Direction.SingleUp
            else -> Direction.DoubleUp
        }

        // Convert delta to mmol/L
        val deltaMmol = (avgNow - avgPast) / 18.0182

        return Pair(direction, deltaMmol)
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
