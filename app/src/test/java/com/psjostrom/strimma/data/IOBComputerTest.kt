package com.psjostrom.strimma.data

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp

class IOBComputerTest {

    private val tau = 55.0 // Fiasp

    private fun treatment(dose: Double, minutesAgo: Long, now: Long = 1_000_000_000_000L): Treatment {
        return Treatment(
            id = "test-$minutesAgo",
            createdAt = now - minutesAgo * 60_000,
            eventType = "Correction Bolus",
            insulin = dose,
            carbs = null,
            basalRate = null,
            duration = null,
            enteredBy = "test",
            fetchedAt = now
        )
    }

    @Test
    fun `zero minutes ago gives full dose`() {
        // IOB(0) = dose * (1 + 0) * exp(0) = dose
        val now = 1_000_000_000_000L
        val result = IOBComputer.computeIOB(
            listOf(treatment(5.0, 0, now)),
            now, tau
        )
        assertEquals(5.0, result, 0.05)
    }

    @Test
    fun `one tau gives expected decay`() {
        // IOB(tau) = dose * (1 + 1) * exp(-1) = dose * 2 * 0.3679 = dose * 0.7358
        val now = 1_000_000_000_000L
        val result = IOBComputer.computeIOB(
            listOf(treatment(5.0, tau.toLong(), now)),
            now, tau
        )
        val expected = 5.0 * 2.0 * exp(-1.0)
        assertEquals(expected, result, 0.05)
    }

    @Test
    fun `five tau gives near-zero`() {
        // IOB(5*tau) = dose * (1 + 5) * exp(-5) = dose * 6 * 0.0067 = dose * 0.0404
        val now = 1_000_000_000_000L
        val result = IOBComputer.computeIOB(
            listOf(treatment(5.0, (5 * tau).toLong(), now)),
            now, tau
        )
        val expected = 5.0 * 6.0 * exp(-5.0)
        assertEquals(expected, result, 0.05)
    }

    @Test
    fun `beyond lookback window returns zero`() {
        val now = 1_000_000_000_000L
        // 6 * tau is beyond the 5 * tau lookback
        val result = IOBComputer.computeIOB(
            listOf(treatment(5.0, (6 * tau).toLong(), now)),
            now, tau
        )
        assertEquals(0.0, result, 0.001)
    }

    @Test
    fun `multiple boluses sum correctly`() {
        val now = 1_000_000_000_000L
        val treatments = listOf(
            treatment(3.0, 0, now),
            treatment(2.0, 30, now)
        )
        val result = IOBComputer.computeIOB(treatments, now, tau)

        val iob1 = 3.0 // at t=0
        val t2 = 30.0 / tau
        val iob2 = 2.0 * (1.0 + t2) * exp(-t2)
        val expected = Math.round((iob1 + iob2) * 10.0) / 10.0

        assertEquals(expected, result, 0.05)
    }

    @Test
    fun `treatments with null insulin are ignored`() {
        val now = 1_000_000_000_000L
        val carbOnly = Treatment(
            id = "carb",
            createdAt = now,
            eventType = "Carb Correction",
            insulin = null,
            carbs = 30.0,
            basalRate = null,
            duration = null,
            enteredBy = "test",
            fetchedAt = now
        )
        val result = IOBComputer.computeIOB(listOf(carbOnly), now, tau)
        assertEquals(0.0, result, 0.001)
    }

    @Test
    fun `future treatments are ignored`() {
        val now = 1_000_000_000_000L
        val future = Treatment(
            id = "future",
            createdAt = now + 60_000,
            eventType = "Correction Bolus",
            insulin = 5.0,
            carbs = null,
            basalRate = null,
            duration = null,
            enteredBy = "test",
            fetchedAt = now
        )
        val result = IOBComputer.computeIOB(listOf(future), now, tau)
        assertEquals(0.0, result, 0.001)
    }

    @Test
    fun `empty list returns zero`() {
        val result = IOBComputer.computeIOB(emptyList(), System.currentTimeMillis(), tau)
        assertEquals(0.0, result, 0.001)
    }

    @Test
    fun `different tau values work`() {
        val now = 1_000_000_000_000L
        // Lyumjev tau=50, NovoRapid tau=75
        val lyumjevResult = IOBComputer.computeIOB(
            listOf(treatment(5.0, 50, now)), now, 50.0
        )
        val novoRapidResult = IOBComputer.computeIOB(
            listOf(treatment(5.0, 50, now)), now, 75.0
        )
        // NovoRapid decays slower, so more IOB at same time
        assertTrue("NovoRapid should have higher IOB than Lyumjev at same time",
            novoRapidResult > lyumjevResult)
    }

    @Test
    fun `result is rounded to one decimal`() {
        val now = 1_000_000_000_000L
        val result = IOBComputer.computeIOB(
            listOf(treatment(3.7, 20, now)),
            now, tau
        )
        // Check that the result has at most one decimal
        val str = "%.1f".format(result)
        assertEquals(str.toDouble(), result, 0.001)
    }
}
