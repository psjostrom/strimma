package com.psjostrom.strimma.service

import com.psjostrom.strimma.data.MS_PER_HOUR
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.health.ExerciseSyncer
import com.psjostrom.strimma.network.NightscoutPuller
import com.psjostrom.strimma.network.NightscoutPusher
import com.psjostrom.strimma.network.TreatmentSyncer
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.tidepool.TidepoolUploader
import com.psjostrom.strimma.update.UpdateChecker
import com.psjostrom.strimma.webserver.LocalWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages periodic background jobs: push/upload retry, data pruning, treatment sync,
 * web server lifecycle, exercise sync, update checker, and initial push/pull.
 */
@Suppress("LongParameterList") // DI constructor — each dependency is a distinct background concern
@Singleton
class SyncOrchestrator @Inject constructor(
    private val pusher: NightscoutPusher,
    private val tidepoolUploader: TidepoolUploader,
    private val dao: ReadingDao,
    private val settings: SettingsRepository,
    private val treatmentSyncer: TreatmentSyncer,
    private val localWebServer: LocalWebServer,
    private val exerciseSyncer: ExerciseSyncer,
    private val nightscoutPuller: NightscoutPuller,
    private val updateChecker: UpdateChecker
) {
    companion object {
        private const val RETRY_INTERVAL_MINUTES = 5
        private const val PRUNE_INTERVAL_DAYS = 1
        private const val PRUNE_RETENTION_DAYS = 30L
        private const val HOURS_PER_DAY = 24
    }

    private var treatmentSyncJob: Job? = null
    private var exerciseSyncJob: Job? = null

    fun start(scope: CoroutineScope) {
        startRetryLoop(scope)
        startPruneLoop(scope)
        startTreatmentSyncLifecycle(scope)
        startWebServerLifecycle(scope)
        exerciseSyncJob = exerciseSyncer.start(scope)
        updateChecker.start(scope)

        pusher.pushPending()
        tidepoolUploader.uploadPending()
        scope.launch { nightscoutPuller.pullIfEmpty() }
    }

    fun stop() {
        treatmentSyncJob?.cancel()
        treatmentSyncJob = null
        exerciseSyncJob?.cancel()
        exerciseSyncJob = null
        localWebServer.stop()
        pusher.stop()
        tidepoolUploader.stop()
        updateChecker.stop()
    }

    private fun startRetryLoop(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(RETRY_INTERVAL_MINUTES * MS_PER_MINUTE)
                pusher.pushPending()
                tidepoolUploader.uploadPending()
            }
        }
    }

    private fun startPruneLoop(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                val thirtyDaysAgo = System.currentTimeMillis() - PRUNE_RETENTION_DAYS * HOURS_PER_DAY * MS_PER_HOUR
                dao.pruneBefore(thirtyDaysAgo)
                delay(PRUNE_INTERVAL_DAYS * HOURS_PER_DAY * MS_PER_HOUR)
            }
        }
    }

    private fun startTreatmentSyncLifecycle(scope: CoroutineScope) {
        scope.launch {
            combine(
                settings.treatmentsSyncEnabled,
                settings.nightscoutUrl,
                settings.secretVersion
            ) { enabled, nsUrl, _ ->
                if (!enabled) return@combine false
                val hasConfig = nsUrl.isNotBlank() && settings.getNightscoutSecret().isNotBlank()
                if (!hasConfig && enabled) {
                    settings.setTreatmentsSyncEnabled(false)
                    DebugLog.log("Treatment sync auto-disabled: Nightscout not configured")
                }
                hasConfig && enabled
            }.collect { shouldSync ->
                treatmentSyncJob?.cancel()
                treatmentSyncJob = null
                if (shouldSync) {
                    treatmentSyncJob = treatmentSyncer.start(scope)
                    DebugLog.log("Treatment sync started")
                } else {
                    DebugLog.log("Treatment sync stopped")
                }
            }
        }
    }

    private fun startWebServerLifecycle(scope: CoroutineScope) {
        scope.launch {
            settings.webServerEnabled.collect { enabled ->
                if (enabled) {
                    localWebServer.start()
                } else {
                    localWebServer.stop()
                }
            }
        }
    }
}
