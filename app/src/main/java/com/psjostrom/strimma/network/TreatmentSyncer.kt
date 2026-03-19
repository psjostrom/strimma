package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.SettingsRepository
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
        private const val LOOKBACK_MS = 6 * 60 * 60 * 1000L
        private const val PRUNE_MS = 48 * 60 * 60 * 1000L
    }

    private var disabled = false

    fun start(scope: CoroutineScope): Job {
        disabled = false
        return scope.launch {
            while (isActive) {
                if (!disabled) {
                    sync()
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun sync() {
        val url = settings.nightscoutUrl.first()
        val secret = settings.getNightscoutSecret()
        if (url.isBlank() || secret.isBlank()) return

        val since = System.currentTimeMillis() - LOOKBACK_MS
        val treatments = client.fetchTreatments(url, secret, since)

        if (treatments.isEmpty()) {
            // Could be 404 or just no data — check if we got nothing repeatedly
            // The client logs 404 specifically; don't disable on empty results alone
            return
        }

        dao.upsert(treatments)
        DebugLog.log(message = "Treatments synced: ${treatments.size}")

        val pruneThreshold = System.currentTimeMillis() - PRUNE_MS
        dao.deleteOlderThan(pruneThreshold)
    }
}
