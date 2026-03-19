package com.psjostrom.strimma.graph

import com.psjostrom.strimma.data.GlucoseReading
import kotlin.math.abs
import kotlin.math.exp

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
    // Exponential decay rate: at t=-12 (oldest), weight ≈ 0.015 (vs 1.0 at t=0).
    // Last 4 min hold ~84% of total weight while still using 12 min for curve shape.
    private const val DECAY = 0.35

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

        val rawModel = fitBestModel(points) ?: return null

        // Anchor the model to the last reading so prediction connects seamlessly.
        // Least-squares minimizes total error but doesn't guarantee passing through
        // the endpoint — shift the curve so model(0) = anchor.mmol exactly.
        val offset = anchor.mmol - rawModel(0.0)
        if (abs(offset) > 1.0) return null // Raw model too far off — fit is suspect
        val model: (Double) -> Double = { t -> rawModel(t) + offset }

        val predictionPoints = (1..horizonMinutes).map { m ->
            PredictionPoint(m, model(m.toDouble()).coerceIn(MMOL_FLOOR, MMOL_CEILING))
        }

        val crossing = findCrossing(model, horizonMinutes, bgLow, bgHigh, anchor.mmol)

        return Prediction(predictionPoints, crossing, anchor.ts, anchor.mmol)
    }

    private fun weightFor(t: Double): Double = exp(DECAY * t)

    internal fun fitBestModel(points: List<Pair<Double, Double>>): ((Double) -> Double)? {
        if (points.size < 2) return null

        val weighted = points.map { (t, y) -> Triple(t, y, weightFor(t)) }

        val linear = fitLinear(weighted) ?: return null
        if (points.size < 4) return linear

        val quadratic = fitQuadratic(weighted)
        if (quadratic == null) return linear

        // Compare weighted residual sum of squares
        val linRSS = weighted.sumOf { (t, y, w) -> val e = linear(t) - y; w * e * e }
        val quadRSS = weighted.sumOf { (t, y, w) -> val e = quadratic(t) - y; w * e * e }

        // Prefer quadratic only if it meaningfully improves fit (>10% reduction)
        return if (quadRSS < linRSS * 0.9) quadratic else linear
    }

    internal fun fitLinear(weighted: List<Triple<Double, Double, Double>>): ((Double) -> Double)? {
        var sw = 0.0; var swt = 0.0; var swy = 0.0; var swtt = 0.0; var swty = 0.0
        for ((t, y, w) in weighted) {
            sw += w; swt += w * t; swy += w * y; swtt += w * t * t; swty += w * t * y
        }
        val denom = sw * swtt - swt * swt
        if (denom == 0.0) return null
        val b = (sw * swty - swt * swy) / denom
        val a = (swy - b * swt) / sw
        return { t -> a + b * t }
    }

    internal fun fitQuadratic(weighted: List<Triple<Double, Double, Double>>): ((Double) -> Double)? {
        if (weighted.size < 3) return null

        // Weighted normal equations for y = a + b*t + c*t²
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var r0 = 0.0; var r1 = 0.0; var r2 = 0.0
        for ((t, y, w) in weighted) {
            val t2 = t * t; val t3 = t2 * t; val t4 = t3 * t
            s0 += w; s1 += w * t; s2 += w * t2; s3 += w * t3; s4 += w * t4
            r0 += w * y; r1 += w * t * y; r2 += w * t2 * y
        }

        val det = det3(s0, s1, s2, s1, s2, s3, s2, s3, s4)
        if (abs(det) < 1e-12) return null

        val a = det3(r0, s1, s2, r1, s2, s3, r2, s3, s4) / det
        val b = det3(s0, r0, s2, s1, r1, s3, s2, r2, s4) / det
        val c = det3(s0, s1, r0, s1, s2, r1, s2, s3, r2) / det

        // Reject if curvature is extreme (|c| > 0.02 mmol/min² = >1 mmol/L from curvature in 7 min)
        if (abs(c) > 0.02) return null

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
