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
    fun `stop cancels any pending dispatch`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fanOut = ReadingFanOut(dispatcher)
        val received = mutableListOf<GlucoseReading>()

        fanOut.fire(reading(100, 108)) { received.add(it) }
        advanceTimeBy(ReadingFanOut.DEBOUNCE_MS / 2)

        fanOut.stop()
        advanceUntilIdle()
        assertEquals("pending dispatch cancelled by stop()", 0, received.size)
    }

    @Test
    fun `fire after stop schedules a new dispatch`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fanOut = ReadingFanOut(dispatcher)
        val received = mutableListOf<GlucoseReading>()

        fanOut.fire(reading(100, 100)) { received.add(it) }
        fanOut.stop()
        advanceUntilIdle()
        assertEquals(0, received.size)

        // After stop, fanOut should still be usable for future fires (e.g. service
        // restart picks up where it left off).
        fanOut.fire(reading(200, 110)) { received.add(it) }
        advanceUntilIdle()
        assertEquals(1, received.size)
        assertEquals(110, received[0].sgv)
    }
}
