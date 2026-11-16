package com.psjostrom.strimma.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt

class DirectionComputerTest {

    private val computer = DirectionComputer()
    private val baseTs = 1_700_000_000_000L

    private fun reading(minutesAgo: Double, sgv: Int) = GlucoseReading(
        ts = baseTs - (minutesAgo * 60_000).toLong(),
        sgv = sgv,
        direction = "NONE", delta = null, pushed = 1
    )

    /** Readings every [intervalMinutes] for the last 20 minutes, rising [mgdlPerMin], ending at 120 mg/dL now. */
    private fun trend(intervalMinutes: Int, mgdlPerMin: Double): Pair<List<GlucoseReading>, GlucoseReading> {
        val history = (20 downTo intervalMinutes step intervalMinutes).map {
            reading(it.toDouble(), (120 - it * mgdlPerMin).roundToInt())
        }
        return history to reading(0.0, 120)
    }

    private fun directionFor(mgdlPerMin: Double): Direction {
        val (history, current) = trend(intervalMinutes = 5, mgdlPerMin = mgdlPerMin)
        return computer.compute(history, current).first
    }

    @Test
    fun `maps rate of change to EASD thresholds`() {
        assertEquals(Direction.Flat, directionFor(0.0))
        assertEquals(Direction.FortyFiveUp, directionFor(1.6))
        assertEquals(Direction.SingleUp, directionFor(2.4))
        assertEquals(Direction.DoubleUp, directionFor(4.0))
        assertEquals(Direction.FortyFiveDown, directionFor(-1.6))
        assertEquals(Direction.SingleDown, directionFor(-2.4))
        assertEquals(Direction.DoubleDown, directionFor(-4.0))
    }

    @Test
    fun `5-minute sensor delta is the full change since the previous reading`() {
        val (history, current) = trend(intervalMinutes = 5, mgdlPerMin = 2.0)
        val (direction, delta) = computer.compute(history, current)
        assertEquals(10.0, delta!!, 0.001)
        assertEquals(Direction.FortyFiveUp, direction)
    }

    @Test
    fun `1-minute and 5-minute sensors give the same delta for the same rise`() {
        val (oneMinHistory, oneMinCurrent) = trend(intervalMinutes = 1, mgdlPerMin = -2.4)
        val (fiveMinHistory, fiveMinCurrent) = trend(intervalMinutes = 5, mgdlPerMin = -2.4)
        val oneMin = computer.compute(oneMinHistory, oneMinCurrent)
        val fiveMin = computer.compute(fiveMinHistory, fiveMinCurrent)
        assertEquals(-12.0, oneMin.second!!, 0.001)
        assertEquals(-12.0, fiveMin.second!!, 0.001)
        assertEquals(Direction.SingleDown, oneMin.first)
        assertEquals(Direction.SingleDown, fiveMin.first)
    }

    @Test
    fun `one noisy 1-minute reading does not flip a flat arrow`() {
        // A raw 5-minute delta would be +6 mg/dL (FortyFiveUp). Averaging the last 2.5 minutes
        // of readings at each end gives (120 + 120 + 126) / 3 - 120 = +2 (Flat).
        val history = (10 downTo 1).map { reading(it.toDouble(), 120) }
        val (direction, delta) = computer.compute(history, reading(0.0, 126))
        assertEquals(2.0, delta!!, 0.001)
        assertEquals(Direction.Flat, direction)
    }

    @Test
    fun `delta is scaled to 5 minutes when readings are not exactly 5 minutes apart`() {
        val history = listOf(reading(4.0, 100))
        val (_, delta) = computer.compute(history, reading(0.0, 108))
        assertEquals(10.0, delta!!, 0.001)
    }

    @Test
    fun `missed reading still gives a 5-minute delta from the reading 10 minutes ago`() {
        val history = listOf(reading(15.0, 90), reading(10.0, 100))
        val (direction, delta) = computer.compute(history, reading(0.0, 120))
        assertEquals(10.0, delta!!, 0.001)
        assertEquals(Direction.FortyFiveUp, direction)
    }

    @Test
    fun `NONE with no history`() {
        val (direction, delta) = computer.compute(emptyList(), reading(0.0, 120))
        assertEquals(Direction.NONE, direction)
        assertNull(delta)
    }

    @Test
    fun `NONE when only readings from the last 2 minutes exist`() {
        val history = listOf(reading(2.0, 110), reading(1.0, 115))
        val (direction, delta) = computer.compute(history, reading(0.0, 120))
        assertEquals(Direction.NONE, direction)
        assertNull(delta)
    }

    @Test
    fun `NONE when the newest past reading is more than 15 minutes old`() {
        val history = listOf(reading(20.0, 100))
        val (direction, delta) = computer.compute(history, reading(0.0, 120))
        assertEquals(Direction.NONE, direction)
        assertNull(delta)
    }
}
