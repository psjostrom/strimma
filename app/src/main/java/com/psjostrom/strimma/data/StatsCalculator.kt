package com.psjostrom.strimma.data

import kotlin.math.sqrt

data class GlucoseStats(
    val count: Int,
    val averageMmol: Double,
    val stdDevMmol: Double,
    val cv: Double,
    val gmi: Double,
    val tirPercent: Double,
    val belowPercent: Double,
    val abovePercent: Double,
    val periodLabel: String
)

object StatsCalculator {

    private const val MGDL_CONVERSION = 18.0182
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

        val mmolValues = readings.map { it.mmol }
        val count = mmolValues.size
        val avg = mmolValues.average()
        val variance = mmolValues.map { d -> (d - avg) * (d - avg) }.average()
        val stdDev = sqrt(variance)
        val cv = if (avg > 0) (stdDev / avg) * PERCENT_MULTIPLIER else 0.0

        // GMI (eHbA1c): ATTD consensus formula
        val avgMgdl = avg * MGDL_CONVERSION
        val gmi = GMI_INTERCEPT + GMI_SLOPE * avgMgdl

        val inRange = mmolValues.count { it in bgLow..bgHigh }
        val below = mmolValues.count { it < bgLow }
        val above = mmolValues.count { it > bgHigh }

        return GlucoseStats(
            count = count,
            averageMmol = avg,
            stdDevMmol = stdDev,
            cv = cv,
            gmi = gmi,
            tirPercent = (inRange.toDouble() / count) * PERCENT_MULTIPLIER,
            belowPercent = (below.toDouble() / count) * PERCENT_MULTIPLIER,
            abovePercent = (above.toDouble() / count) * PERCENT_MULTIPLIER,
            periodLabel = periodLabel
        )
    }
}
