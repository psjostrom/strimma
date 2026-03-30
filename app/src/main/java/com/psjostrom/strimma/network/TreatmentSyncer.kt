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
        private const val LOOKBACK_DAYS = 30
        private const val PRUNE_DAYS = 30
        private const val LOOKBACK_MS = LOOKBACK_DAYS * 24 * 60 * 60 * 1000L
        private const val PRUNE_MS = PRUNE_DAYS * 24 * 60 * 60 * 1000L
        private const val MAX_ERROR_LENGTH = 80
        // Looping systems (CamAPS, AndroidAPS, Loop) generate temp basals every 5 min
        // (~288/day) plus boluses/carbs (~10-20/day). 500/day covers aggressive configurations.
        private const val TREATMENTS_PER_DAY = 500
        private const val SYNC_COUNT_MULTIPLIER = 30
    }

    fun start(scope: CoroutineScope): Job {
        return scope.launch {
            while (isActive) {
                sync()
                delay(POLL_INTERVAL_MS)
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
            DebugLog.log(message = "Treatment pull error: ${e.message?.take(MAX_ERROR_LENGTH)}")
            Result.failure(e)
        }
    }

    private suspend fun sync() {
        val url = settings.nightscoutUrl.first()
        val secret = settings.getNightscoutSecret()
        if (url.isBlank() || secret.isBlank()) return

        val since = System.currentTimeMillis() - LOOKBACK_MS
        val syncCount = SYNC_COUNT_MULTIPLIER * TREATMENTS_PER_DAY
        val treatments = client.fetchTreatments(url, secret, since, syncCount)

        if (treatments.isEmpty()) return

        dao.upsert(treatments)
        DebugLog.log(message = "Treatments synced: ${treatments.size}")

        val pruneThreshold = System.currentTimeMillis() - PRUNE_MS
        dao.deleteOlderThan(pruneThreshold)
    }
}
