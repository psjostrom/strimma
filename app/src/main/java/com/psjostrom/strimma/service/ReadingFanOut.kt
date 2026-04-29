package com.psjostrom.strimma.service

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debounces "downstream" side effects (alert, push, upload, HC write, broadcast) so an
 * Eversense-style cluster — OLD value stored, NEW value stored within ~1 s — fires the side
 * effects once, with the NEW value, after the cluster settles.
 *
 * Callers fire each time a reading is stored. Each [fire] cancels the previously-scheduled
 * dispatch and schedules a new one [DEBOUNCE_MS] later. When the debounce window elapses
 * without further fires, the callback runs with the most recent reading inside
 * [NonCancellable] so a service-shutdown after the dispatch starts doesn't drop the alert
 * partway through.
 *
 * **Shutdown semantics.** [stop] is suspending and FLUSHES any pending dispatch — it does
 * NOT silently drop it. Supersession (a new [fire] before the window elapses) drops the
 * prior dispatch as before; only [stop] flushes. This closes the medical-critical window
 * where a service kill within [DEBOUNCE_MS] would otherwise drop an urgent-low alert that
 * might not re-fire for up to 5 minutes (Eversense cadence). The flush runs under
 * [NonCancellable] so the OS shutdown grace can't truncate it.
 *
 * UI-visible side effects (notification update, widget refresh) MUST NOT go through here —
 * the user wants to see the latest BG immediately. Only call [fire] for effects whose
 * downstream-noise / spurious-alarm cost outweighs [DEBOUNCE_MS] of latency.
 *
 * **Threading.** [fire] and [stop] guard `pending`/`pendingJob` via `synchronized(this)` so
 * concurrent fires from different dispatchers can't leak a launch. Today every callsite
 * runs on `Dispatchers.Main` (the service's [CoroutineScope]), but the future-WorkManager-
 * job case is a one-line cost we'd rather not regret.
 */
@Singleton
class ReadingFanOut @Inject constructor(
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {
    private var job = SupervisorJob()
    private var scope = CoroutineScope(job + dispatcher)

    private data class Pending(
        val reading: GlucoseReading,
        val onSettled: suspend (GlucoseReading) -> Unit,
    )

    private var pending: Pending? = null
    private var pendingJob: Job? = null

    fun fire(reading: GlucoseReading, onSettled: suspend (GlucoseReading) -> Unit) {
        synchronized(this) {
            pendingJob?.cancel()
            pending = Pending(reading, onSettled)
            pendingJob = scope.launch {
                delay(DEBOUNCE_MS)
                // Snapshot + clear under the lock so a superseding fire() that lands
                // between this delay completing and the dispatch starting still wins
                // (the snapshot will be null because supersession overwrote it).
                val toFire = synchronized(this@ReadingFanOut) {
                    val snapshot = pending
                    pending = null
                    pendingJob = null
                    snapshot
                } ?: return@launch
                // NonCancellable so a service shutdown after the dispatch starts can't drop
                // an alert mid-fire. The delay above IS cancellable, so a superseding fire()
                // still cancels this coroutine before it reaches dispatch.
                withContext(NonCancellable) {
                    toFire.onSettled(toFire.reading)
                }
            }
        }
    }

    /**
     * Flush any pending dispatch and rebuild the scope.
     *
     * Suspending so the caller can `runBlocking { withTimeoutOrNull(N) { stop() } }` from
     * a non-suspend lifecycle hook (e.g. Service.onDestroy). The flush runs the latest
     * pending [fire]'s callback synchronously under [NonCancellable] — this is the
     * shutdown-safety guarantee the class contract is designed around.
     *
     * The scope rebuild matters because the FanOut is a `@Singleton` whose lifetime
     * exceeds the service's: when the foreground service is destroyed and re-created
     * (settings change, START_STICKY restart), the new service instance fires into the
     * same FanOut. Without the rebuild, the cancelled scope would refuse new launches
     * forever.
     */
    suspend fun stop() {
        val toFlush = synchronized(this) {
            val snapshot = pending
            pendingJob?.cancel()
            pending = null
            pendingJob = null
            snapshot
        }
        // Flush — shutdown shouldn't silently drop a critical alert.
        toFlush?.let {
            withContext(NonCancellable) { it.onSettled(it.reading) }
        }
        synchronized(this) {
            scope.cancel()
            job = SupervisorJob()
            scope = CoroutineScope(job + dispatcher)
        }
    }

    companion object {
        // 1500 ms covers Eversense's typical OLD→NEW cluster gap (~1 s observed in #192
        // logs) with margin. Smaller values let the OLD push/alert escape ahead of the
        // settled NEW; larger values add user-visible latency to alerts. 1.5 s is
        // imperceptible vs. the sensor cadence (1–5 min). The shutdown-flush in [stop]
        // means the user-visible cost of a longer window is bounded.
        internal const val DEBOUNCE_MS = 1500L
    }
}
