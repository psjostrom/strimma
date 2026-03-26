package com.psjostrom.strimma.data.calendar

object PreActivityAssessor {

    private const val HYPO_THRESHOLD = 81       // 4.5 mmol/L
    private const val COMPOUND_BG = 144          // 8.0 mmol/L
    private const val HIGH_THRESHOLD = 252       // 14.0 mmol/L
    private const val FORECAST_LOW = 99          // 5.5 mmol/L

    private const val SLOPE_FAST_FALLING = -0.9  // -0.05 mmol/L/min
    private const val SLOPE_FALLING = -0.54      // -0.03 mmol/L/min

    private const val IOB_THRESHOLD = 0.5
    private const val CARBS_PER_UNIT = 12
    private const val CARB_ROUND = 5

    private const val BASE_CARBS_HYPO = 20
    private const val BASE_CARBS_COMPOUND = 20
    private const val BASE_CARBS_LOW = 15

    private const val MAX_CARB_RECOMMENDATION = 60.0

    private const val TIMING_SOON_MS = 45 * 60_000L
    private const val TIMING_IMMINENT_MS = 15 * 60_000L

    data class AssessmentResult(
        val readiness: ReadinessLevel,
        val reasons: List<AssessmentReason>,
        val carbRecommendation: CarbRecommendation?
    )

    @Suppress("CyclomaticComplexMethod", "LongParameterList") // Assessment has many independent dimensions by design
    fun assess(
        currentBgMgdl: Int,
        velocityMgdlPerMin: Double?,
        iob: Double,
        forecastBgAt30minMgdl: Double?,
        timeToWorkoutMs: Long,
        targetLowMgdl: Float,
        @Suppress("UNUSED_PARAMETER") targetHighMgdl: Float
    ): AssessmentResult {
        val reasons = mutableListOf<AssessmentReason>()
        var baseCarbs = 0

        val isCompound = currentBgMgdl < COMPOUND_BG &&
            velocityMgdlPerMin != null && velocityMgdlPerMin < SLOPE_FALLING

        if (isCompound) {
            reasons.add(AssessmentReason(ReadinessLevel.WAIT, "BG below 8 and falling -- high hypo risk"))
            baseCarbs = BASE_CARBS_COMPOUND
        } else {
            when {
                currentBgMgdl < HYPO_THRESHOLD -> {
                    reasons.add(AssessmentReason(ReadinessLevel.WAIT, "BG too low to start"))
                    baseCarbs = BASE_CARBS_HYPO
                }
                currentBgMgdl < targetLowMgdl -> {
                    reasons.add(AssessmentReason(ReadinessLevel.CAUTION, "BG below target"))
                    baseCarbs = BASE_CARBS_LOW
                }
                currentBgMgdl > HIGH_THRESHOLD -> {
                    reasons.add(AssessmentReason(ReadinessLevel.CAUTION, "BG high -- expect steeper drop"))
                }
            }

            if (velocityMgdlPerMin != null) {
                when {
                    velocityMgdlPerMin < SLOPE_FAST_FALLING -> {
                        reasons.add(AssessmentReason(ReadinessLevel.WAIT, "BG dropping fast"))
                    }
                    velocityMgdlPerMin < SLOPE_FALLING -> {
                        reasons.add(AssessmentReason(ReadinessLevel.CAUTION, "BG trending down"))
                    }
                }
            }
        }

        if (forecastBgAt30minMgdl != null && forecastBgAt30minMgdl < FORECAST_LOW) {
            reasons.add(AssessmentReason(ReadinessLevel.CAUTION, "Forecast: BG below 5.5 in 30 min"))
        }

        val iobCarbs = if (iob >= IOB_THRESHOLD) {
            roundToNearest(iob * CARBS_PER_UNIT, CARB_ROUND)
        } else 0

        val totalCarbs = roundToNearest(
            (baseCarbs + iobCarbs).toDouble().coerceAtMost(MAX_CARB_RECOMMENDATION),
            CARB_ROUND
        )

        val carbRecommendation = if (totalCarbs > 0) {
            val timing = when {
                timeToWorkoutMs < TIMING_IMMINENT_MS -> "immediately"
                timeToWorkoutMs < TIMING_SOON_MS -> "now"
                else -> "~30 min before start"
            }
            CarbRecommendation(totalCarbs, timing)
        } else null

        val readiness = when {
            reasons.any { it.level == ReadinessLevel.WAIT } -> ReadinessLevel.WAIT
            reasons.any { it.level == ReadinessLevel.CAUTION } -> ReadinessLevel.CAUTION
            else -> ReadinessLevel.READY
        }

        return AssessmentResult(readiness, reasons, carbRecommendation)
    }

    private fun roundToNearest(value: Double, step: Int): Int {
        return (Math.round(value / step) * step).toInt()
    }
}
