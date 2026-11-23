package com.psjostrom.strimma.data.meal

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.IOBComputer
import com.psjostrom.strimma.data.Treatment
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

data class MealAnalysisParams(
    val bgLow: Double,
    val bgHigh: Double,
    val nextMealTime: Long?,
    val allTreatments: List<Treatment>,
    val tauMinutes: Double
)

@Singleton
class MealAnalyzer @Inject constructor() {

    companion object {
        private const val PRE_MEAL_WINDOW_MINUTES = 15L
        private const val DEFAULT_WINDOW_MINUTES = 180L
        private const val EXTENDED_WINDOW_MINUTES = 240L
        private const val BASELINE_TOLERANCE_MGDL = 5.0
        private const val MIN_BASELINE_READINGS = 3
        private const val MIN_POSTPRANDIAL_READINGS = 5
        private const val RECOVERY_CHECK_WINDOW_MINUTES = 5L

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
     * @param now Current time; a meal whose window ends after it is not analyzed yet
     * @return MealPostprandialResult or null if insufficient data or the window has not ended
     */
    fun analyze(
        meal: Treatment,
        readings: List<GlucoseReading>,
        params: MealAnalysisParams,
        now: Long = System.currentTimeMillis()
    ): MealPostprandialResult? {
        val mealTime = meal.createdAt
        val carbGrams = meal.carbs ?: return null

        // 1. Compute baseline
        val baseline = computeBaseline(readings, mealTime) ?: return null

        // 2. Determine postprandial window
        val windowMinutes = determineWindow(readings, mealTime, baseline, params.bgLow, params.nextMealTime)

        // 3. Extract postprandial readings. A meal whose window has not ended would show
        // partial TIR, peak and recovery as if they were final, so it waits until the end.
        val windowEnd = mealTime + Duration.ofMinutes(windowMinutes).toMillis()
        val postprandialReadings = readings
            .filter { it.ts > mealTime && it.ts <= windowEnd }
            .sortedBy { it.ts }

        if (windowEnd > now || postprandialReadings.size < MIN_POSTPRANDIAL_READINGS) return null

        // 4. Compute metrics
        val peak = postprandialReadings.maxByOrNull { it.sgv }!!
        val peakMgdl = peak.sgv.toDouble()
        val excursionMgdl = max(0.0, peakMgdl - baseline)
        val timeToPeakMinutes = ((peak.ts - mealTime) / MS_PER_MINUTE).toInt()

        val recoveryMinutes = computeRecovery(postprandialReadings, mealTime, baseline)

        val tirPercent = computeTIR(postprandialReadings, params.bgLow, params.bgHigh)

        val iAucMgdlMin = computeIAUC(postprandialReadings, baseline)

        val iobAtMeal = IOBComputer.computeIOB(params.allTreatments, mealTime, params.tauMinutes)

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
        bgLow: Double,
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
            .filter { it.ts in (effectiveEnd - MS_PER_MINUTE * RECOVERY_CHECK_WINDOW_MINUTES)..effectiveEnd }
            .lastOrNull()

        // Extend toward 4h if BG at 3h has not come back down, or is below the Low threshold,
        // so a low after the meal stays in the window. A next meal before 3h already ended
        // the window; a next meal between 3h and 4h ends the extension.
        val bgAt3h = bgAtDefaultEnd?.sgv?.toDouble()
        val shouldExtend = (nextMealTime == null || nextMealTime >= defaultEnd) &&
            bgAt3h != null &&
            (!hasReturned(bgAt3h, baseline) || bgAt3h < bgLow)

        val finalEnd = if (shouldExtend) minOf(extendedEnd, nextMealTime ?: extendedEnd) else effectiveEnd

        return ((finalEnd - mealTime) / MS_PER_MINUTE)
    }

    private fun computeRecovery(
        postprandialReadings: List<GlucoseReading>,
        mealTime: Long,
        baseline: Double
    ): Int? {
        // A meal without a rise has nothing to recover from.
        val peak = postprandialReadings.maxByOrNull { it.sgv } ?: return null
        if (hasReturned(peak.sgv.toDouble(), baseline)) return null

        // The first reading after the peak that is back down near the pre-meal level, and
        // whose next reading is too. One low reading, such as sensor noise or a
        // compression low, does not end recovery.
        val afterPeak = postprandialReadings.filter { it.ts > peak.ts }
        val recoveryReading = afterPeak.zipWithNext()
            .firstOrNull { (reading, next) ->
                hasReturned(reading.sgv.toDouble(), baseline) && hasReturned(next.sgv.toDouble(), baseline)
            }
            ?.first

        return recoveryReading?.let {
            ((it.ts - mealTime) / MS_PER_MINUTE).toInt()
        }
    }

    // Glucose has recovered once it is at most 5 mg/dL (0.3 mmol/L) above the pre-meal
    // level. Falling below the pre-meal level also counts: a reading that drops past the
    // baseline between two samples has recovered.
    private fun hasReturned(bgMgdl: Double, baseline: Double): Boolean {
        return bgMgdl <= baseline + BASELINE_TOLERANCE_MGDL
    }

    private fun computeTIR(
        readings: List<GlucoseReading>,
        bgLow: Double,
        bgHigh: Double
    ): Double {
        if (readings.isEmpty()) return 0.0

        val inRange = readings.count { it.sgv.toDouble() in bgLow..bgHigh }
        return (inRange.toDouble() / readings.size) * PERCENT
    }

    private fun computeIAUC(
        readings: List<GlucoseReading>,
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
