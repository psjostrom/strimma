package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.DirectionComputer
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.receiver.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val LOOKBACK_MINUTES = 15
private const val DUPLICATE_THRESHOLD_MS = 3_000L
private const val SECONDS_TO_MS = 1000L
private const val DELTA_ROUNDING_FACTOR = 10.0

/**
 * Storage path for Nightscout-follower entries. Intentionally bypasses
 * [com.psjostrom.strimma.service.ReadingPipeline]: the upstream NS server already dedupes
 * on its end (NS rejects duplicate timestamps), so re-bucketing here would just reapply
 * weaker logic on already-clean data. The 3-second proximity check below catches the rare
 * pathological case where a follower poll re-fetches an entry already in our DB.
 *
 * Side-effect dispatch (alert, push, HC, broadcast, upload) DOES converge on
 * `StrimmaService.onNewReading` via `cameFromNightscout=true`, so the follower path is
 * unified at the side-effects level even though storage is direct.
 */
suspend fun processNightscoutEntries(
    entries: List<NightscoutEntryResponse>,
    dao: ReadingDao,
    directionComputer: DirectionComputer,
    pushed: Int = 1
): List<GlucoseReading> {
    if (entries.isEmpty()) return emptyList()

    val sortedEntries = entries
        .filter { it.sgv != null && it.date != null }
        .sortedBy { it.date }
    if (sortedEntries.isEmpty()) return emptyList()

    val earliestTs = sortedEntries.first().date!!

    // We load existing recent readings once for the entire batch.
    // As we process and create new valid readings in this batch, we add them to this
    // mutable list so that subsequent readings in the same batch can correctly compute
    // their direction based on the freshly created preceding readings.
    val recentReadings = dao.since(earliestTs - LOOKBACK_MINUTES * MS_PER_MINUTE).toMutableList()

    val newReadings = mutableListOf<GlucoseReading>()

    for (entry in sortedEntries) {
        val sgv = entry.sgv!!
        val ts = entry.date!!

        val existing = recentReadings.find { kotlin.math.abs(it.ts - ts) < DUPLICATE_THRESHOLD_MS }
        if (existing != null) continue

        val tempReading = GlucoseReading(
            ts = ts, sgv = sgv,
            direction = "NONE", delta = null, pushed = pushed
        )
        val windowStart = ts - LOOKBACK_MINUTES * MS_PER_MINUTE
        recentReadings.removeAll { it.ts < windowStart }

        val (computedDirection, deltaMgdl) = directionComputer.compute(recentReadings, tempReading)

        val reading = tempReading.copy(
            direction = computedDirection.name,
            delta = deltaMgdl?.let { Math.round(it * DELTA_ROUNDING_FACTOR) / DELTA_ROUNDING_FACTOR }
        )

        newReadings.add(reading)
        recentReadings.add(reading)
    }

    if (newReadings.isNotEmpty()) {
        dao.insertBatch(newReadings)
    }

    return newReadings
}

suspend fun processNightscoutEntry(
    entry: NightscoutEntryResponse,
    dao: ReadingDao,
    directionComputer: DirectionComputer,
    pushed: Int = 1
): GlucoseReading? {
    return processNightscoutEntries(listOf(entry), dao, directionComputer, pushed).firstOrNull()
}

@Singleton
class NightscoutFollower @Inject constructor(
    private val client: NightscoutClient,
    private val dao: ReadingDao,
    private val directionComputer: DirectionComputer,
    private val settings: SettingsRepository
) {
    private val _status = MutableStateFlow<IntegrationStatus>(IntegrationStatus.Idle)
    val status: StateFlow<IntegrationStatus> = _status

    companion object {
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
        private const val FETCH_COUNT = 2016

        fun filterValidEntries(entries: List<NightscoutEntryResponse>): List<NightscoutEntryResponse> {
            return entries
                .filter { it.type == "sgv" && it.sgv != null && it.date != null }
                .filter { GlucoseReading.isValidSgv(it.sgv!!) }
                .sortedBy { it.date }
        }
    }

    fun start(scope: CoroutineScope, onNewReading: suspend (GlucoseReading) -> Unit): Job {
        return scope.launch {
            _status.value = IntegrationStatus.Connecting

            val url = settings.nightscoutUrl.first()
            val secret = settings.getNightscoutSecret()
            if (url.isBlank() || secret.isBlank()) {
                DebugLog.log(message = "Follower: URL or secret empty")
                _status.value = IntegrationStatus.Idle
                return@launch
            }

            backfill(url, secret, onNewReading)

            while (isActive) {
                val pollSeconds = settings.followerPollSeconds.first()
                delay(pollSeconds * SECONDS_TO_MS)

                val latestTs = dao.latestOnce()?.ts ?: (System.currentTimeMillis() - SEVEN_DAYS_MS)
                val entries = client.fetchEntries(url, secret, since = latestTs)

                if (entries == null) {
                    if (_status.value !is IntegrationStatus.Error) {
                        _status.value = IntegrationStatus.Error("Connection lost")
                    }
                    DebugLog.log(message = "Follower: poll failed")
                    continue
                }

                val valid = filterValidEntries(entries)
                if (valid.isEmpty()) {
                    _status.value = IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
                    continue
                }

                val newReadings = processNightscoutEntries(valid, dao, directionComputer)
                for (reading in newReadings) {
                    onNewReading(reading)
                }

                _status.value = IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
                DebugLog.log(message = "Follower: ${valid.size} valid entries, ${newReadings.size} new readings")
            }
        }
    }

    fun stop() {
        _status.value = IntegrationStatus.Idle
    }

    private suspend fun backfill(url: String, secret: String, onNewReading: suspend (GlucoseReading) -> Unit) {
        val latestTs = dao.latestOnce()?.ts ?: 0L
        val sevenDaysAgo = System.currentTimeMillis() - SEVEN_DAYS_MS

        if (latestTs >= sevenDaysAgo) {
            _status.value = IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
            DebugLog.log(message = "Follower: skip backfill, data is recent")
            return
        }

        DebugLog.log(message = "Follower: starting backfill")
        var since = if (latestTs > 0) latestTs else sevenDaysAgo
        var totalInserted = 0
        var lastReading: GlucoseReading? = null

        while (true) {
            val entries = client.fetchEntries(url, secret, since = since, count = FETCH_COUNT)
            if (entries == null) {
                _status.value = IntegrationStatus.Error("Backfill failed")
                DebugLog.log(message = "Follower: backfill fetch failed")
                return
            }
            if (entries.isEmpty()) break

            val pageResult = processBackfillEntries(entries)
            totalInserted += pageResult.insertedCount
            if (pageResult.lastReading != null) {
                lastReading = pageResult.lastReading
            }

            since = nextBackfillSince(entries) ?: break
        }

        if (lastReading != null) {
            onNewReading(lastReading)
        }

        _status.value = IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
        DebugLog.log(message = "Follower: backfill complete, $totalInserted readings")
    }

    private suspend fun processBackfillEntries(entries: List<NightscoutEntryResponse>): BackfillPageResult {
        val valid = filterValidEntries(entries)
        val newReadings = processNightscoutEntries(valid, dao, directionComputer)

        return BackfillPageResult(
            insertedCount = newReadings.size,
            lastReading = newReadings.lastOrNull()
        )
    }

    private fun nextBackfillSince(entries: List<NightscoutEntryResponse>): Long? {
        if (entries.size < FETCH_COUNT) return null
        return entries.mapNotNull { it.date }.maxOrNull()?.plus(1)
    }

    private data class BackfillPageResult(
        val insertedCount: Int,
        val lastReading: GlucoseReading?
    )

}
