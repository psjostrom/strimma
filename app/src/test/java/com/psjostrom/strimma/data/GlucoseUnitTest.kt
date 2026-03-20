package com.psjostrom.strimma.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlucoseUnitTest {

    // All methods now take mg/dL as input

    @Test
    fun `format mmol converts and shows one decimal`() {
        assertEquals("5.4", GlucoseUnit.MMOL.format(97.3))
        assertEquals("12.0", GlucoseUnit.MMOL.format(216.2))
    }

    @Test
    fun `format mgdl shows whole number`() {
        assertEquals("97", GlucoseUnit.MGDL.format(97.3))
        assertEquals("216", GlucoseUnit.MGDL.format(216.2))
    }

    @Test
    fun `format int overload works`() {
        assertEquals("5.5", GlucoseUnit.MMOL.format(100))
        assertEquals("100", GlucoseUnit.MGDL.format(100))
    }

    @Test
    fun `formatWithUnit includes label`() {
        assertEquals("5.4 mmol/L", GlucoseUnit.MMOL.formatWithUnit(97.3))
        assertEquals("97 mg/dL", GlucoseUnit.MGDL.formatWithUnit(97.3))
    }

    @Test
    fun `formatDelta mmol converts and shows sign and unit`() {
        assertEquals("+0.3 mmol/l", GlucoseUnit.MMOL.formatDelta(5.4))
        assertEquals("-0.5 mmol/l", GlucoseUnit.MMOL.formatDelta(-9.0))
    }

    @Test
    fun `formatDelta mgdl shows sign and unit`() {
        assertEquals("+5 mg/dl", GlucoseUnit.MGDL.formatDelta(5.4))
        assertEquals("-9 mg/dl", GlucoseUnit.MGDL.formatDelta(-9.0))
    }

    @Test
    fun `parseThreshold mmol converts to mgdl`() {
        val result = GlucoseUnit.MMOL.parseThreshold("4.0")!!
        assertEquals(72.0f, result, 1.0f)

        val high = GlucoseUnit.MMOL.parseThreshold("10.0")!!
        assertEquals(180.0f, high, 1.0f)
    }

    @Test
    fun `parseThreshold mgdl returns value as-is`() {
        assertEquals(72.0f, GlucoseUnit.MGDL.parseThreshold("72")!!, 0.01f)
        assertEquals(180.0f, GlucoseUnit.MGDL.parseThreshold("180")!!, 0.01f)
    }

    @Test
    fun `parseThreshold handles comma decimal`() {
        val result = GlucoseUnit.MMOL.parseThreshold("4,5")!!
        assertEquals(81.0f, result, 1.0f)
    }

    @Test
    fun `parseThreshold returns null for invalid input`() {
        assertNull(GlucoseUnit.MMOL.parseThreshold("abc"))
        assertNull(GlucoseUnit.MGDL.parseThreshold(""))
    }

    @Test
    fun `formatThreshold roundtrips through parseThreshold`() {
        val mgdl = 72.0f
        val formatted = GlucoseUnit.MGDL.formatThreshold(mgdl)
        val parsed = GlucoseUnit.MGDL.parseThreshold(formatted)!!
        assertEquals(mgdl, parsed, 0.5f)
    }

    @Test
    fun `displayValue converts correctly`() {
        assertEquals(5.4, GlucoseUnit.MMOL.displayValue(97.3), 0.1)
        assertEquals(97.3, GlucoseUnit.MGDL.displayValue(97.3), 0.001)
    }
}
