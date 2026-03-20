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

sealed class FollowerStatus {
    object Idle : FollowerStatus()
    object Connecting : FollowerStatus()
    data class Connected(val lastPollTs: Long) : FollowerStatus()
    data class Disconnected(val since: Long) : FollowerStatus()
}

private const val LOOKBACK_MINUTES = 15
private const val DUPLICATE_THRESHOLD_MS = 3_000L
private const val MINUTES_TO_MS = 60 * 1000L
private const val SECONDS_TO_MS = 1000L
private const val ROUNDING_FACTOR = 10.0

suspend fun processNightscoutEntry(
    entry: NightscoutEntryResponse,
    dao: ReadingDao,
    directionComputer: DirectionComputer
): GlucoseReading? {
    val sgv = entry.sgv ?: return null
    val ts = entry.date ?: return null
    val mmol = NightscoutFollower.sgvToMmol(sgv)

    val recentReadings = dao.since(ts - LOOKBACK_MINUTES * MINUTES_TO_MS)
    val existing = recentReadings.find { kotlin.math.abs(it.ts - ts) < DUPLICATE_THRESHOLD_MS }
    if (existing != null) return null

    val tempReading = GlucoseReading(
        ts = ts, sgv = sgv, mmol = mmol,
        direction = "NONE", deltaMmol = null, pushed = 1
    )
    val (computedDirection, deltaMmol) = directionComputer.compute(recentReadings, tempReading)

    // Use server-provided direction if available and valid, otherwise use locally computed
    val directionName = if (entry.direction != null && entry.direction != "NONE" && entry.direction != "NOT COMPUTABLE") {
        entry.direction
    } else {
        computedDirection.name
    }

    val reading = tempReading.copy(
        direction = directionName,
        deltaMmol = deltaMmol?.let { Math.round(it * ROUNDING_FACTOR) / ROUNDING_FACTOR }
    )

    dao.insert(reading)
    return reading
}

@Singleton
class NightscoutFollower @Inject constructor(
    private val client: NightscoutClient,
    private val dao: ReadingDao,
    private val directionComputer: DirectionComputer,
    private val settings: SettingsRepository
) {
    private val _status = MutableStateFlow<FollowerStatus>(FollowerStatus.Idle)
    val status: StateFlow<FollowerStatus> = _status

    companion object {
        private const val MGDL_CONVERSION = 18.0182
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
        private const val FETCH_COUNT = 2016
        private const val MIN_VALID_MMOL = 1.0
        private const val MAX_VALID_MMOL = 50.0
        private const val MMOL_ROUNDING_FACTOR = 10.0

        fun filterValidEntries(entries: List<NightscoutEntryResponse>): List<NightscoutEntryResponse> {
            return entries
                .filter { it.type == "sgv" && it.sgv != null && it.date != null }
                .filter {
                    val mmol = it.sgv!! / MGDL_CONVERSION
                    mmol >= MIN_VALID_MMOL && mmol <= MAX_VALID_MMOL
                }
                .sortedBy { it.date }
        }

        fun sgvToMmol(sgv: Int): Double {
            val raw = sgv / MGDL_CONVERSION
            return Math.round(raw * MMOL_ROUNDING_FACTOR) / MMOL_ROUNDING_FACTOR
        }
    }

    fun start(scope: CoroutineScope, onNewReading: suspend (GlucoseReading) -> Unit): Job {
        return scope.launch {
            _status.value = FollowerStatus.Connecting

            val url = settings.followerUrl.first()
            val secret = settings.getFollowerSecret()
            if (url.isBlank() || secret.isBlank()) {
                DebugLog.log(message = "Follower: URL or secret empty")
                _status.value = FollowerStatus.Idle
                return@launch
            }

            backfill(url, secret, onNewReading)

            while (isActive) {
                val pollSeconds = settings.followerPollSeconds.first()
                delay(pollSeconds * SECONDS_TO_MS)

                val latestTs = dao.latestOnce()?.ts ?: (System.currentTimeMillis() - SEVEN_DAYS_MS)
                val entries = client.fetchEntries(url, secret, since = latestTs)

                if (entries == null) {
                    val now = System.currentTimeMillis()
                    if (_status.value !is FollowerStatus.Disconnected) {
                        _status.value = FollowerStatus.Disconnected(since = now)
                    }
                    DebugLog.log(message = "Follower: poll failed")
                    continue
                }

                val valid = filterValidEntries(entries)
                if (valid.isEmpty()) {
                    _status.value = FollowerStatus.Connected(lastPollTs = System.currentTimeMillis())
                    continue
                }

                for (entry in valid) {
                    val reading = processNightscoutEntry(entry, dao, directionComputer)
                    if (reading != null) {
                        onNewReading(reading)
                    }
                }

                _status.value = FollowerStatus.Connected(lastPollTs = System.currentTimeMillis())
                DebugLog.log(message = "Follower: ${valid.size} new readings")
            }
        }
    }

    fun stop() {
        _status.value = FollowerStatus.Idle
    }

    private suspend fun backfill(url: String, secret: String, onNewReading: suspend (GlucoseReading) -> Unit) {
        val latestTs = dao.latestOnce()?.ts ?: 0L
        val sevenDaysAgo = System.currentTimeMillis() - SEVEN_DAYS_MS

        if (latestTs >= sevenDaysAgo) {
            _status.value = FollowerStatus.Connected(lastPollTs = System.currentTimeMillis())
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
                _status.value = FollowerStatus.Disconnected(since = System.currentTimeMillis())
                DebugLog.log(message = "Follower: backfill fetch failed")
                return
            }

            val valid = filterValidEntries(entries)
            if (valid.isEmpty()) break

            for (entry in valid) {
                val reading = processNightscoutEntry(entry, dao, directionComputer)
                if (reading != null) {
                    lastReading = reading
                    totalInserted++
                }
            }

            if (entries.size < FETCH_COUNT) break
            since = entries.maxOf { it.date ?: 0L } + 1
        }

        if (lastReading != null) {
            onNewReading(lastReading)
        }

        _status.value = FollowerStatus.Connected(lastPollTs = System.currentTimeMillis())
        DebugLog.log(message = "Follower: backfill complete, $totalInserted readings")
    }

}
