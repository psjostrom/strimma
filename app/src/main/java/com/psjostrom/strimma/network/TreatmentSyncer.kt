package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.MS_PER_DAY
import com.psjostrom.strimma.data.TreatmentDao
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

@Singleton
class TreatmentSyncer @Inject constructor(
    private val client: NightscoutClient,
    private val dao: TreatmentDao,
    private val settings: SettingsRepository
) {
    private val _status = MutableStateFlow<IntegrationStatus>(IntegrationStatus.Idle)
    val status: StateFlow<IntegrationStatus> = _status

    internal sealed class StartupAction {
        data object FullSync : StartupAction()
        data object PollSync : StartupAction()
        data class DeltaSync(val since: Long, val count: Int) : StartupAction()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5 * 60 * 1000L
        internal const val FULL_LOOKBACK_DAYS = 30
        private const val PRUNE_DAYS = 30
        internal const val FULL_LOOKBACK_MS = FULL_LOOKBACK_DAYS * 24 * 60 * 60 * 1000L
        internal const val POLL_LOOKBACK_MS = 24 * 60 * 60 * 1000L // 24h for regular polls
        private const val PRUNE_MS = PRUNE_DAYS * 24 * 60 * 60 * 1000L
        // Looping systems (CamAPS, AndroidAPS, Loop) generate temp basals every 5 min
        // (~288/day) plus boluses/carbs (~10-20/day). 500/day covers aggressive configurations.
        internal const val TREATMENTS_PER_DAY = 500
        private const val FULL_SYNC_COUNT = FULL_LOOKBACK_DAYS * TREATMENTS_PER_DAY

        internal fun sanitizeErrorMessage(e: Exception): String {
            val msg = e.message ?: return "Sync failed"
            return when {
                msg.contains("timeout", ignoreCase = true) -> "Connection timeout"
                msg.contains("refused", ignoreCase = true) -> "Connection refused"
                msg.contains("connect", ignoreCase = true) -> "Cannot connect"
                msg.contains("ssl", ignoreCase = true) || msg.contains("certificate", ignoreCase = true) -> "SSL error"
                else -> "Sync failed"
            }
        }

        internal fun computeStartupAction(lastFetch: Long?, now: Long): StartupAction {
            if (lastFetch == null) return StartupAction.FullSync
            val gapMs = (now - lastFetch).coerceIn(0, FULL_LOOKBACK_MS)
            if (gapMs <= POLL_LOOKBACK_MS) return StartupAction.PollSync
            val since = now - gapMs
            val gapDays = (gapMs / MS_PER_DAY + 1).toInt().coerceAtMost(FULL_LOOKBACK_DAYS)
            val count = gapDays * TREATMENTS_PER_DAY
            return StartupAction.DeltaSync(since, count)
        }
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
        when (val action = computeStartupAction(dao.latestFetchedAt(), System.currentTimeMillis())) {
            is StartupAction.FullSync -> sync(fullSync = true)
            is StartupAction.PollSync -> sync(fullSync = false)
            is StartupAction.DeltaSync -> syncSince(action.since, action.count)
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
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
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

        try {
            val treatments = client.fetchTreatments(url, secret, since, count)

            if (treatments.isEmpty()) {
                _status.value = IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
                return
            }

            dao.upsert(treatments)
            DebugLog.log(message = "Treatments synced: ${treatments.size}")

            val pruneThreshold = System.currentTimeMillis() - PRUNE_MS
            dao.deleteOlderThan(pruneThreshold)

            _status.value = IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") // Room can throw various exceptions (disk full, DB locked)
            e: Exception
        ) {
            DebugLog.log(message = "Treatment sync error: ${e.message?.take(NightscoutClient.MAX_ERROR_LENGTH)}")
            _status.value = IntegrationStatus.Error(sanitizeErrorMessage(e))
        }
    }
}
