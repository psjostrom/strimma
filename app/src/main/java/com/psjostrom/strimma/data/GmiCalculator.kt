package com.psjostrom.strimma.data

/**
 * GMI (Glucose Management Indicator) using the ATTD consensus formula.
 * Single owner for the GMI computation — all consumers call this.
 */
object GmiCalculator {

    private const val GMI_INTERCEPT = 3.31
    private const val GMI_SLOPE = 0.02392

    /** Compute GMI (eHbA1c) from average glucose in mg/dL. */
    fun compute(averageMgdl: Double): Double = GMI_INTERCEPT + GMI_SLOPE * averageMgdl
}
