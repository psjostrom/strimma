package com.psjostrom.strimma.data.calendar

enum class ReadinessLevel { READY, CAUTION, WAIT }

data class AssessmentReason(val level: ReadinessLevel, val message: String)

data class CarbRecommendation(val totalGrams: Int, val timingSuggestion: String)

sealed class GuidanceState {
    data object NoWorkout : GuidanceState()
    data class WorkoutApproaching(
        val event: WorkoutEvent,
        val readiness: ReadinessLevel,
        val reasons: List<AssessmentReason>,
        val suggestions: List<String>,
        val carbRecommendation: CarbRecommendation?,
        val targetLowMgdl: Float,
        val targetHighMgdl: Float,
        val currentBgMgdl: Int,
        val trendArrow: String,
        val iob: Double
    ) : GuidanceState()
}
