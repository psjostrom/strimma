package com.psjostrom.strimma.service

import com.psjostrom.strimma.data.GlucoseReading
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the eager-vs-debounced split of side effects that fire when a new reading lands.
 * Extracted from [StrimmaService.onNewReading] so the load-bearing wiring contract has a
 * single testable surface — a maintainer who refactors the service can't accidentally move
 * an effect from one side to the other without breaking [ReadingDispatcherTest].
 *
 * - **eager** runs immediately so the user sees the latest BG without delay.
 * - The remaining effects (alert, push, upload, broadcast, HC write) run via [fanOut] after
 *   the debounce window, with cluster supersession handling the Eversense OLD→NEW case.
 *
 * `originatedRemotely=true` skips [push] only — used by the Nightscout follower path so we
 * don't echo a reading back to the server we just got it from.
 */
@Singleton
class ReadingDispatcher @Inject constructor(
    private val fanOut: ReadingFanOut,
) {
    @Suppress("LongParameterList") // Each effect is an independent collaborator the caller wires explicitly
    suspend fun dispatch(
        reading: GlucoseReading,
        originatedRemotely: Boolean,
        eager: suspend () -> Unit,
        alert: suspend (GlucoseReading) -> Unit,
        push: suspend (GlucoseReading) -> Unit,
        upload: suspend () -> Unit,
        broadcast: suspend (GlucoseReading) -> Unit,
        hc: suspend (GlucoseReading) -> Unit,
    ) {
        eager()
        fanOut.fire(reading) { settled ->
            alert(settled)
            if (!originatedRemotely) push(settled)
            upload()
            broadcast(settled)
            hc(settled)
        }
    }
}
