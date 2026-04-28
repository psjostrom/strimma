package com.psjostrom.strimma.data

/**
 * Per-source sensor sample period — the minimum interval at which a CGM produces a real reading.
 *
 * Used by [com.psjostrom.strimma.service.ReadingPipeline] to dedupe same-value notification
 * reposts. Some CGM apps (notably Eversense) re-emit the same notification every ~30 s as a
 * UI refresh tick; without source-aware dedupe those reposts land in the DB as separate readings.
 *
 * Defaults to a conservative 1 min for unknown sources so we never silently drop a real reading
 * from a sensor we haven't catalogued.
 *
 * Buckets are wall-clock-aligned to UTC midnight (`ts / period * period`), not to the sensor's
 * actual emit cadence. For typical 5-min sensors whose real reads fall well inside a bucket
 * this is fine. For a sensor whose real reads happen to straddle a wall-clock bucket boundary
 * by milliseconds, two real readings can land in adjacent buckets — both stored. The opposite
 * (two readings collapsed because they jittered into the same bucket) requires sub-period
 * jitter, which is rare. Both edge cases are acceptable; the alternative (sliding-window
 * dedup keyed on the previous reading) has its own boundary failure modes (see prior 5/6
 * sample-period attempt before bucketing was adopted).
 */
object SensorIntervals {
    private const val ONE_MIN_MS = 60_000L
    private const val FIVE_MIN_MS = 300_000L
    private const val DEFAULT_MS = ONE_MIN_MS

    // Only sensor-bound apps are listed — apps that read directly from a specific hardware
    // family. Sensor-agnostic middleware (CamAPS FX, Juggluco, xDrip+, AAPS, etc.) is
    // intentionally absent: those apps relay whichever sensor the user has paired, so we
    // can't infer a sample period from the package name. They fall back to [DEFAULT_MS],
    // which is conservative — matches today's behavior of not dedupping same-value
    // notifications beyond the cluster window.
    private val INTERVALS: Map<String, Long> = mapOf(
        // Dexcom — 5-min cadence across the family
        "com.dexcom.g6" to FIVE_MIN_MS,
        "com.dexcom.g6.region1.mmol" to FIVE_MIN_MS,
        "com.dexcom.g6.region2.mgdl" to FIVE_MIN_MS,
        "com.dexcom.g6.region3.mgdl" to FIVE_MIN_MS,
        "com.dexcom.g6.region4.mmol" to FIVE_MIN_MS,
        "com.dexcom.g6.region5.mmol" to FIVE_MIN_MS,
        "com.dexcom.g6.region6.mgdl" to FIVE_MIN_MS,
        "com.dexcom.g6.region7.mmol" to FIVE_MIN_MS,
        "com.dexcom.g6.region8.mmol" to FIVE_MIN_MS,
        "com.dexcom.g6.region9.mgdl" to FIVE_MIN_MS,
        "com.dexcom.g6.region10.mgdl" to FIVE_MIN_MS,
        "com.dexcom.g6.region11.mmol" to FIVE_MIN_MS,
        "com.dexcom.g7" to FIVE_MIN_MS,
        "com.dexcom.dexcomone" to FIVE_MIN_MS,
        "com.dexcom.d1plus" to FIVE_MIN_MS,
        "com.dexcom.stelo" to FIVE_MIN_MS,

        // FreeStyle Libre — 1-min cadence (Libre 3 native; Libre 2 in continuous mode)
        "com.freestylelibre3.app" to ONE_MIN_MS,
        "com.freestylelibre3.app.de" to ONE_MIN_MS,
        "com.freestylelibre.app" to ONE_MIN_MS,
        "com.freestylelibre.app.de" to ONE_MIN_MS,

        // Diabox — Libre-specific bridge, 1-min
        "com.outshineiot.diabox" to ONE_MIN_MS,

        // Medtronic — 5-min cadence
        "com.medtronic.diabetes.guardian" to FIVE_MIN_MS,
        "com.medtronic.diabetes.guardianconnect" to FIVE_MIN_MS,
        "com.medtronic.diabetes.guardianconnect.us" to FIVE_MIN_MS,
        "com.medtronic.diabetes.minimedmobile.eu" to FIVE_MIN_MS,
        "com.medtronic.diabetes.minimedmobile.us" to FIVE_MIN_MS,
        "com.medtronic.diabetes.simplera.eu" to FIVE_MIN_MS,

        // Eversense — 5-min cadence; the official app reposts the same notification every
        // ~30 s as a foreground-service tick (see issue #192).
        "com.senseonics.androidapp" to FIVE_MIN_MS,
        "com.senseonics.gen12androidapp" to FIVE_MIN_MS,
        "com.senseonics.eversense365.us" to FIVE_MIN_MS,

        // Aidex — 5-min cadence across variants
        "com.microtech.aidexx.mgdl" to FIVE_MIN_MS,
        "com.microtech.aidexx.linxneo.mmoll" to FIVE_MIN_MS,
        "com.microtech.aidexx.equil.mmoll" to FIVE_MIN_MS,
        "com.microtech.aidexx.diaexport.mmoll" to FIVE_MIN_MS,
        "com.microtech.aidexx.smart.mmoll" to FIVE_MIN_MS,
        "com.microtech.aidexx" to FIVE_MIN_MS,
    )

    /** Returns the sensor sample period in milliseconds for [source], or a 1-min default. */
    fun samplePeriodMs(source: String?): Long =
        source?.let { INTERVALS[it] } ?: DEFAULT_MS
}
