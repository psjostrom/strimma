package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.DirectionComputer
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

    val sortedEntries = dropFutureEntries(entries.filter { it.sgv != null && it.date != null })
        .sortedBy { it.date }
    if (sortedEntries.isEmpty()) return emptyList()

    val earliestTs = sortedEntries.first().date!!

    // We load existing recent readings once for the entire batch.
    // As we process and create new valid readings in this batch, we add them to this
    // mutable list so that subsequent readings in the same batch can correctly compute
    // their direction based on the freshly created preceding readings.
    val recentReadings = dao.since(earliestTs - DirectionComputer.HISTORY_MS).toMutableList()

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
        val windowStart = ts - DirectionComputer.HISTORY_MS
        recentReadings.removeAll { it.ts < windowStart }

        val pastReadings = recentReadings.filter { it.ts <= ts }
        val (computedDirection, deltaMgdl) = directionComputer.compute(pastReadings, tempReading)

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

/** Drops entries stamped beyond [GlucoseReading.MAX_CLOCK_SKEW_MS] ahead of the phone clock. */
internal fun dropFutureEntries(entries: List<NightscoutEntryResponse>): List<NightscoutEntryResponse> {
    val now = System.currentTimeMillis()
    val kept = entries.filter { entry -> entry.date?.let { GlucoseReading.isFutureTs(it, now) } != true }
    if (kept.size < entries.size) {
        DebugLog.log("Rejected ${entries.size - kept.size} entries with future timestamps, now=$now")
    }
    return kept
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
        private const val SETTINGS_RETRY_MS = 60_000L

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

            // An exception from Room, DataStore or onNewReading ends one read, backfill or
            // poll, not the follower. Nothing else restarts a follower job that has ended.
            var connection: Pair<String, String>? = null
            while (connection == null) {
                connection = withNetworkBoundary("Follower settings", onError = { null }) {
                    settings.nightscoutUrl.first() to settings.getNightscoutSecret()
                }
                if (connection == null) {
                    _status.value = IntegrationStatus.Error("Settings unreadable")
                    delay(SETTINGS_RETRY_MS)
                }
            }
            val (url, secret) = connection
            if (url.isBlank() || secret.isBlank()) {
                DebugLog.log(message = "Follower: URL or secret empty")
                _status.value = IntegrationStatus.Idle
                return@launch
            }

            withNetworkBoundary(
                "Follower backfill",
                onError = { _status.value = IntegrationStatus.Error("Backfill failed") }
            ) {
                backfill(url, secret, onNewReading)
            }

            while (isActive) {
                val pollSeconds = withNetworkBoundary(
                    "Follower poll interval",
                    onError = { SettingsRepository.DEFAULT_FOLLOWER_POLL_SECONDS }
                ) {
                    settings.followerPollSeconds.first()
                }
                delay(pollSeconds * SECONDS_TO_MS)
                withNetworkBoundary(
                    "Follower poll",
                    onError = { _status.value = IntegrationStatus.Error("Poll failed") }
                ) {
                    poll(url, secret, onNewReading)
                }
            }
        }
    }

    private suspend fun poll(url: String, secret: String, onNewReading: suspend (GlucoseReading) -> Unit) {
        val latestTs = dao.latestOnce()?.ts ?: (System.currentTimeMillis() - SEVEN_DAYS_MS)
        val result = syncSince(url, secret, latestTs, onNewReading)
        if (result == null) {
            _status.value = IntegrationStatus.Error("Connection lost")
            DebugLog.log(message = "Follower: poll failed")
            return
        }

        _status.value = if (result.callbackFailed) {
            IntegrationStatus.Error("Poll failed")
        } else {
            IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
        }
        if (result.inserted > 0) DebugLog.log(message = "Follower: ${result.inserted} new readings")
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
        val since = if (latestTs > 0) latestTs else sevenDaysAgo
        val result = syncSince(url, secret, since, onNewReading)
        if (result == null) {
            _status.value = IntegrationStatus.Error("Backfill failed")
            DebugLog.log(message = "Follower: backfill fetch failed")
            return
        }

        _status.value = if (result.callbackFailed) {
            IntegrationStatus.Error("Backfill failed")
        } else {
            IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
        }
        DebugLog.log(message = "Follower: backfill complete, ${result.inserted} readings")
    }

    /**
     * Stores every entry newer than [since] and returns how many readings were new and
     * whether [onNewReading] threw, or null when a fetch failed. Pages arrive newest first and each page is stored as it
     * arrives, so the current BG is stored and alerted on before older pages download.
     * The oldest readings of each page have no earlier readings stored yet, so their
     * direction is NONE. [onNewReading] fires once, for the newest new reading, because
     * a catch-up of thousands of readings must not redraw the notification for each one.
     * An exception from [onNewReading] is logged and reported in the result, and the
     * older pages of the gap are still fetched and stored.
     */
    private suspend fun syncSince(
        url: String,
        secret: String,
        since: Long,
        onNewReading: suspend (GlucoseReading) -> Unit,
    ): SyncResult? {
        var inserted = 0
        var notified = false
        var callbackFailed = false
        val ok = client.forEachEntryPage(url, secret, since) { page ->
            val newReadings = processNightscoutEntries(filterValidEntries(page), dao, directionComputer)
            inserted += newReadings.size
            val newest = newReadings.lastOrNull()
            if (!notified && newest != null) {
                notified = true
                withNetworkBoundary("Follower callback", onError = { callbackFailed = true }) {
                    onNewReading(newest)
                }
            }
        }
        return if (ok) SyncResult(inserted, callbackFailed) else null
    }

    private data class SyncResult(val inserted: Int, val callbackFailed: Boolean)

}
