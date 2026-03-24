package com.psjostrom.strimma.data

import kotlin.math.sqrt

private const val MS_PER_MINUTE = 60_000.0
private const val MIN_READINGS_FOR_GVP = 3
private const val MAX_GAP_MINUTES = 15.0

data class GlucoseStats(
    val count: Int,
    val averageMgdl: Double,
    val stdDevMgdl: Double,
    val cv: Double,
    val gmi: Double,
    val gvp: Double?,
    val tirPercent: Double,
    val belowPercent: Double,
    val abovePercent: Double,
    val periodLabel: String
)

object StatsCalculator {

    private const val GMI_INTERCEPT = 3.31
    private const val GMI_SLOPE = 0.02392
    private const val PERCENT_MULTIPLIER = 100.0

    fun compute(
        readings: List<GlucoseReading>,
        bgLow: Double,
        bgHigh: Double,
        periodLabel: String
    ): GlucoseStats? {
        if (readings.isEmpty()) return null

        val mgdlValues = readings.map { it.sgv.toDouble() }
        val count = mgdlValues.size
        val avg = mgdlValues.average()
        val variance = mgdlValues.map { d -> (d - avg) * (d - avg) }.average()
        val stdDev = sqrt(variance)
        val cv = if (avg > 0) (stdDev / avg) * PERCENT_MULTIPLIER else 0.0

        // GMI (eHbA1c): ATTD consensus formula (uses mg/dL)
        val gmi = GMI_INTERCEPT + GMI_SLOPE * avg

        val inRange = mgdlValues.count { it in bgLow..bgHigh }
        val below = mgdlValues.count { it < bgLow }
        val above = mgdlValues.count { it > bgHigh }

        return GlucoseStats(
            count = count,
            averageMgdl = avg,
            stdDevMgdl = stdDev,
            cv = cv,
            gmi = gmi,
            gvp = computeGvp(readings),
            tirPercent = (inRange.toDouble() / count) * PERCENT_MULTIPLIER,
            belowPercent = (below.toDouble() / count) * PERCENT_MULTIPLIER,
            abovePercent = (above.toDouble() / count) * PERCENT_MULTIPLIER,
            periodLabel = periodLabel
        )
    }

    /**
     * Glycemic Variability Percentage (Peyser et al. 2018).
     *
     * Measures glucose trace length relative to a perfectly flat line.
     * L = Σ √(Δt² + Δsgv²) using natural units (minutes, mg/dL).
     * GVP = (L / L₀ − 1) × 100, where L₀ = total duration in minutes.
     *
     * Captures both amplitude and frequency of oscillations — CV only
     * captures amplitude. Segments with gaps >15 min are excluded to
     * avoid inflating the trace with sensor warm-up or app restarts.
     *
     * Reference (5-min Dexcom data):
     *   Non-diabetic: ~17% (8–28%)
     *   T2D: ~28% (8–61%)
     *   T1D: ~45% (17–112%)
     */
    fun computeGvp(readings: List<GlucoseReading>): Double? {
        if (readings.size < MIN_READINGS_FOR_GVP) return null

        val sorted = readings.sortedBy { it.ts }
        var traceLength = 0.0
        var idealLength = 0.0

        for (i in 1 until sorted.size) {
            val dtMinutes = (sorted[i].ts - sorted[i - 1].ts) / MS_PER_MINUTE
            if (dtMinutes <= 0 || dtMinutes > MAX_GAP_MINUTES) continue

            val dSgv = (sorted[i].sgv - sorted[i - 1].sgv).toDouble()
            traceLength += sqrt(dtMinutes * dtMinutes + dSgv * dSgv)
            idealLength += dtMinutes
        }

        if (idealLength <= 0) return null
        return (traceLength / idealLength - 1.0) * PERCENT_MULTIPLIER
    }
}
