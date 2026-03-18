package com.psjostrom.strimma.graph

import com.psjostrom.strimma.data.GlucoseReading

enum class CrossingType { LOW, HIGH }

data class PredictionPoint(val minuteOffset: Int, val mmol: Double)

data class ThresholdCrossing(
    val type: CrossingType,
    val minutesUntil: Int,
    val mmolAtCrossing: Double
)

data class Prediction(
    val points: List<PredictionPoint>,
    val crossing: ThresholdCrossing?,
    val anchorTs: Long,
    val anchorMmol: Double
)

object PredictionComputer {

    private const val LOOKBACK_MS = 12 * 60_000L
    private const val MMOL_FLOOR = 1.0
    private const val MMOL_CEILING = 30.0

    fun compute(
        readings: List<GlucoseReading>,
        horizonMinutes: Int,
        bgLow: Double,
        bgHigh: Double
    ): Prediction? {
        val now = readings.maxOfOrNull { it.ts } ?: return null
        val recent = readings.filter { it.ts >= now - LOOKBACK_MS }.sortedBy { it.ts }
        if (recent.size < 2) return null

        val anchor = recent.last()
        // Normalize to minutes relative to anchor for numerical stability
        val points = recent.map { (it.ts - anchor.ts).toDouble() / 60_000.0 to it.mmol }

        val model = fitBestModel(points) ?: return null

        val predictionPoints = (1..horizonMinutes).map { m ->
            PredictionPoint(m, model(m.toDouble()).coerceIn(MMOL_FLOOR, MMOL_CEILING))
        }

        val crossing = findCrossing(model, horizonMinutes, bgLow, bgHigh, anchor.mmol)

        return Prediction(predictionPoints, crossing, anchor.ts, anchor.mmol)
    }

    internal fun fitBestModel(points: List<Pair<Double, Double>>): ((Double) -> Double)? {
        if (points.size < 2) return null

        val linear = fitLinear(points) ?: return null
        if (points.size < 4) return linear

        val quadratic = fitQuadratic(points)
        if (quadratic == null) return linear

        // Compare residual sum of squares
        val linRSS = points.sumOf { (t, y) -> val e = linear(t) - y; e * e }
        val quadRSS = points.sumOf { (t, y) -> val e = quadratic(t) - y; e * e }

        // Prefer quadratic only if it meaningfully improves fit (>10% reduction)
        // and the curvature isn't extreme (prevents wild extrapolations)
        return if (quadRSS < linRSS * 0.9) quadratic else linear
    }

    internal fun fitLinear(points: List<Pair<Double, Double>>): ((Double) -> Double)? {
        val n = points.size.toDouble()
        var sumT = 0.0; var sumY = 0.0; var sumTT = 0.0; var sumTY = 0.0
        for ((t, y) in points) {
            sumT += t; sumY += y; sumTT += t * t; sumTY += t * y
        }
        val denom = n * sumTT - sumT * sumT
        if (denom == 0.0) return null
        val b = (n * sumTY - sumT * sumY) / denom
        val a = (sumY - b * sumT) / n
        return { t -> a + b * t }
    }

    internal fun fitQuadratic(points: List<Pair<Double, Double>>): ((Double) -> Double)? {
        if (points.size < 3) return null
        val n = points.size.toDouble()

        // Normal equations for y = a + b*t + c*t²
        var s0 = n; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var r0 = 0.0; var r1 = 0.0; var r2 = 0.0
        for ((t, y) in points) {
            val t2 = t * t; val t3 = t2 * t; val t4 = t3 * t
            s1 += t; s2 += t2; s3 += t3; s4 += t4
            r0 += y; r1 += t * y; r2 += t2 * y
        }

        // Solve 3x3 system via Cramer's rule
        // | s0 s1 s2 | | a |   | r0 |
        // | s1 s2 s3 | | b | = | r1 |
        // | s2 s3 s4 | | c |   | r2 |
        val det = det3(s0, s1, s2, s1, s2, s3, s2, s3, s4)
        if (kotlin.math.abs(det) < 1e-12) return null

        val a = det3(r0, s1, s2, r1, s2, s3, r2, s3, s4) / det
        val b = det3(s0, r0, s2, s1, r1, s3, s2, r2, s4) / det
        val c = det3(s0, s1, r0, s1, s2, r1, s2, s3, r2) / det

        // Reject if curvature is extreme — prevents wild extrapolations
        // c in mmol/min²: |c| > 0.02 means >1 mmol/L change from curvature alone in 7 min
        if (kotlin.math.abs(c) > 0.02) return null

        return { t -> a + b * t + c * t * t }
    }

    private fun det3(
        a11: Double, a12: Double, a13: Double,
        a21: Double, a22: Double, a23: Double,
        a31: Double, a32: Double, a33: Double
    ): Double =
        a11 * (a22 * a33 - a23 * a32) -
        a12 * (a21 * a33 - a23 * a31) +
        a13 * (a21 * a32 - a22 * a31)

    private fun findCrossing(
        model: (Double) -> Double,
        horizonMinutes: Int,
        bgLow: Double,
        bgHigh: Double,
        currentMmol: Double
    ): ThresholdCrossing? {
        val inRange = currentMmol in bgLow..bgHigh
        if (!inRange) return null

        for (m in 1..horizonMinutes) {
            val predicted = model(m.toDouble()).coerceIn(MMOL_FLOOR, MMOL_CEILING)
            if (predicted < bgLow) {
                return ThresholdCrossing(CrossingType.LOW, m, predicted)
            }
            if (predicted > bgHigh) {
                return ThresholdCrossing(CrossingType.HIGH, m, predicted)
            }
        }
        return null
    }
}
