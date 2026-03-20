package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.DirectionComputer
import com.psjostrom.strimma.data.GlucoseSource
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.receiver.DebugLog
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NightscoutPuller @Inject constructor(
    private val client: NightscoutClient,
    private val dao: ReadingDao,
    private val directionComputer: DirectionComputer,
    private val settings: SettingsRepository
) {
    companion object {
        private const val PAGE_SIZE = 2016
        private const val HOURS_PER_DAY = 24
        private const val MINUTES_PER_HOUR = 60
        private const val SECONDS_PER_MINUTE = 60
        private const val MS_PER_SECOND = 1000L
        private const val MS_PER_DAY = HOURS_PER_DAY * MINUTES_PER_HOUR * SECONDS_PER_MINUTE * MS_PER_SECOND
        private const val AUTO_PULL_DAYS = 30
        private const val MAX_ERROR_LENGTH = 80
    }

    suspend fun pullHistory(days: Int): Result<Int> {
        val url = settings.nightscoutUrl.first()
        val secret = settings.getNightscoutSecret()
        if (url.isBlank() || secret.isBlank()) {
            return Result.failure(IllegalStateException("Nightscout URL or secret not configured"))
        }

        val since = System.currentTimeMillis() - days.toLong() * MS_PER_DAY
        return pullSince(url, secret, since)
    }

    suspend fun pullIfEmpty() {
        val latest = dao.latestOnce()
        if (latest != null) return

        if (settings.glucoseSource.first() == GlucoseSource.NIGHTSCOUT_FOLLOWER) return

        val url = settings.nightscoutUrl.first()
        val secret = settings.getNightscoutSecret()
        if (url.isBlank() || secret.isBlank()) return

        DebugLog.log(message = "Pull: DB empty, pulling $AUTO_PULL_DAYS days from Nightscout")
        val since = System.currentTimeMillis() - AUTO_PULL_DAYS * MS_PER_DAY
        val result = pullSince(url, secret, since)
        result.onSuccess { count ->
            DebugLog.log(message = "Pull: auto-pull complete, $count readings")
        }.onFailure { e ->
            DebugLog.log(message = "Pull: auto-pull failed: ${e.message?.take(MAX_ERROR_LENGTH)}")
        }
    }

    private suspend fun pullSince(url: String, secret: String, since: Long): Result<Int> {
        var cursor = since
        var totalInserted = 0

        return try {
            while (true) {
                val entries = client.fetchEntries(url, secret, since = cursor, count = PAGE_SIZE)
                    ?: return Result.failure(IllegalStateException("Failed to fetch from Nightscout"))

                val valid = NightscoutFollower.filterValidEntries(entries)
                if (valid.isEmpty()) break

                for (entry in valid) {
                    if (processNightscoutEntry(entry, dao, directionComputer) != null) {
                        totalInserted++
                    }
                }

                if (entries.size < PAGE_SIZE) break
                cursor = entries.maxOf { it.date ?: 0L } + 1
            }

            DebugLog.log(message = "Pull: $totalInserted readings from Nightscout")
            Result.success(totalInserted)
        } catch (
            @Suppress("TooGenericExceptionCaught") // Network boundary — Ktor can throw any exception type
            e: Exception
        ) {
            DebugLog.log(message = "Pull error: ${e.message?.take(MAX_ERROR_LENGTH)}")
            Result.failure(e)
        }
    }

}
