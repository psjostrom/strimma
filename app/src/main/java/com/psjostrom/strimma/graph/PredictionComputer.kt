package com.psjostrom.strimma.graph

import com.psjostrom.strimma.data.GlucoseReading
import kotlin.math.abs
import kotlin.math.exp

enum class CrossingType { LOW, HIGH }

data class PredictionPoint(val minuteOffset: Int, val mgdl: Double)

data class ThresholdCrossing(
    val type: CrossingType,
    val minutesUntil: Int,
    val mgdlAtCrossing: Double
)

data class Prediction(
    val points: List<PredictionPoint>,
    val crossing: ThresholdCrossing?,
    val anchorTs: Long,
    val anchorMgdl: Double
)

object PredictionComputer {

    private const val LOOKBACK_MS = 12 * 60_000L
    private const val MS_PER_MINUTE = 60_000.0
    private const val MGDL_FLOOR = 18.0
    private const val MGDL_CEILING = 540.0
    // Exponential decay rate: at t=-12 (oldest), weight ≈ 0.015 (vs 1.0 at t=0).
    // Last 4 min hold ~84% of total weight while still using 12 min for curve shape.
    private const val DECAY = 0.35
    // Velocity dampening: predictions flatten over time (glucose trends mean-revert).
    // 0.05 ≈ velocity halves at ~14 min — a 15-min prediction retains ~70% of linear.
    private const val DAMP = 0.05
    // Max physiological rate of change (~5 mg/dL/min extreme). 9.0 gives headroom.
    private const val MAX_VELOCITY = 9.0

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
        val anchorMgdl = anchor.sgv.toDouble()
        // Normalize to minutes relative to anchor for numerical stability
        val points = recent.map { (it.ts - anchor.ts).toDouble() / MS_PER_MINUTE to it.sgv.toDouble() }

        val velocity = fitWeightedVelocity(points) ?: return null
        if (abs(velocity) > MAX_VELOCITY) return null  // sensor artifact

        // Dampened prediction: glucose doesn't maintain constant velocity.
        // v(t) = v0 * e^(-DAMP*t), integrated → x(t) = x0 + (v0/DAMP) * (1 - e^(-DAMP*t))
        // Naturally passes through anchor at t=0 (no offset needed).
        val model: (Double) -> Double = { t ->
            anchorMgdl + (velocity / DAMP) * (1.0 - exp(-DAMP * t))
        }

        val predictionPoints = (1..horizonMinutes).map { m ->
            PredictionPoint(m, model(m.toDouble()).coerceIn(MGDL_FLOOR, MGDL_CEILING))
        }

        val crossing = findCrossing(model, horizonMinutes, bgLow, bgHigh, anchorMgdl)

        return Prediction(predictionPoints, crossing, anchor.ts, anchorMgdl)
    }

    private fun weightFor(t: Double): Double = exp(DECAY * t)

    /**
     * Weighted linear regression slope = weighted velocity (mg/dL per minute).
     * Exponential decay weighting emphasizes recent readings.
     */
    internal fun fitWeightedVelocity(points: List<Pair<Double, Double>>): Double? {
        if (points.size < 2) return null
        val weighted = points.map { (t, y) -> Triple(t, y, weightFor(t)) }
        var sw = 0.0; var swt = 0.0; var swy = 0.0; var swtt = 0.0; var swty = 0.0
        for ((t, y, w) in weighted) {
            sw += w; swt += w * t; swy += w * y; swtt += w * t * t; swty += w * t * y
        }
        val denom = sw * swtt - swt * swt
        if (denom == 0.0) return null
        return (sw * swty - swt * swy) / denom
    }

    private fun findCrossing(
        model: (Double) -> Double,
        horizonMinutes: Int,
        bgLow: Double,
        bgHigh: Double,
        currentMgdl: Double
    ): ThresholdCrossing? {
        val inRange = currentMgdl in bgLow..bgHigh
        if (!inRange) return null

        for (m in 1..horizonMinutes) {
            val predicted = model(m.toDouble()).coerceIn(MGDL_FLOOR, MGDL_CEILING)
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
