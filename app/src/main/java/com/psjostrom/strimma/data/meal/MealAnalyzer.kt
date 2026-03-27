package com.psjostrom.strimma.data.meal

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.IOBComputer
import com.psjostrom.strimma.data.Treatment
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class MealAnalyzer @Inject constructor() {

    companion object {
        private const val PRE_MEAL_WINDOW_MINUTES = 15L
        private const val DEFAULT_WINDOW_MINUTES = 180L
        private const val EXTENDED_WINDOW_MINUTES = 240L
        private const val BASELINE_TOLERANCE_MGDL = 5.0
        private const val MIN_BASELINE_READINGS = 3
        private const val MIN_POSTPRANDIAL_READINGS = 5
        private const val MS_PER_MINUTE = 60_000L
        private const val PERCENT = 100.0
    }

    /**
     * Analyze per-meal postprandial glucose response.
     *
     * @param meal The carb-containing treatment (meal event)
     * @param readings All glucose readings (pre + post meal)
     * @param bgLow Lower bound for TIR (mg/dL)
     * @param bgHigh Upper bound for TIR (mg/dL)
     * @param nextMealTime Timestamp of next meal (cuts window short), or null
     * @param allTreatments All treatments (for IOB computation)
     * @param tauMinutes Insulin time constant (for IOB computation)
     * @return MealPostprandialResult or null if insufficient data
     */
    fun analyze(
        meal: Treatment,
        readings: List<GlucoseReading>,
        bgLow: Double,
        bgHigh: Double,
        nextMealTime: Long?,
        allTreatments: List<Treatment>,
        tauMinutes: Double
    ): MealPostprandialResult? {
        val mealTime = meal.createdAt
        val carbGrams = meal.carbs ?: return null

        // 1. Compute baseline
        val baseline = computeBaseline(readings, mealTime) ?: return null

        // 2. Determine postprandial window
        val windowMinutes = determineWindow(readings, mealTime, baseline, nextMealTime)

        // 3. Extract postprandial readings
        val windowEnd = mealTime + Duration.ofMinutes(windowMinutes).toMillis()
        val postprandialReadings = readings
            .filter { it.ts > mealTime && it.ts <= windowEnd }
            .sortedBy { it.ts }

        if (postprandialReadings.size < MIN_POSTPRANDIAL_READINGS) return null

        // 4. Compute metrics
        val peak = postprandialReadings.maxByOrNull { it.sgv }!!
        val peakMgdl = peak.sgv.toDouble()
        val excursionMgdl = max(0.0, peakMgdl - baseline)
        val timeToPeakMinutes = ((peak.ts - mealTime) / MS_PER_MINUTE).toInt()

        val recoveryMinutes = computeRecovery(postprandialReadings, mealTime, baseline)

        val tirPercent = computeTIR(postprandialReadings, bgLow, bgHigh)

        val iAucMgdlMin = computeIAUC(postprandialReadings, mealTime, baseline)

        val iobAtMeal = IOBComputer.computeIOB(allTreatments, mealTime, tauMinutes)

        return MealPostprandialResult(
            mealTime = mealTime,
            carbGrams = carbGrams,
            baselineMgdl = baseline,
            peakMgdl = peakMgdl,
            excursionMgdl = excursionMgdl,
            timeToPeakMinutes = timeToPeakMinutes,
            recoveryMinutes = recoveryMinutes,
            tirPercent = tirPercent,
            iAucMgdlMin = iAucMgdlMin,
            iobAtMeal = iobAtMeal,
            windowMinutes = windowMinutes.toInt(),
            readings = postprandialReadings
        )
    }

    private fun computeBaseline(readings: List<GlucoseReading>, mealTime: Long): Double? {
        val preWindowStart = mealTime - Duration.ofMinutes(PRE_MEAL_WINDOW_MINUTES).toMillis()
        val preReadings = readings
            .filter { it.ts in preWindowStart until mealTime }
            .sortedBy { it.ts }

        return when {
            preReadings.size >= MIN_BASELINE_READINGS -> {
                // Average of all readings in 15-min window
                preReadings.map { it.sgv }.average()
            }
            preReadings.isNotEmpty() -> {
                // Use single closest reading before meal
                preReadings.last().sgv.toDouble()
            }
            else -> {
                // No pre-meal readings at all
                null
            }
        }
    }

    private fun determineWindow(
        readings: List<GlucoseReading>,
        mealTime: Long,
        baseline: Double,
        nextMealTime: Long?
    ): Long {
        val defaultEnd = mealTime + Duration.ofMinutes(DEFAULT_WINDOW_MINUTES).toMillis()
        val extendedEnd = mealTime + Duration.ofMinutes(EXTENDED_WINDOW_MINUTES).toMillis()

        // Early cutoff if next meal arrives before default window end
        val effectiveEnd = if (nextMealTime != null && nextMealTime < defaultEnd) {
            nextMealTime
        } else {
            defaultEnd
        }

        // Check if BG has returned to baseline at the default window end
        val bgAtDefaultEnd = readings
            .filter { it.ts in (effectiveEnd - MS_PER_MINUTE * 5)..effectiveEnd }
            .lastOrNull()

        // Extend to 4h if BG hasn't recovered at 3h (and no next meal cutoff)
        val shouldExtend = nextMealTime == null &&
            bgAtDefaultEnd != null &&
            !hasReturned(bgAtDefaultEnd.sgv.toDouble(), baseline)

        val finalEnd = if (shouldExtend) extendedEnd else effectiveEnd

        return ((finalEnd - mealTime) / MS_PER_MINUTE)
    }

    private fun computeRecovery(
        postprandialReadings: List<GlucoseReading>,
        mealTime: Long,
        baseline: Double
    ): Int? {
        // Find peak
        val peak = postprandialReadings.maxByOrNull { it.sgv } ?: return null

        // Find first reading after peak that returns to baseline ±5
        val afterPeak = postprandialReadings.filter { it.ts > peak.ts }
        val recoveryReading = afterPeak.firstOrNull { hasReturned(it.sgv.toDouble(), baseline) }

        return recoveryReading?.let {
            ((it.ts - mealTime) / MS_PER_MINUTE).toInt()
        }
    }

    private fun hasReturned(bgMgdl: Double, baseline: Double): Boolean {
        return bgMgdl in (baseline - BASELINE_TOLERANCE_MGDL)..(baseline + BASELINE_TOLERANCE_MGDL)
    }

    private fun computeTIR(
        readings: List<GlucoseReading>,
        bgLow: Double,
        bgHigh: Double
    ): Double {
        if (readings.isEmpty()) return 0.0

        val inRange = readings.count { it.sgv in bgLow.toInt()..bgHigh.toInt() }
        return (inRange.toDouble() / readings.size) * PERCENT
    }

    private fun computeIAUC(
        readings: List<GlucoseReading>,
        mealTime: Long,
        baseline: Double
    ): Double {
        if (readings.size < 2) return 0.0

        var auc = 0.0

        for (i in 0 until readings.size - 1) {
            val r1 = readings[i]
            val r2 = readings[i + 1]

            val deltaMinutes = (r2.ts - r1.ts).toDouble() / MS_PER_MINUTE

            // Height above baseline for each point (clamped to >= 0)
            val h1 = max(0.0, r1.sgv - baseline)
            val h2 = max(0.0, r2.sgv - baseline)

            // Trapezoidal area = (h1 + h2) / 2 * deltaTime
            auc += (h1 + h2) / 2.0 * deltaMinutes
        }

        return auc
    }
}
