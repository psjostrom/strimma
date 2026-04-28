package com.psjostrom.strimma.service

import com.psjostrom.strimma.data.DirectionComputer
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SensorIntervals
import com.psjostrom.strimma.receiver.DebugLog
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
 * duplicate value in the bucket, or backwards-in-time).
 *
 * Dedup is wall-clock-bucketed by the source's sample period (see [SensorIntervals]). A new
 * reading is dropped if its value matches ANY existing reading in the bucket — same-value
 * notification reposts (Eversense's foreground-service tick) collapse, AND a value that
 * matches a backfilled puller row in the same bucket is also dropped. A different value
 * triggers an in-bucket replacement: the prior latest row is removed and the new row stored
 * at its actual notification ts inside an atomic [ReadingDao.replaceInBucket] transaction.
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

        // Reject backwards-in-time readings. xDrip-broadcast carries its own ts and a
        // misbehaving (or replayed) broadcast could carry a ts older than what's in the
        // bucket — silently swapping a newer row for an older-tagged one would corrupt
        // dao.latestOnce()/since() chronological ordering.
        if (latestExisting != null && timestamp < latestExisting.ts) {
            DebugLog.log(
                "Rejected backwards-in-time: ts=$timestamp < existing.ts=${latestExisting.ts} " +
                    "source=$source"
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
