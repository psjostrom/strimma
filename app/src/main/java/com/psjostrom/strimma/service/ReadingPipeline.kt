package com.psjostrom.strimma.service

import com.psjostrom.strimma.data.DirectionComputer
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.network.NightscoutPusher
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.tidepool.TidepoolUploader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core reading pipeline: validate → deduplicate → compute direction/delta → persist → push.
 *
 * Returns the stored [GlucoseReading] if accepted, or null if rejected (invalid SGV or duplicate).
 * Side effects (notification, alerts, broadcast, HC write, widgets) are NOT handled here —
 * the caller (StrimmaService) owns those.
 */
@Singleton
class ReadingPipeline @Inject constructor(
    private val dao: ReadingDao,
    private val directionComputer: DirectionComputer,
    private val pusher: NightscoutPusher,
    private val tidepoolUploader: TidepoolUploader
) {
    private val readingMutex = Mutex()

    companion object {
        internal const val DUPLICATE_THRESHOLD_MS = 3_000L
        internal const val LOOKBACK_MINUTES = 15
        private const val DELTA_ROUNDING_FACTOR = 10.0
    }

    suspend fun processReading(mgdl: Double, timestamp: Long): GlucoseReading? {
        return readingMutex.withLock { processReadingLocked(mgdl, timestamp) }
    }

    private suspend fun processReadingLocked(mgdl: Double, timestamp: Long): GlucoseReading? {
        if (!GlucoseReading.isValidSgv(mgdl)) {
            DebugLog.log("Rejected invalid mg/dL value: $mgdl")
            return null
        }
        val sgv = Math.round(mgdl).toInt()

        val existing = dao.lastN(1)
        val gapMs = if (existing.isNotEmpty()) timestamp - existing[0].ts else -1L
        if (existing.isNotEmpty() && kotlin.math.abs(gapMs) < DUPLICATE_THRESHOLD_MS) {
            DebugLog.log("Dedup-rejected: sgv=$sgv gapMs=$gapMs prevSgv=${existing[0].sgv}")
            return null
        }

        val recentReadings = dao.since(timestamp - LOOKBACK_MINUTES * MS_PER_MINUTE)
        val tempReading = GlucoseReading(
            ts = timestamp, sgv = sgv,
            direction = "NONE", delta = null, pushed = 0
        )
        val (direction, deltaMgdl) = directionComputer.compute(recentReadings, tempReading)

        val reading = tempReading.copy(
            direction = direction.name,
            delta = deltaMgdl?.let { Math.round(it * DELTA_ROUNDING_FACTOR) / DELTA_ROUNDING_FACTOR }
        )

        dao.insert(reading)
        DebugLog.log("Stored: ${reading.sgv} mg/dL ${direction.arrow} gapMs=$gapMs")
        pusher.pushReading(reading)
        tidepoolUploader.onNewReading()
        return reading
    }
}
