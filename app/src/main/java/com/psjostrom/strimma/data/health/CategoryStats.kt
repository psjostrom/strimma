package com.psjostrom.strimma.data.health

import com.psjostrom.strimma.data.calendar.MetabolicProfile

enum class BGBand(val label: String) {
    LOW("Below low"),
    LOW_RANGE("Low range"),
    MID_RANGE("In range"),
    HIGH("Above range");

    companion object {
        private const val MID_THRESHOLD_MGDL = 126  // 7.0 mmol/L
        private const val HIGH_THRESHOLD_MGDL = 180 // 10.0 mmol/L

        fun fromBG(bgMgdl: Int, bgLowMgdl: Double): BGBand = when {
            bgMgdl < bgLowMgdl -> LOW
            bgMgdl < MID_THRESHOLD_MGDL -> LOW_RANGE
            bgMgdl <= HIGH_THRESHOLD_MGDL -> MID_RANGE
            else -> HIGH
        }
    }
}

data class BandStats(
    val sessionCount: Int,
    val avgMinBG: Double,
    val avgDropRate: Double,
    val hypoRate: Double,
    val avgPostNadir: Double?
)

data class CategoryStats(
    val category: ExerciseCategory,
    val metabolicProfile: MetabolicProfile?,
    val sessionCount: Int,
    val avgEntryBG: Double,
    val avgMinBG: Double,
    val avgDropRate: Double,
    val avgDurationMin: Int,
    val hypoCount: Int,
    val hypoRate: Double,
    val avgPostNadir: Double?,
    val avgPostHighest: Double?,
    val postHypoCount: Int,
    val statsByEntryBand: Map<BGBand, BandStats>
)
