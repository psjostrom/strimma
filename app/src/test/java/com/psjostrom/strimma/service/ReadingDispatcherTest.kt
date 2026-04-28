package com.psjostrom.strimma.service

import com.psjostrom.strimma.data.GlucoseReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the eager-vs-debounced wiring contract so a future maintainer can't silently
 * regress #192 by moving an effect from one side to the other. Lambdas are recording
 * spies — legitimate per project rules ("Spy on callbacks only — testing that the
 * component fires them, not reimplementing their behavior").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingDispatcherTest {

    private fun reading(ts: Long, sgv: Int) = GlucoseReading(
        ts = ts, sgv = sgv, direction = "Flat", delta = null, pushed = 0
    )

    private class Spies {
        val eagerCount = AtomicInteger(0)
        val alerted = mutableListOf<Int>()
        val pushed = mutableListOf<Int>()
        val uploadCount = AtomicInteger(0)
        val broadcasted = mutableListOf<Int>()
        val hcWritten = mutableListOf<Int>()
    }

    private suspend fun ReadingDispatcher.dispatch(
        reading: GlucoseReading,
        cameFromNightscout: Boolean,
        spies: Spies,
    ) {
        dispatch(
            reading = reading,
            cameFromNightscout = cameFromNightscout,
            eager = { spies.eagerCount.incrementAndGet() },
            alert = { settled -> spies.alerted.add(settled.sgv) },
            push = { settled -> spies.pushed.add(settled.sgv) },
            upload = { spies.uploadCount.incrementAndGet() },
            broadcast = { settled -> spies.broadcasted.add(settled.sgv) },
            hc = { settled -> spies.hcWritten.add(settled.sgv) },
        )
    }

    @Test
    fun `single reading - eager fires immediately, debounced effects fire after window`() = runTest {
        val dispatcher = ReadingDispatcher(ReadingFanOut(StandardTestDispatcher(testScheduler)))
        val spies = Spies()

        dispatcher.dispatch(reading(100, 108), cameFromNightscout = false, spies)

        assertEquals("eager runs without waiting for the debounce window", 1, spies.eagerCount.get())
        assertTrue("debounced effects haven't fired yet", spies.pushed.isEmpty())

        advanceUntilIdle()

        assertEquals(listOf(108), spies.alerted)
        assertEquals(listOf(108), spies.pushed)
        assertEquals(1, spies.uploadCount.get())
        assertEquals(listOf(108), spies.broadcasted)
        assertEquals(listOf(108), spies.hcWritten)
    }

    @Test
    fun `cluster within debounce window - debounced effects fire once on NEW`() = runTest {
        val dispatcher = ReadingDispatcher(ReadingFanOut(StandardTestDispatcher(testScheduler)))
        val spies = Spies()

        // Eversense OLD→NEW within 1 ms — the case from issue #192.
        dispatcher.dispatch(reading(100, 68), cameFromNightscout = false, spies)
        dispatcher.dispatch(reading(101, 85), cameFromNightscout = false, spies)
        advanceUntilIdle()

        assertEquals("eager fires for every dispatch", 2, spies.eagerCount.get())
        assertEquals("alert fires once with NEW (no spurious urgent-low audible)", listOf(85), spies.alerted)
        assertEquals("push fires once with NEW (no stale OLD row in NS)", listOf(85), spies.pushed)
        assertEquals(1, spies.uploadCount.get())
        assertEquals(listOf(85), spies.broadcasted)
        assertEquals(listOf(85), spies.hcWritten)
    }

    @Test
    fun `cameFromNightscout true skips push but fires every other debounced effect`() = runTest {
        val dispatcher = ReadingDispatcher(ReadingFanOut(StandardTestDispatcher(testScheduler)))
        val spies = Spies()

        dispatcher.dispatch(reading(100, 108), cameFromNightscout = true, spies)
        advanceUntilIdle()

        assertEquals(
            "NS-follower path doesn't echo back to the source it just got the reading from",
            emptyList<Int>(), spies.pushed
        )
        // Everything else still fires — the user wants alerts, HC, broadcasts, Tidepool
        // upload regardless of where the reading originated.
        assertEquals(listOf(108), spies.alerted)
        assertEquals(1, spies.uploadCount.get())
        assertEquals(listOf(108), spies.broadcasted)
        assertEquals(listOf(108), spies.hcWritten)
    }

    @Test
    fun `cameFromNightscout true on cluster - still skips push for both`() = runTest {
        val dispatcher = ReadingDispatcher(ReadingFanOut(StandardTestDispatcher(testScheduler)))
        val spies = Spies()

        dispatcher.dispatch(reading(100, 68), cameFromNightscout = true, spies)
        dispatcher.dispatch(reading(101, 85), cameFromNightscout = true, spies)
        advanceUntilIdle()

        assertEquals(emptyList<Int>(), spies.pushed)
        assertEquals(listOf(85), spies.alerted) // still cluster-deduped
    }
}
