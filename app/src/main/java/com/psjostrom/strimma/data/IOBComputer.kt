package com.psjostrom.strimma.data

import kotlinx.coroutines.flow.first
import kotlin.math.exp

/**
 * One-shot IOB fetch: reads current settings and treatments, returns IOB.
 * Used by ExerciseHistoryViewModel for on-demand guidance.
 */
suspend fun fetchCurrentIOB(
    settings: SettingsRepository,
    treatmentDao: TreatmentDao,
    now: Long = System.currentTimeMillis()
): Double {
    if (!settings.treatmentsSyncEnabled.first()) return 0.0
    val insulinType = settings.insulinType.first()
    val customDIA = settings.customDIA.first()
    val tau = IOBComputer.tauForInsulinType(insulinType, customDIA)
    val treatments = treatmentDao.insulinSince(now - IOBComputer.lookbackMs(tau))
    return IOBComputer.computeIOB(treatments, now, tau)
}

/**
 * Compute current IOB from already-resolved settings values.
 * Used by StrimmaService (StateFlow.value) and LocalWebServer (Flow.first()).
 */
suspend fun computeCurrentIOB(
    treatmentsSyncEnabled: Boolean,
    insulinType: InsulinType,
    customDIA: Float,
    treatmentDao: TreatmentDao,
    now: Long = System.currentTimeMillis()
): Double {
    if (!treatmentsSyncEnabled) return 0.0
    val tau = IOBComputer.tauForInsulinType(insulinType, customDIA)
    val treatments = treatmentDao.insulinSince(now - IOBComputer.lookbackMs(tau))
    return IOBComputer.computeIOB(treatments, now, tau)
}

object IOBComputer {

    private const val MINUTES_PER_HOUR = 60.0
    private const val TAU_MULTIPLIER = 5.0

    private const val ROUNDING_FACTOR = 10.0

    /**
     * Resolve tau (time constant in minutes) from insulin type and custom DIA.
     * Single owner for this conversion — called by both ViewModel and Service.
     */
    fun tauForInsulinType(insulinType: InsulinType, customDIAHours: Float): Double {
        return if (insulinType == InsulinType.CUSTOM) {
            // DIA ~= 5 * tau, so tau = DIA_hours * 60 / 5
            customDIAHours.toDouble() * MINUTES_PER_HOUR / TAU_MULTIPLIER
        } else {
            insulinType.tauMinutes
        }
    }

    fun lookbackMs(tauMinutes: Double): Long =
        (TAU_MULTIPLIER * tauMinutes * MS_PER_MINUTE.toDouble()).toLong()

    fun iobForTreatment(dose: Double, minutesSince: Double, tauMinutes: Double): Double {
        val t = minutesSince / tauMinutes
        return dose * (1.0 + t) * exp(-t)
    }

    /**
     * Compute Insulin on Board using exponential decay model:
     * IOB(t) = dose * (1 + t/tau) * exp(-t/tau)
     *
     * where t = minutes since bolus, tau = insulin time constant.
     * Lookback window: 5 * tau minutes.
     */
    @Suppress("LoopWithTooManyJumpStatements") // Filter-and-accumulate pattern
    fun computeIOB(treatments: List<Treatment>, now: Long, tauMinutes: Double): Double {
        val lookbackMs = lookbackMs(tauMinutes)
        val cutoff = now - lookbackMs

        var total = 0.0
        for (treatment in treatments) {
            val dose = treatment.insulin ?: continue
            if (treatment.createdAt < cutoff) continue
            if (treatment.createdAt > now) continue

            val minutesSince = (now - treatment.createdAt) / MS_PER_MINUTE.toDouble()
            total += iobForTreatment(dose, minutesSince, tauMinutes)
        }

        return Math.round(total * ROUNDING_FACTOR) / ROUNDING_FACTOR
    }
}
