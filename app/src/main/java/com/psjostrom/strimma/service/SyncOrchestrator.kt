package com.psjostrom.strimma.service

import com.psjostrom.strimma.data.MS_PER_HOUR
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import androidx.annotation.VisibleForTesting
import com.psjostrom.strimma.data.health.ExerciseSyncer
import com.psjostrom.strimma.di.IoDispatcher
import com.psjostrom.strimma.network.NightscoutPuller
import com.psjostrom.strimma.network.NightscoutPusher
import com.psjostrom.strimma.network.TreatmentSyncer
import com.psjostrom.strimma.receiver.DebugLog
import com.psjostrom.strimma.receiver.scopeCrashHandler
import com.psjostrom.strimma.tidepool.TidepoolUploader
import com.psjostrom.strimma.update.UpdateChecker
import com.psjostrom.strimma.webserver.LocalWebServer
import kotlinx.coroutines.CoroutineDispatcher
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
 * Owns its own IO-backed scope so the orchestrated jobs run off the main thread.
 * `CalendarPoller` is owned by `StrimmaService` and stays on Main — its blocking
 * work uses `withContext(IO)` internally — and so is not covered by this scope.
 */
@Suppress("LongParameterList") // 9 distinct background collaborators + 1 dispatcher for scope ownership
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
    }

    // Belt for anything that escapes the network/DB catches in this scope's launches.
    private val crashHandler = scopeCrashHandler("Sync")

    // @Volatile because `start()` and `stop()` are invoked from the service
    // lifecycle (today: Main thread only), but the scope reference is read from
    // inside coroutines running on the IO dispatcher. Adds a memory barrier so
    // a future caller from another thread can't observe a torn write.
    @Volatile private var job = SupervisorJob()
    @Volatile private var scope = CoroutineScope(job + dispatcher + crashHandler)

    private var treatmentSyncJob: Job? = null
    private var exerciseSyncJob: Job? = null

    @VisibleForTesting
    internal var started: Boolean = false
        private set

    fun start() {
        // Idempotent: a stray double-call must not duplicate the periodic loops
        // or stack two DataStore collectors. Mirrors UpdateChecker.start()'s
        // `if (checkJob != null) return` guard.
        if (started) return
        started = true

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
        started = false

        // updateChecker.stop() MUST be called explicitly — `scope.cancel()` cancels
        // the launched coroutine but leaves UpdateChecker's internal `checkJob`
        // reference live (just cancelled). The next `start()` would then early-return
        // on its `if (checkJob != null) return` guard.
        updateChecker.stop()
        pusher.stop()
        tidepoolUploader.stop()
        localWebServer.stop()

        // `treatmentSyncJob` and `exerciseSyncJob` are children of `scope` —
        // `scope.cancel()` below cancels them transitively. Just null the
        // references so a re-start() doesn't observe stale Jobs.
        treatmentSyncJob = null
        exerciseSyncJob = null

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
