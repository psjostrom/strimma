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
 * UI-visible side effects (notification update, widget refresh) MUST NOT go through here —
 * the user wants to see the latest BG immediately. Only call [fire] for effects whose
 * downstream-noise / spurious-alarm cost outweighs 250 ms of latency.
 */
@Singleton
class ReadingFanOut @Inject constructor(
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {
    private var job = SupervisorJob()
    private var scope = CoroutineScope(job + dispatcher)

    @Volatile
    private var pending: Job? = null

    fun fire(reading: GlucoseReading, onSettled: suspend (GlucoseReading) -> Unit) {
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

    /** Cancel any pending dispatch. Called from StrimmaService.onDestroy. */
    fun stop() {
        scope.cancel()
        job = SupervisorJob()
        scope = CoroutineScope(job + dispatcher)
        pending = null
    }

    companion object {
        internal const val DEBOUNCE_MS = 250L
    }
}
