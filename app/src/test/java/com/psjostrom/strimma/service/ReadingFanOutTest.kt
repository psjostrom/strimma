package com.psjostrom.strimma.service

import com.psjostrom.strimma.data.GlucoseReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit test for [ReadingFanOut]. Verifies the debouncing contract that absorbs Eversense
 * cluster transients: when two `fire()` calls arrive within the debounce window, only the
 * second's callback runs, and only after the window elapses without further fires.
 *
 * Also pins the shutdown-flush contract: [ReadingFanOut.stop] must FLUSH any pending
 * dispatch synchronously, not silently drop it. This is the medical-critical guarantee
 * that prevents an urgent-low alert scheduled within the debounce window from being lost
 * when the foreground service is killed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingFanOutTest {

    private fun reading(ts: Long, sgv: Int) = GlucoseReading(
        ts = ts, sgv = sgv, direction = "Flat", delta = null, pushed = 0
    )

    @Test
    fun `single fire dispatches after debounce window`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fanOut = ReadingFanOut(dispatcher)
        val received = mutableListOf<GlucoseReading>()

        fanOut.fire(reading(100, 108)) { received.add(it) }

        // Before window elapses — no dispatch yet.
        advanceTimeBy(ReadingFanOut.DEBOUNCE_MS - 1)
        assertEquals(0, received.size)

        // After window elapses — dispatch fires.
        advanceUntilIdle()
        assertEquals(1, received.size)
        assertEquals(108, received[0].sgv)
    }

    @Test
    fun `cluster - second fire within window supersedes the first`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fanOut = ReadingFanOut(dispatcher)
        val received = mutableListOf<GlucoseReading>()

        // Eversense cluster pattern: OLD then NEW within ~1 ms.
        fanOut.fire(reading(100, 68)) { received.add(it) }
        advanceTimeBy(1)
        fanOut.fire(reading(101, 85)) { received.add(it) }

        advanceUntilIdle()
        assertEquals(
            "second fire's callback wins; first never dispatches",
            1, received.size
        )
        assertEquals(85, received[0].sgv)
    }

    @Test
    fun `two fires beyond debounce window both dispatch`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fanOut = ReadingFanOut(dispatcher)
        val received = mutableListOf<GlucoseReading>()

        fanOut.fire(reading(100, 100)) { received.add(it) }
        advanceTimeBy(ReadingFanOut.DEBOUNCE_MS + 1)
        // First should have dispatched by now.
        assertEquals(1, received.size)

        fanOut.fire(reading(200, 110)) { received.add(it) }
        advanceUntilIdle()
        assertEquals(2, received.size)
        assertEquals(100, received[0].sgv)
        assertEquals(110, received[1].sgv)
    }

    @Test
    fun `three fires within window keep only the last`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fanOut = ReadingFanOut(dispatcher)
        val received = mutableListOf<GlucoseReading>()

        // Multi-step cluster: each new fire cancels the previous pending dispatch.
        fanOut.fire(reading(100, 68)) { received.add(it) }
        advanceTimeBy(50)
        fanOut.fire(reading(150, 75)) { received.add(it) }
        advanceTimeBy(50)
        fanOut.fire(reading(200, 85)) { received.add(it) }

        advanceUntilIdle()
        assertEquals(1, received.size)
        assertEquals(85, received[0].sgv)
    }

    @Test
    fun `stop flushes pending dispatch instead of dropping it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fanOut = ReadingFanOut(dispatcher)
        val received = mutableListOf<GlucoseReading>()

        // Schedule a dispatch but don't let the debounce window elapse.
        fanOut.fire(reading(100, 50)) { received.add(it) }   // urgent low — must not be dropped
        advanceTimeBy(ReadingFanOut.DEBOUNCE_MS / 2)
        assertEquals("dispatch hasn't fired through the debounce yet", 0, received.size)

        // stop() must flush the pending dispatch — this is the shutdown-safety guarantee.
        fanOut.stop()
        advanceUntilIdle()
        assertEquals("stop() flushed the pending dispatch", 1, received.size)
        assertEquals(50, received[0].sgv)
    }

    @Test
    fun `stop flushes the latest pending after a cluster supersedes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fanOut = ReadingFanOut(dispatcher)
        val received = mutableListOf<GlucoseReading>()

        // Cluster: OLD then NEW within window. Then stop() before debounce elapses.
        fanOut.fire(reading(100, 68)) { received.add(it) }
        advanceTimeBy(1)
        fanOut.fire(reading(101, 85)) { received.add(it) }
        advanceTimeBy(ReadingFanOut.DEBOUNCE_MS / 2)

        fanOut.stop()
        advanceUntilIdle()
        assertEquals("only NEW fires; OLD was superseded before stop flushed", 1, received.size)
        assertEquals(85, received[0].sgv)
    }

    @Test
    fun `stop is a no-op when no dispatch is pending`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fanOut = ReadingFanOut(dispatcher)
        val received = mutableListOf<GlucoseReading>()

        // No fire() call — stop() must not invoke any callback.
        fanOut.stop()
        advanceUntilIdle()
        assertEquals(0, received.size)
    }

    @Test
    fun `stop after dispatch completed does not refire`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fanOut = ReadingFanOut(dispatcher)
        val received = mutableListOf<GlucoseReading>()

        fanOut.fire(reading(100, 108)) { received.add(it) }
        advanceUntilIdle()
        assertEquals(1, received.size)

        // Dispatch already happened; stop() must not fire it again.
        fanOut.stop()
        advanceUntilIdle()
        assertEquals(1, received.size)
    }

    @Test
    fun `fire after stop schedules a new dispatch`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fanOut = ReadingFanOut(dispatcher)
        val received = mutableListOf<GlucoseReading>()

        fanOut.fire(reading(100, 100)) { received.add(it) }
        fanOut.stop()
        advanceUntilIdle()
        // stop() flushed the pending dispatch.
        assertEquals(1, received.size)
        assertEquals(100, received[0].sgv)

        // After stop, fanOut should still be usable for future fires (e.g. service
        // restart picks up where it left off).
        fanOut.fire(reading(200, 110)) { received.add(it) }
        advanceUntilIdle()
        assertEquals(2, received.size)
        assertEquals(110, received[1].sgv)
    }
}
