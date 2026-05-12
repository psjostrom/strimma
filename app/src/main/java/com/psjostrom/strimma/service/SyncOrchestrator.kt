package com.psjostrom.strimma.service

import com.psjostrom.strimma.data.MS_PER_HOUR
import com.psjostrom.strimma.data.MS_PER_MINUTE
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.TreatmentDao
import androidx.annotation.VisibleForTesting
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.data.health.ExerciseSyncer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
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
 * Manages periodic background jobs: push/upload retry, treatment sync,
 * web server lifecycle, exercise sync, update checker, retention prune,
 * and initial push/pull.
 *
 * Owns its own IO-backed scope so the orchestrated jobs run off the main thread.
 * `CalendarPoller` is owned by `StrimmaService` and stays on Main — its blocking
 * work uses `withContext(IO)` internally — and so is not covered by this scope.
 */
@Suppress("LongParameterList") // 11 distinct collaborators + 1 dispatcher for scope ownership
@Singleton
class SyncOrchestrator @Inject constructor(
    private val pusher: NightscoutPusher,
    private val tidepoolUploader: TidepoolUploader,
    private val readingDao: ReadingDao,
    private val treatmentDao: TreatmentDao,
    private val exerciseDao: ExerciseDao,
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
        private const val RETENTION_INTERVAL_HOURS = 24L
        private const val HOURS_PER_DAY = 24L

        // Stale unpushed rows older than this are dropped on every retention tick,
        // regardless of the user's RetentionPolicy. Bounds the worst case where a
        // poison row at the head of unpushed() blocks fresh BG indefinitely (the
        // pre-PR 30-day prune used to self-heal this; the always-on cleanup
        // restores that floor without resurrecting per-table prune asymmetry).
        private const val STALE_UNPUSHED_DAYS = 30L
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
        startRetentionLoop()
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

    /**
     * Applies the user's [com.psjostrom.strimma.data.RetentionPolicy] to readings,
     * treatments, and exercise sessions. Reacts immediately when the policy
     * changes (so the user sees their disk free up shortly after picking a
     * tighter window) and re-runs every 24h to catch up. INDEFINITE skips the
     * per-table prune but still runs the unpushed-cleanup pass below.
     *
     * The prune contract is uniform across all three tables. Initial backfill
     * limits still vary per table (TreatmentSyncer.FULL_LOOKBACK_DAYS,
     * ExerciseSyncer.INITIAL_FETCH_DAYS) — that's a fill-side concern, not
     * retention.
     *
     * `pruneUnpushedBefore` runs unconditionally so a poison row at the head
     * of `unpushed()` cannot starve fresh BG forever; see STALE_UNPUSHED_DAYS.
     *
     * Wrapped in try/catch so a transient DataStore IO error or DAO exception
     * doesn't kill the loop for the rest of the service lifetime — the next
     * tick or policy change resumes pruning normally.
     */
    private fun startRetentionLoop() {
        scope.launch {
            // Periodic ticker that re-runs the prune even when the policy is unchanged.
            // Combined with the policy flow so a setting change triggers an immediate
            // pass (no 24h wait for the user to see their disk free up).
            val ticker = flow {
                while (currentCoroutineContext().isActive) {
                    emit(Unit)
                    delay(RETENTION_INTERVAL_HOURS * MS_PER_HOUR)
                }
            }
            combine(settings.retentionPolicy, ticker) { policy, _ -> policy }
                .collect { policy ->
                    @Suppress("TooGenericExceptionCaught") // DAO/DataStore can throw various types
                    try {
                        val now = System.currentTimeMillis()
                        readingDao.pruneUnpushedBefore(now - STALE_UNPUSHED_DAYS * HOURS_PER_DAY * MS_PER_HOUR)
                        val days = policy.days
                        if (days != null) {
                            val cutoff = now - days * HOURS_PER_DAY * MS_PER_HOUR
                            readingDao.pruneBefore(cutoff)
                            treatmentDao.deleteOlderThan(cutoff)
                            exerciseDao.deleteSessionsOlderThan(cutoff)
                        }
                    } catch (e: Exception) {
                        DebugLog.log("Retention prune iteration failed: ${e.message}")
                    }
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
