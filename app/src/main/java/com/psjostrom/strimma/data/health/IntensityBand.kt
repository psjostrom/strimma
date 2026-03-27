package com.psjostrom.strimma.data.health

enum class IntensityBand(val label: String) {
    LIGHT("Light"),
    MODERATE("Moderate"),
    INTENSE("Intense");

    companion object {
        fun fromAvgHR(avgHR: Int, maxHR: Int): IntensityBand {
            val fraction = avgHR.toDouble() / maxHR
            return when {
                fraction >= 0.80 -> INTENSE
                fraction >= 0.65 -> MODERATE
                else -> LIGHT
            }
        }
    }
}
