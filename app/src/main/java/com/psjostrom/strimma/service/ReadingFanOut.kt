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
 * **Vulnerability window.** The [delay] before the dispatch IS cancellable — that's how
 * supersession (and explicit [stop]) work. If the OS kills the process or [stop] is called
 * during the [DEBOUNCE_MS] window, the dispatch is dropped: no alert, no push, no HC write
 * for that cluster. Pre-debounce behavior had a much narrower window (~ms, the duration of
 * an inline alert call); this design accepts a 1.5 s window in exchange for cluster
 * suppression. The next reading from the sensor (≤1 min later for Libre 3, ≤5 min for
 * Eversense) resumes the side-effect chain on a fresh service instance under START_STICKY.
 *
 * UI-visible side effects (notification update, widget refresh) MUST NOT go through here —
 * the user wants to see the latest BG immediately. Only call [fire] for effects whose
 * downstream-noise / spurious-alarm cost outweighs [DEBOUNCE_MS] of latency.
 *
 * **Threading.** [fire] and [stop] guard the cancel-and-reassign of [pending] via
 * `synchronized(this)` so concurrent fires from different dispatchers can't leak a launch.
 * Today every callsite runs on `Dispatchers.Main` (the service's [CoroutineScope]), but the
 * future-WorkManager-job case is a one-line cost we'd rather not regret.
 */
@Singleton
class ReadingFanOut @Inject constructor(
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {
    private var job = SupervisorJob()
    private var scope = CoroutineScope(job + dispatcher)

    private var pending: Job? = null

    fun fire(reading: GlucoseReading, onSettled: suspend (GlucoseReading) -> Unit) {
        synchronized(this) {
            pending?.cancel()
            pending = scope.launch {
                delay(DEBOUNCE_MS)
                // NonCancellable so a service shutdown after the dispatch starts can't drop
                // an alert mid-fire. The delay above IS cancellable, so a superseding fire()
                // still cancels this coroutine before it reaches dispatch.
                withContext(NonCancellable) {
                    onSettled(reading)
                }
            }
        }
    }

    /**
     * Cancel any pending dispatch and rebuild the scope.
     *
     * The rebuild matters because the FanOut is a `@Singleton` whose lifetime exceeds the
     * service's: when the foreground service is destroyed and re-created (settings change,
     * START_STICKY restart), the new service instance fires into the same FanOut. Without
     * the rebuild, the cancelled scope would refuse new launches forever.
     */
    fun stop() {
        synchronized(this) {
            scope.cancel()
            job = SupervisorJob()
            scope = CoroutineScope(job + dispatcher)
            pending = null
        }
    }

    companion object {
        // 1500 ms covers Eversense's typical OLD→NEW cluster gap (~1 s observed in #192
        // logs) with margin. Smaller values let the OLD push/alert escape ahead of the
        // settled NEW; larger values add user-visible latency to alerts. 1.5 s is
        // imperceptible vs. the sensor cadence (1–5 min).
        internal const val DEBOUNCE_MS = 1500L
    }
}
