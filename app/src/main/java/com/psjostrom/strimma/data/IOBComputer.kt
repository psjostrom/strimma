package com.psjostrom.strimma.data

import kotlin.math.exp

object IOBComputer {

    /**
     * Resolve tau (time constant in minutes) from insulin type and custom DIA.
     * Single owner for this conversion — called by both ViewModel and Service.
     */
    fun tauForInsulinType(insulinType: InsulinType, customDIAHours: Float): Double {
        return if (insulinType == InsulinType.CUSTOM) {
            // DIA ~= 5 * tau, so tau = DIA_hours * 60 / 5
            customDIAHours.toDouble() * 60.0 / 5.0
        } else {
            insulinType.tauMinutes
        }
    }

    /**
     * Compute Insulin on Board using exponential decay model:
     * IOB(t) = dose * (1 + t/tau) * exp(-t/tau)
     *
     * where t = minutes since bolus, tau = insulin time constant.
     * Lookback window: 5 * tau minutes.
     */
    fun computeIOB(treatments: List<Treatment>, now: Long, tauMinutes: Double): Double {
        val lookbackMs = (5.0 * tauMinutes * 60_000).toLong()
        val cutoff = now - lookbackMs

        var total = 0.0
        for (treatment in treatments) {
            val dose = treatment.insulin ?: continue
            if (treatment.createdAt < cutoff) continue
            if (treatment.createdAt > now) continue

            val minutesSince = (now - treatment.createdAt) / 60_000.0
            val t = minutesSince / tauMinutes
            val iob = dose * (1.0 + t) * exp(-t)
            total += iob
        }

        return Math.round(total * 10.0) / 10.0
    }
}
