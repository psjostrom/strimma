package com.psjostrom.strimma.service

import com.psjostrom.strimma.data.DirectionComputer
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SensorIntervals
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.receiver.XdripBroadcastReceiver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure-storage reading pipeline: validate → bucket dedup → compute direction/delta → persist
 * → return. All side effects (push, upload, alert, notification, broadcast, HC write,
 * widgets) are owned by the caller — see [ReadingFanOut] for the debounced dispatch that
 * absorbs Eversense-style cluster transients before firing downstream consumers.
 *
 * Returns the stored [GlucoseReading] if accepted, or null if dropped (invalid SGV,
 * duplicate value in the bucket, or — for xdrip-broadcast only — backwards-in-time).
 *
 * Dedup is wall-clock-bucketed by the source's sample period (see [SensorIntervals]). A new
 * reading is dropped if its value matches ANY existing reading in the bucket — same-value
 * notification reposts (Eversense's foreground-service tick) collapse, AND a value that
 * matches a backfilled puller row in the same bucket is also dropped. A different value
 * triggers an in-bucket replacement: the prior latest row is removed and the new row stored
 * at its actual notification ts inside an atomic [ReadingDao.replaceInBucket] transaction.
 *
 * Logging via [DebugLog] is intentionally excluded from this contract — it's observability,
 * not behavior, and is safe to call from any caller without coupling them to the pipeline.
 */
@Singleton
class ReadingPipeline @Inject constructor(
    private val dao: ReadingDao,
    private val directionComputer: DirectionComputer,
) {
    private val readingMutex = Mutex()

    companion object {
        internal const val LOOKBACK_MINUTES = 15
        private const val DELTA_ROUNDING_FACTOR = 10.0
    }

    suspend fun processReading(
        mgdl: Double,
        timestamp: Long,
        source: String? = null
    ): GlucoseReading? {
        return readingMutex.withLock { processReadingLocked(mgdl, timestamp, source) }
    }

    private suspend fun processReadingLocked(
        mgdl: Double,
        timestamp: Long,
        source: String?
    ): GlucoseReading? {
        if (!GlucoseReading.isValidSgv(mgdl)) {
            DebugLog.log("Rejected invalid mg/dL value: $mgdl")
            return null
        }
        val sgv = Math.round(mgdl).toInt()

        val samplePeriod = SensorIntervals.samplePeriodMs(source)
        val bucketStart = (timestamp / samplePeriod) * samplePeriod
        val bucketRows = dao.readingsInRange(bucketStart, bucketStart + samplePeriod - 1)
        val latestExisting = bucketRows.maxByOrNull { it.ts }

        // Dedup against ANY row in the bucket — not just the latest. Pre-existing puller
        // backfill can leave multiple rows in one bucket; matching the second-newest must
        // also drop, otherwise we'd add a same-value duplicate alongside the puller's row.
        if (bucketRows.any { it.sgv == sgv }) {
            DebugLog.log(
                "Dedup-rejected: sgv=$sgv bucketStart=$bucketStart " +
                    "samplePeriod=$samplePeriod source=$source"
            )
            return null
        }

        // Reject backwards-in-time readings ONLY for xdrip-broadcast — it's the only path
        // that carries its own ts as an extra, so a misbehaving (or replayed) broadcast
        // could swap a newer row for an older-tagged one. All other paths are trusted:
        // notification mode uses System.currentTimeMillis() at receive time, follower modes
        // use the upstream API's authoritative timestamp. Applying this guard universally
        // dropped legitimate live readings when the puller had backfilled rows from a
        // partner device whose clock was a few seconds ahead.
        if (latestExisting != null &&
            source == XdripBroadcastReceiver.SOURCE_TAG &&
            timestamp < latestExisting.ts
        ) {
            DebugLog.log(
                "Rejected backwards-in-time xdrip broadcast: ts=$timestamp " +
                    "< existing.ts=${latestExisting.ts}"
            )
            return null
        }

        val isReplace = latestExisting != null
        val storeTs = timestamp

        // Direction is computed over the prior LOOKBACK_MINUTES minutes. Exclude every
        // row in the current bucket — both the row we're about to delete (replace path)
        // and any pre-existing puller siblings — so the slope window matches the
        // bucketing invariant ("at most one logical reading per bucket").
        val recentReadings = dao.since(storeTs - LOOKBACK_MINUTES * MS_PER_MINUTE)
            .filter { it.ts < bucketStart || it.ts >= bucketStart + samplePeriod }
        val tempReading = GlucoseReading(
            ts = storeTs, sgv = sgv,
            direction = "NONE", delta = null, pushed = 0
        )
        val (direction, deltaMgdl) = directionComputer.compute(recentReadings, tempReading)

        val reading = tempReading.copy(
            direction = direction.name,
            delta = deltaMgdl?.let { Math.round(it * DELTA_ROUNDING_FACTOR) / DELTA_ROUNDING_FACTOR }
        )

        if (latestExisting != null) {
            dao.replaceInBucket(oldTs = latestExisting.ts, newReading = reading)
        } else {
            dao.insert(reading)
        }

        val tag = if (isReplace) "Replaced" else "Stored"
        DebugLog.log(
            "$tag: ${reading.sgv} mg/dL ${direction.arrow} bucketStart=$bucketStart source=$source"
        )
        return reading
    }
}
