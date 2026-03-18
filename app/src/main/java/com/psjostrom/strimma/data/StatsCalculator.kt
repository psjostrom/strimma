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
        val cv = if (avg > 0) (stdDev / avg) * 100.0 else 0.0

        // GMI (eHbA1c): ATTD consensus formula
        val avgMgdl = avg * 18.0182
        val gmi = 3.31 + 0.02392 * avgMgdl

        val inRange = mmolValues.count { it in bgLow..bgHigh }
        val below = mmolValues.count { it < bgLow }
        val above = mmolValues.count { it > bgHigh }

        return GlucoseStats(
            count = count,
            averageMmol = avg,
            stdDevMmol = stdDev,
            cv = cv,
            gmi = gmi,
            tirPercent = (inRange.toDouble() / count) * 100,
            belowPercent = (below.toDouble() / count) * 100,
            abovePercent = (above.toDouble() / count) * 100,
            periodLabel = periodLabel
        )
    }
}
