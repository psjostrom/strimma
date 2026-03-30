package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.MS_PER_DAY
import com.psjostrom.strimma.data.TreatmentDao
import com.psjostrom.strimma.receiver.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TreatmentSyncer @Inject constructor(
    private val client: NightscoutClient,
    private val dao: TreatmentDao,
    private val settings: SettingsRepository
) {
    companion object {
        private const val POLL_INTERVAL_MS = 5 * 60 * 1000L
        private const val FULL_LOOKBACK_DAYS = 30
        private const val PRUNE_DAYS = 30
        private const val FULL_LOOKBACK_MS = FULL_LOOKBACK_DAYS * 24 * 60 * 60 * 1000L
        private const val POLL_LOOKBACK_MS = 24 * 60 * 60 * 1000L // 24h for regular polls
        private const val PRUNE_MS = PRUNE_DAYS * 24 * 60 * 60 * 1000L
        // Looping systems (CamAPS, AndroidAPS, Loop) generate temp basals every 5 min
        // (~288/day) plus boluses/carbs (~10-20/day). 500/day covers aggressive configurations.
        private const val TREATMENTS_PER_DAY = 500
        private const val FULL_SYNC_COUNT = FULL_LOOKBACK_DAYS * TREATMENTS_PER_DAY
    }

    fun start(scope: CoroutineScope): Job {
        return scope.launch {
            startupSync()
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                sync(fullSync = false)
            }
        }
    }

    private suspend fun startupSync() {
        val lastFetch = dao.latestFetchedAt()
        if (lastFetch == null) {
            // Empty DB — full 30-day sync
            sync(fullSync = true)
        } else {
            // Sync from last fetch time, capped at 30 days. This covers the exact
            // gap since the app last ran — no data loss, no redundant fetching.
            val gapMs = (System.currentTimeMillis() - lastFetch).coerceIn(0, FULL_LOOKBACK_MS)
            if (gapMs <= POLL_LOOKBACK_MS) {
                // Last fetch was within 24h — the regular poll will cover it
                sync(fullSync = false)
            } else {
                // Gap exceeds poll window — fetch from last fetch time
                val since = System.currentTimeMillis() - gapMs
                val gapDays = (gapMs / MS_PER_DAY + 1).toInt().coerceAtMost(FULL_LOOKBACK_DAYS)
                val count = gapDays * TREATMENTS_PER_DAY
                syncSince(since, count)
            }
        }
    }

    suspend fun pullHistory(days: Int): Result<Int> {
        val url = settings.nightscoutUrl.first()
        val secret = settings.getNightscoutSecret()
        if (url.isBlank() || secret.isBlank()) {
            return Result.failure(IllegalStateException("Nightscout URL or secret not configured"))
        }

        val since = System.currentTimeMillis() - days.toLong() * MS_PER_DAY
        val count = days * TREATMENTS_PER_DAY
        return try {
            val treatments = client.fetchTreatments(url, secret, since, count)
            if (treatments.isNotEmpty()) {
                dao.upsert(treatments)
            }
            DebugLog.log(message = "Treatment pull: ${treatments.size} treatments for $days days")
            Result.success(treatments.size)
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception
        ) {
            DebugLog.log(message = "Treatment pull error: ${e.message?.take(NightscoutClient.MAX_ERROR_LENGTH)}")
            Result.failure(e)
        }
    }

    private suspend fun sync(fullSync: Boolean) {
        if (fullSync) {
            syncSince(System.currentTimeMillis() - FULL_LOOKBACK_MS, FULL_SYNC_COUNT)
        } else {
            syncSince(System.currentTimeMillis() - POLL_LOOKBACK_MS, TREATMENTS_PER_DAY)
        }
    }

    private suspend fun syncSince(since: Long, count: Int) {
        val url = settings.nightscoutUrl.first()
        val secret = settings.getNightscoutSecret()
        if (url.isBlank() || secret.isBlank()) return

        val treatments = client.fetchTreatments(url, secret, since, count)

        if (treatments.isEmpty()) return

        dao.upsert(treatments)
        DebugLog.log(message = "Treatments synced: ${treatments.size}")

        val pruneThreshold = System.currentTimeMillis() - PRUNE_MS
        dao.deleteOlderThan(pruneThreshold)
    }
}
