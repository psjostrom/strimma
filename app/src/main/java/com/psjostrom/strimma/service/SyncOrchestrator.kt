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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages periodic background jobs: push/upload retry, treatment sync,
 * web server lifecycle, exercise sync, update checker, pattern checking,
 * retention prune, and initial push/pull.
 *
 * Owns its own IO-backed scope so the orchestrated jobs run off the main thread.
 * `CalendarPoller` is owned by `StrimmaService` and stays on Main — its blocking
 * work uses `withContext(IO)` internally — and so is not covered by this scope.
 */
@Suppress("LongParameterList") // 12 distinct collaborators + 1 dispatcher for scope ownership
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
    private val patternChecker: com.psjostrom.strimma.data.pattern.PatternChecker,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val RETRY_INTERVAL_MINUTES = 5
        private const val RETENTION_INTERVAL_HOURS = 24L
        private const val HOURS_PER_DAY = 24L

        // Unpushed rows older than this are no longer pushed. Bounds the worst case
        // where a poison row in unpushed() blocks fresh BG indefinitely.
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
        patternChecker.start(scope)

        pusher.pushPending()
        tidepoolUploader.uploadPending()
        scope.launch {
            nightscoutPuller.pullIfEmpty()
            patternChecker.checkNow()
        }
    }

    fun stop() {
        started = false

        // updateChecker.stop() MUST be called explicitly — `scope.cancel()` cancels
        // the launched coroutine but leaves UpdateChecker's internal `checkJob`
        // reference live (just cancelled). The next `start()` would then early-return
        // on its `if (checkJob != null) return` guard.
        updateChecker.stop()
        patternChecker.stop()
        pusher.stop()
        tidepoolUploader.stop()
        localWebServer.stop()

        // The treatment sync job and `exerciseSyncJob` are children of `scope` —
        // `scope.cancel()` below cancels them transitively. Just null the
        // reference so a re-start() doesn't observe a stale Job.
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
     * per-table prune but still gives up on stale unpushed rows (see below).
     *
     * The prune contract is uniform across all three tables. Initial backfill
     * limits still vary per table (TreatmentSyncer.FULL_LOOKBACK_DAYS,
     * ExerciseSyncer.INITIAL_FETCH_DAYS) — that's a fill-side concern, not
     * retention.
     *
     * `abandonUnpushedBefore` runs unconditionally so a poison row in
     * `unpushed()` cannot starve fresh BG forever; see STALE_UNPUSHED_DAYS.
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
                        readingDao.abandonUnpushedBefore(now - STALE_UNPUSHED_DAYS * HOURS_PER_DAY * MS_PER_HOUR)
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

    /**
     * Treatment sync runs while the setting is on and the Nightscout URL and secret are
     * both set. A missing URL or secret pauses sync without changing the user's setting,
     * so clearing the URL to retype it does not turn sync off.
     */
    private fun startTreatmentSyncLifecycle() {
        // The sync job starts in the same scope as this lifecycle, so a stop() that
        // replaces `scope` cancels both together. The job reference is local, so a
        // lifecycle that is being cancelled cannot overwrite the next lifecycle's job.
        val lifecycleScope = scope
        lifecycleScope.launch {
            var syncJob: Job? = null
            fun stopSync() {
                val running = syncJob ?: return
                running.cancel()
                syncJob = null
                DebugLog.log("Treatment sync stopped")
            }
            settings.treatmentsSyncEnabled.collectLatest { enabled ->
                stopSync()
                if (!enabled) return@collectLatest
                runPerConnection(
                    combine(settings.nightscoutUrl, settings.secretVersion) { url, version -> url to version },
                    onChange = { stopSync() }
                ) {
                    stopSync()
                    if (settings.nightscoutUrl.first().isNotBlank() && settings.getNightscoutSecret().isNotBlank()) {
                        syncJob = treatmentSyncer.start(lifecycleScope)
                        DebugLog.log("Treatment sync started")
                    } else {
                        DebugLog.log("Treatment sync paused: Nightscout not configured")
                    }
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
