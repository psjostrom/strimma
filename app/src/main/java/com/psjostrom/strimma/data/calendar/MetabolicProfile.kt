package com.psjostrom.strimma.data.calendar

enum class MetabolicProfile(
    val defaultTargetLowMgdl: Float,
    val defaultTargetHighMgdl: Float
) {
    AEROBIC(126f, 180f),           // 7-10 mmol/L — ADA/Riddell consensus
    HIGH_INTENSITY(144f, 216f),    // 8-12 mmol/L — lower hypo risk, may spike
    RESISTANCE(126f, 180f);        // 7-10 mmol/L — similar to aerobic, delayed effect

    companion object {
        private val HIGH_INTENSITY_KEYWORDS = listOf(
            "interval", "tempo", "threshold", "speed", "fartlek", "hiit", "sprint",
            "intervall" // Swedish
        )

        /**
         * Detect intensity override from calendar event title (planned workouts).
         * Returns null if no keywords match, so the caller falls back to
         * [com.psjostrom.strimma.data.health.ExerciseCategory.defaultMetabolicProfile].
         *
         * Completed exercises use [CategoryStatsCalculator.resolveProfile] instead,
         * which has actual HR data for intensity detection.
         */
        fun fromKeywords(title: String): MetabolicProfile? {
            val lower = title.lowercase()
            if (HIGH_INTENSITY_KEYWORDS.any { lower.contains(it) }) return HIGH_INTENSITY
            return null
        }
    }
}
