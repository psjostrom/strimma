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
 * Core reading pipeline: validate → deduplicate → compute direction/delta → persist → push.
 *
 * Returns the stored [GlucoseReading] if accepted, or null if dropped (invalid SGV or duplicate).
 * Side effects (notification, alerts, broadcast, HC write, widgets) are NOT handled here —
 * the caller (StrimmaService) owns those.
 *
 * Dedup is wall-clock-bucketed by the source's sample period (see [SensorIntervals]). Each
 * bucket holds at most one reading. New notifications landing in a bucket that already has a
 * reading either drop (same value — Eversense-style notification reposts) or replace in place
 * (different value — late-arriving cluster value supersedes the earlier stale one).
 */
@Singleton
class ReadingPipeline @Inject constructor(
    private val dao: ReadingDao,
    private val directionComputer: DirectionComputer,
    private val pusher: ReadingPusher,
    private val uploader: ReadingUploader
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
        val existing = dao.readingsInRange(bucketStart, bucketStart + samplePeriod - 1)
            .firstOrNull()

        if (existing != null && existing.sgv == sgv) {
            DebugLog.log(
                "Dedup-rejected: sgv=$sgv bucketStart=$bucketStart " +
                    "samplePeriod=$samplePeriod source=$source"
            )
            return null
        }

        // Different value in this bucket — drop the earlier (stale) row and store the new
        // value at its actual notification timestamp. Keeping the new ts matters for
        // Eversense-style clusters where the real reading lands several minutes after the
        // first repost in the bucket.
        val isReplace = existing != null
        if (existing != null && existing.ts != timestamp) {
            dao.deleteByTs(existing.ts)
        }
        val storeTs = timestamp

        val recentReadings = dao.since(storeTs - LOOKBACK_MINUTES * MS_PER_MINUTE)
            .filter { it.ts != storeTs && (existing == null || it.ts != existing.ts) }
        val tempReading = GlucoseReading(
            ts = storeTs, sgv = sgv,
            direction = "NONE", delta = null, pushed = 0
        )
        val (direction, deltaMgdl) = directionComputer.compute(recentReadings, tempReading)

        val reading = tempReading.copy(
            direction = direction.name,
            delta = deltaMgdl?.let { Math.round(it * DELTA_ROUNDING_FACTOR) / DELTA_ROUNDING_FACTOR }
        )

        dao.insert(reading)
        val tag = if (isReplace) "Replaced" else "Stored"
        DebugLog.log(
            "$tag: ${reading.sgv} mg/dL ${direction.arrow} bucketStart=$bucketStart source=$source"
        )
        pusher.pushReading(reading)
        uploader.onNewReading()
        return reading
    }
}
