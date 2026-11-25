package com.psjostrom.strimma.network

import android.database.sqlite.SQLiteException
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.MS_PER_DAY
import com.psjostrom.strimma.data.Treatment
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
import kotlinx.serialization.SerializationException
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
        data class DeltaSync(val since: Long) : StartupAction()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5 * 60 * 1000L

        // Bounds the *initial* fetch from Nightscout and the gap-fill window after a
        // long offline period — not local retention. Local treatments are kept
        // indefinitely so the Story view can scroll arbitrarily far back. Older
        // history can be backfilled on demand via TreatmentSyncer.pullHistory(days).
        internal const val FULL_LOOKBACK_DAYS = 100
        internal const val FULL_LOOKBACK_MS = FULL_LOOKBACK_DAYS * 24 * 60 * 60 * 1000L
        internal const val POLL_LOOKBACK_MS = 24 * 60 * 60 * 1000L // 24h for regular polls

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
            return StartupAction.DeltaSync(now - gapMs)
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
            is StartupAction.DeltaSync -> syncSince(action.since)
        }
    }

    suspend fun pullHistory(days: Int): Result<Int> {
        val url = settings.nightscoutUrl.first()
        val secret = settings.getNightscoutSecret()
        if (url.isBlank() || secret.isBlank()) {
            return Result.failure(IllegalStateException("Nightscout URL or secret not configured"))
        }

        val since = System.currentTimeMillis() - days.toLong() * MS_PER_DAY
        return withNetworkBoundary<Result<Int>>(label = "Treatment pull", onError = { Result.failure(it) }) {
            val treatments = fetchAll(url, secret, since)
            dao.upsert(treatments)
            DebugLog.log(message = "Treatment pull: ${treatments.size} treatments for $days days")
            Result.success(treatments.size)
        }
    }

    private suspend fun sync(fullSync: Boolean) {
        if (fullSync) {
            syncSince(System.currentTimeMillis() - FULL_LOOKBACK_MS)
        } else {
            syncSince(System.currentTimeMillis() - POLL_LOOKBACK_MS)
        }
    }

    private suspend fun syncSince(since: Long) {
        val url = settings.nightscoutUrl.first()
        val secret = settings.getNightscoutSecret()
        if (url.isBlank() || secret.isBlank()) return

        // Per-type IntegrationStatus differentiation: parse / DB / network errors
        // surface different user-facing messages, so onError switches on type.
        withNetworkBoundary<Unit>(
            label = "Treatment sync",
            onError = { e ->
                val msg = when (e) {
                    is SerializationException -> "Sync failed"
                    is SQLiteException -> "Database error"
                    else -> sanitizeErrorMessage(e)
                }
                _status.value = IntegrationStatus.Error(msg)
            }
        ) {
            val treatments = fetchAll(url, secret, since)
            dao.upsert(treatments)
            if (treatments.isNotEmpty()) DebugLog.log(message = "Treatments synced: ${treatments.size}")

            _status.value = IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
        }
    }

    /**
     * Fetches every page before anything is stored. The startup sync picks a full sync or a
     * short poll from the newest stored `fetchedAt`, so storing the pages of a sync that
     * then fails would make the next start skip the treatments that were never fetched.
     * Neighbouring pages share their boundary rows, so the result is keyed by id.
     */
    private suspend fun fetchAll(url: String, secret: String, since: Long): List<Treatment> {
        val byId = LinkedHashMap<String, Treatment>()
        client.forEachTreatmentPage(url, secret, since) { page -> page.associateByTo(byId) { it.id } }
        return byId.values.toList()
    }
}
