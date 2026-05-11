package com.psjostrom.strimma.service

import com.psjostrom.strimma.data.MS_PER_HOUR
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.health.ExerciseSyncer
import com.psjostrom.strimma.di.IoDispatcher
import com.psjostrom.strimma.network.NightscoutPuller
import com.psjostrom.strimma.network.NightscoutPusher
import com.psjostrom.strimma.network.TreatmentSyncer
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.tidepool.TidepoolUploader
import com.psjostrom.strimma.update.UpdateChecker
import com.psjostrom.strimma.webserver.LocalWebServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages periodic background jobs: push/upload retry, data pruning, treatment sync,
 * web server lifecycle, exercise sync, update checker, and initial push/pull.
 *
 * Owns its own IO-backed scope so sync work — Room queries, HTTP retries, treatment
 * polling — never runs on `Dispatchers.Main`. A previous version inherited the
 * service's Main scope, which meant a single slow DAO call could block the UI and
 * any uncaught network exception killed the foreground service via the Android
 * default Main-thread handler.
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
    private val updateChecker: UpdateChecker,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val RETRY_INTERVAL_MINUTES = 5
        private const val PRUNE_INTERVAL_DAYS = 1
        private const val PRUNE_RETENTION_DAYS = 30L
        private const val HOURS_PER_DAY = 24
        private const val MAX_LOG_LENGTH = 80
    }

    // Last-resort safety net: anything that escapes the network/DB catches in
    // this scope's launches gets logged instead of crashing the process via the
    // JVM/Android default uncaught handler. Same pattern as NightscoutPusher.
    private val crashHandler = CoroutineExceptionHandler { _, t ->
        DebugLog.log("Sync scope uncaught: ${t.javaClass.simpleName}: ${t.message?.take(MAX_LOG_LENGTH)}")
    }
    private var job = SupervisorJob()
    private var scope = CoroutineScope(job + dispatcher + crashHandler)

    private var treatmentSyncJob: Job? = null
    private var exerciseSyncJob: Job? = null

    fun start() {
        startRetryLoop()
        startPruneLoop()
        startTreatmentSyncLifecycle()
        startWebServerLifecycle()
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
        scope.cancel()
        job = SupervisorJob()
        scope = CoroutineScope(job + dispatcher + crashHandler)
    }

    private fun startRetryLoop() {
        scope.launch {
            while (isActive) {
                delay(RETRY_INTERVAL_MINUTES * MS_PER_MINUTE)
                pusher.pushPending()
                tidepoolUploader.uploadPending()
            }
        }
    }

    private fun startPruneLoop() {
        scope.launch {
            while (isActive) {
                val thirtyDaysAgo = System.currentTimeMillis() - PRUNE_RETENTION_DAYS * HOURS_PER_DAY * MS_PER_HOUR
                dao.pruneBefore(thirtyDaysAgo)
                delay(PRUNE_INTERVAL_DAYS * HOURS_PER_DAY * MS_PER_HOUR)
            }
        }
    }

    private fun startTreatmentSyncLifecycle() {
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

    private fun startWebServerLifecycle() {
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
