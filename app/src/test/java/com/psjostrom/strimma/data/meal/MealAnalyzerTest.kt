package com.psjostrom.strimma.data.meal

import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.Treatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class MealAnalyzerTest {

    private val analyzer = MealAnalyzer()

    // Helpers

    private val baseTime = Instant.parse("2026-03-27T12:00:00Z").toEpochMilli()

    private fun minutes(n: Long) = Duration.ofMinutes(n).toMillis()

    private fun reading(minutesFromMeal: Long, sgv: Int) = GlucoseReading(
        ts = baseTime + minutes(minutesFromMeal),
        sgv = sgv,
        direction = "Flat",
        delta = null,
        pushed = 1
    )

    private fun meal(carbGrams: Double) = Treatment(
        id = "meal-1",
        createdAt = baseTime,
        eventType = "Meal Bolus",
        insulin = 5.0,
        carbs = carbGrams,
        basalRate = null,
        duration = null,
        enteredBy = "user",
        fetchedAt = baseTime
    )

    /**
     * Generates realistic postprandial BG pattern:
     * - Pre-meal: -15 to 0 min at 108
     * - Rise: 1 to 45 min, linear 108→180
     * - Fall: 46 to 150 min, linear 180→110
     * - Stable: 151 to 200 min at 110
     */
    private fun spikeAndRecoverReadings(): List<GlucoseReading> {
        val preMeal = (-15L..0L).map { reading(it, 108) }
        val rise = (1L..45L).map { t ->
            val sgv = 108 + ((180 - 108) * (t / 45.0)).toInt()
            reading(t, sgv)
        }
        val fall = (46L..150L).map { t ->
            val sgv = 180 - ((180 - 110) * ((t - 46) / (150.0 - 46))).toInt()
            reading(t, sgv)
        }
        val stable = (151L..200L).map { reading(it, 110) }
        return preMeal + rise + fall + stable
    }

    // --- Tests ---

    @Test
    fun `baseline computed from 15 min pre-meal readings`() {
        val preMeal = (-15L..-1L).map { reading(it, 108) }
        val postMeal = (1L..180L).map { reading(it, 108) }
        val allReadings = preMeal + postMeal

        val result = analyzer.analyze(
            meal = meal(50.0),
            readings = allReadings,
            bgLow = 72.0,
            bgHigh = 180.0,
            nextMealTime = null,
            allTreatments = emptyList(),
            tauMinutes = 55.0
        )

        assertNotNull(result)
        result!!

        // Average of all 108 readings = 108
        assertEquals(108.0, result.baselineMgdl, 0.1)
    }

    @Test
    fun `peak and excursion computed correctly`() {
        val readings = spikeAndRecoverReadings()

        val result = analyzer.analyze(
            meal = meal(50.0),
            readings = readings,
            bgLow = 72.0,
            bgHigh = 180.0,
            nextMealTime = null,
            allTreatments = emptyList(),
            tauMinutes = 55.0
        )

        assertNotNull(result)
        result!!

        assertEquals(108.0, result.baselineMgdl, 0.1)
        assertEquals(180, result.peakMgdl.toInt())
        // Excursion = peak - baseline = 180 - 108 = 72
        assertEquals(72.0, result.excursionMgdl, 1.0)
    }

    @Test
    fun `time to peak in minutes`() {
        val readings = spikeAndRecoverReadings()

        val result = analyzer.analyze(
            meal = meal(50.0),
            readings = readings,
            bgLow = 72.0,
            bgHigh = 180.0,
            nextMealTime = null,
            allTreatments = emptyList(),
            tauMinutes = 55.0
        )

        assertNotNull(result)
        result!!

        // Peak is at 45 minutes
        assertEquals(45, result.timeToPeakMinutes)
    }

    @Test
    fun `recovery time detected when BG returns to baseline`() {
        val readings = spikeAndRecoverReadings()

        val result = analyzer.analyze(
            meal = meal(50.0),
            readings = readings,
            bgLow = 72.0,
            bgHigh = 180.0,
            nextMealTime = null,
            allTreatments = emptyList(),
            tauMinutes = 55.0
        )

        assertNotNull(result)
        result!!

        // BG returns to ~110 (within baseline ±5) around 150 min
        assertNotNull(result.recoveryMinutes)
        assertTrue("Recovery should be around 145-155 min", result.recoveryMinutes!! in 145..155)
    }

    @Test
    fun `recovery null when BG stays elevated`() {
        val preMeal = (-15L..-1L).map { reading(it, 108) }
        val spike = (1L..180L).map { reading(it, 200) }
        val allReadings = preMeal + spike

        val result = analyzer.analyze(
            meal = meal(50.0),
            readings = allReadings,
            bgLow = 72.0,
            bgHigh = 180.0,
            nextMealTime = null,
            allTreatments = emptyList(),
            tauMinutes = 55.0
        )

        assertNotNull(result)
        result!!

        assertEquals(200, result.peakMgdl.toInt())
        assertNull(result.recoveryMinutes)
    }

    @Test
    fun `window cut short by next meal`() {
        val readings = spikeAndRecoverReadings()
        val nextMealTime = baseTime + minutes(90) // Next meal at 90 min

        val result = analyzer.analyze(
            meal = meal(50.0),
            readings = readings,
            bgLow = 72.0,
            bgHigh = 180.0,
            nextMealTime = nextMealTime,
            allTreatments = emptyList(),
            tauMinutes = 55.0
        )

        assertNotNull(result)
        result!!

        assertEquals(90, result.windowMinutes)
    }

    @Test
    fun `default window is 180 minutes`() {
        val readings = spikeAndRecoverReadings()

        val result = analyzer.analyze(
            meal = meal(50.0),
            readings = readings,
            bgLow = 72.0,
            bgHigh = 180.0,
            nextMealTime = null,
            allTreatments = emptyList(),
            tauMinutes = 55.0
        )

        assertNotNull(result)
        result!!

        // BG recovers before 180 min, so window stays at default 180
        assertEquals(180, result.windowMinutes)
    }

    @Test
    fun `window extends to 240 if not recovered at 180`() {
        // BG stays high through 240 min
        val preMeal = (-15L..-1L).map { reading(it, 108) }
        val spikeAndStay = (1L..240L).map { reading(it, 200) }
        val allReadings = preMeal + spikeAndStay

        val result = analyzer.analyze(
            meal = meal(50.0),
            readings = allReadings,
            bgLow = 72.0,
            bgHigh = 180.0,
            nextMealTime = null,
            allTreatments = emptyList(),
            tauMinutes = 55.0
        )

        assertNotNull(result)
        result!!

        // Window extends because BG hasn't returned to baseline at 180 min
        assertEquals(240, result.windowMinutes)
    }

    @Test
    fun `TIR computed within window`() {
        val readings = spikeAndRecoverReadings()

        val result = analyzer.analyze(
            meal = meal(50.0),
            readings = readings,
            bgLow = 72.0,
            bgHigh = 180.0,
            nextMealTime = null,
            allTreatments = emptyList(),
            tauMinutes = 55.0
        )

        assertNotNull(result)
        result!!

        // All readings in window (1-180 min) are between 72 and 180, so TIR = 100%
        assertEquals(100.0, result.tirPercent, 0.1)
    }

    @Test
    fun `iAUC is positive for spike above baseline`() {
        val readings = spikeAndRecoverReadings()

        val result = analyzer.analyze(
            meal = meal(50.0),
            readings = readings,
            bgLow = 72.0,
            bgHigh = 180.0,
            nextMealTime = null,
            allTreatments = emptyList(),
            tauMinutes = 55.0
        )

        assertNotNull(result)
        result!!

        // iAUC should be positive since BG spikes above baseline
        assertTrue("iAUC should be > 0 for a spike", result.iAucMgdlMin > 0.0)
    }

    @Test
    fun `returns null with insufficient readings`() {
        val preMeal = listOf(reading(-15, 108))
        val postMeal = listOf(reading(30, 150))
        val allReadings = preMeal + postMeal

        val result = analyzer.analyze(
            meal = meal(50.0),
            readings = allReadings,
            bgLow = 72.0,
            bgHigh = 180.0,
            nextMealTime = null,
            allTreatments = emptyList(),
            tauMinutes = 55.0
        )

        // Only 1 reading in postprandial window, need at least 5
        assertNull(result)
    }

    @Test
    fun `IOB at meal computed correctly`() {
        // Previous bolus 30 min before meal
        val previousBolus = Treatment(
            id = "prev-bolus",
            createdAt = baseTime - minutes(30),
            eventType = "Correction Bolus",
            insulin = 2.0,
            carbs = null,
            basalRate = null,
            duration = null,
            enteredBy = "user",
            fetchedAt = baseTime - minutes(30)
        )

        val readings = spikeAndRecoverReadings()

        val result = analyzer.analyze(
            meal = meal(50.0),
            readings = readings,
            bgLow = 72.0,
            bgHigh = 180.0,
            nextMealTime = null,
            allTreatments = listOf(previousBolus),
            tauMinutes = 55.0
        )

        assertNotNull(result)
        result!!

        // IOB should be > 0 from the previous bolus
        assertTrue("IOB at meal should be > 0", result.iobAtMeal > 0.0)
    }

    @Test
    fun `baseline from single reading when fewer than 3 in pre-window`() {
        // Only 1 reading in pre-window
        val preMeal = listOf(reading(-5, 110))
        val postMeal = (1L..180L).map { reading(it, 150) }
        val allReadings = preMeal + postMeal

        val result = analyzer.analyze(
            meal = meal(50.0),
            readings = allReadings,
            bgLow = 72.0,
            bgHigh = 180.0,
            nextMealTime = null,
            allTreatments = emptyList(),
            tauMinutes = 55.0
        )

        assertNotNull(result)
        result!!

        // Should use the single closest reading (110)
        assertEquals(110.0, result.baselineMgdl, 0.1)
    }

    @Test
    fun `returns null when no pre-meal readings`() {
        val postMeal = (1L..180L).map { reading(it, 150) }

        val result = analyzer.analyze(
            meal = meal(50.0),
            readings = postMeal,
            bgLow = 72.0,
            bgHigh = 180.0,
            nextMealTime = null,
            allTreatments = emptyList(),
            tauMinutes = 55.0
        )

        // No baseline = can't analyze
        assertNull(result)
    }
}
