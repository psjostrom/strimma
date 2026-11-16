package com.psjostrom.strimma.service

import com.psjostrom.strimma.data.DirectionComputer
import com.psjostrom.strimma.data.GlucoseReading
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
 * matches a backfilled puller row in the same bucket is also dropped. When the bucket is
 * empty, the same value within 30 s of an existing reading is dropped too, which catches
 * reposts that cross a bucket boundary; no CGM reports more often than once a minute. A
 * different value
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
        private const val REPOST_WINDOW_MS = 30_000L
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
        val bucketEnd = bucketStart + samplePeriod - 1
        val rows = dao.readingsInRange(
            minOf(bucketStart, timestamp - REPOST_WINDOW_MS),
            maxOf(bucketEnd, timestamp + REPOST_WINDOW_MS)
        )
        val bucketRows = rows.filter { it.ts in bucketStart..bucketEnd }
        val latestExisting = bucketRows.maxByOrNull { it.ts }

        if (isRepost(sgv, timestamp, rows, bucketRows)) {
            DebugLog.log(
                "Dedup-rejected: sgv=$sgv bucketStart=$bucketStart " +
                    "samplePeriod=$samplePeriod source=$source"
            )
            return null
        }

        // Reject backwards-in-time readings ONLY for xdrip-broadcast. All four paths
        // (COMPANION, XDRIP_BROADCAST, NIGHTSCOUT_FOLLOWER, LIBRELINKUP) now carry an
        // upstream ts — none stamp at receive time. The guard stays xdrip-only because
        // that path has the weakest trust model: the broadcast Intent can be replayed
        // by any app holding the action. COMPANION is bounded to the CGM_PACKAGES
        // allowlist with `when` sanitized to 1..now in the listener; follower modes are
        // server-authenticated. Within-bucket backwards-in-time is intentional for
        // those paths — it's how the puller-vs-live race resolves when a partner
        // device's clock runs a few seconds ahead of ours (covered by the
        // "companion - backwards-in-time reading replaces, not rejects" pipeline test).
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

        // Direction is computed over the prior DirectionComputer.HISTORY_MS. Exclude every
        // row in the current bucket — both the row we're about to delete (replace path)
        // and any pre-existing puller siblings — so the slope window matches the
        // bucketing invariant ("at most one logical reading per bucket").
        val recentReadings = dao.since(storeTs - DirectionComputer.HISTORY_MS)
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

    /**
     * Dedup against ANY row in the bucket — not just the latest. Pre-existing puller backfill
     * can leave multiple rows in one bucket; matching the second-newest must also drop,
     * otherwise we'd add a same-value duplicate alongside the puller's row.
     *
     * An empty bucket can still receive a repost of the reading just across the bucket
     * boundary. No CGM reports more often than once a minute, so the same value within
     * [REPOST_WINDOW_MS] is that repost. A non-empty bucket keeps the bucket rules alone, so a
     * neighbouring row never blocks an in-bucket replacement.
     */
    private fun isRepost(
        sgv: Int,
        timestamp: Long,
        rows: List<GlucoseReading>,
        bucketRows: List<GlucoseReading>,
    ): Boolean = if (bucketRows.isEmpty()) {
        rows.any { it.sgv == sgv && kotlin.math.abs(it.ts - timestamp) <= REPOST_WINDOW_MS }
    } else {
        bucketRows.any { it.sgv == sgv }
    }
}
