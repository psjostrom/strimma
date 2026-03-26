package com.psjostrom.strimma.data.calendar

enum class WorkoutCategory(
    val keywords: List<String>,
    val defaultTargetLowMgdl: Float,
    val defaultTargetHighMgdl: Float
) {
    INTERVAL(
        listOf("interval", "tempo", "fartlek", "threshold", "speed"),
        defaultTargetLowMgdl = 162f, // 9 mmol/L
        defaultTargetHighMgdl = 198f // 11 mmol/L
    ),
    LONG(
        listOf("long", "lsr", "90min", "2h", "marathon"),
        defaultTargetLowMgdl = 144f, // 8 mmol/L
        defaultTargetHighMgdl = 180f // 10 mmol/L
    ),
    STRENGTH(
        listOf("gym", "strength", "weights", "core", "lift"),
        defaultTargetLowMgdl = 126f, // 7 mmol/L
        defaultTargetHighMgdl = 162f // 9 mmol/L
    ),
    EASY(
        listOf("easy", "recovery", "walk"),
        defaultTargetLowMgdl = 126f, // 7 mmol/L
        defaultTargetHighMgdl = 162f // 9 mmol/L
    ),
    FALLBACK(
        emptyList(),
        defaultTargetLowMgdl = 126f, // 7 mmol/L
        defaultTargetHighMgdl = 180f // 10 mmol/L
    );

    init {
        // 81f = 4.5 mmol/L, matches PreActivityAssessor.HYPO_THRESHOLD
        @Suppress("MagicNumber")
        val hypoFloor = 81f
        require(defaultTargetLowMgdl >= hypoFloor) { "$name: target low must be >= $hypoFloor mg/dL" }
        require(defaultTargetLowMgdl < defaultTargetHighMgdl) { "$name: target low must be < high" }
    }

    companion object {
        fun fromTitle(title: String): WorkoutCategory {
            val lower = title.lowercase()
            for (category in entries) {
                if (category.keywords.any { lower.contains(it) }) return category
            }
            return FALLBACK
        }
    }
}
