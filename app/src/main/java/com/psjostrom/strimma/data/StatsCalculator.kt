package com.psjostrom.strimma.data

import kotlin.math.sqrt

data class GlucoseStats(
    val count: Int,
    val averageMgdl: Double,
    val stdDevMgdl: Double,
    val cv: Double,
    val gmi: Double,
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
            tirPercent = (inRange.toDouble() / count) * PERCENT_MULTIPLIER,
            belowPercent = (below.toDouble() / count) * PERCENT_MULTIPLIER,
            abovePercent = (above.toDouble() / count) * PERCENT_MULTIPLIER,
            periodLabel = periodLabel
        )
    }
}
