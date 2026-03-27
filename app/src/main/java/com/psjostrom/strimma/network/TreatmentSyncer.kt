package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.GlucoseSource
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
        private const val LOOKBACK_MS = 14 * 24 * 60 * 60 * 1000L
        private const val PRUNE_MS = 14 * 24 * 60 * 60 * 1000L
    }

    fun start(scope: CoroutineScope): Job {
        return scope.launch {
            while (isActive) {
                sync()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun sync() {
        val source = settings.glucoseSource.first()
        val (url, secret) = if (source == GlucoseSource.NIGHTSCOUT_FOLLOWER) {
            settings.followerUrl.first() to settings.getFollowerSecret()
        } else {
            settings.nightscoutUrl.first() to settings.getNightscoutSecret()
        }
        if (url.isBlank() || secret.isBlank()) return

        val since = System.currentTimeMillis() - LOOKBACK_MS
        val treatments = client.fetchTreatments(url, secret, since)

        if (treatments.isEmpty()) return

        dao.upsert(treatments)
        DebugLog.log(message = "Treatments synced: ${treatments.size}")

        val pruneThreshold = System.currentTimeMillis() - PRUNE_MS
        dao.deleteOlderThan(pruneThreshold)
    }
}
