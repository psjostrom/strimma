package com.psjostrom.strimma.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlucoseUnitTest {

    @Test
    fun `format mmol shows one decimal`() {
        assertEquals("5.4", GlucoseUnit.MMOL.format(5.4))
        assertEquals("12.0", GlucoseUnit.MMOL.format(12.0))
    }

    @Test
    fun `format mgdl shows whole number`() {
        assertEquals("97", GlucoseUnit.MGDL.format(5.4))
        assertEquals("216", GlucoseUnit.MGDL.format(12.0))
    }

    @Test
    fun `formatWithUnit includes label`() {
        assertEquals("5.4 mmol/L", GlucoseUnit.MMOL.formatWithUnit(5.4))
        assertEquals("97 mg/dL", GlucoseUnit.MGDL.formatWithUnit(5.4))
    }

    @Test
    fun `formatDelta mmol shows sign and unit`() {
        assertEquals("+0.3 mmol/l", GlucoseUnit.MMOL.formatDelta(0.3))
        assertEquals("-0.5 mmol/l", GlucoseUnit.MMOL.formatDelta(-0.5))
    }

    @Test
    fun `formatDelta mgdl shows sign and unit`() {
        assertEquals("+5 mg/dl", GlucoseUnit.MGDL.formatDelta(0.3))
        assertEquals("-9 mg/dl", GlucoseUnit.MGDL.formatDelta(-0.5))
    }

    @Test
    fun `parseThreshold mmol returns value as-is`() {
        assertEquals(4.0f, GlucoseUnit.MMOL.parseThreshold("4.0")!!, 0.01f)
        assertEquals(10.5f, GlucoseUnit.MMOL.parseThreshold("10.5")!!, 0.01f)
    }

    @Test
    fun `parseThreshold mgdl converts to mmol`() {
        val result = GlucoseUnit.MGDL.parseThreshold("72")!!
        assertEquals(4.0f, result, 0.1f)

        val high = GlucoseUnit.MGDL.parseThreshold("180")!!
        assertEquals(10.0f, high, 0.1f)
    }

    @Test
    fun `parseThreshold handles comma decimal`() {
        assertEquals(4.5f, GlucoseUnit.MMOL.parseThreshold("4,5")!!, 0.01f)
    }

    @Test
    fun `parseThreshold returns null for invalid input`() {
        assertNull(GlucoseUnit.MMOL.parseThreshold("abc"))
        assertNull(GlucoseUnit.MGDL.parseThreshold(""))
    }

    @Test
    fun `formatThreshold roundtrips through parseThreshold`() {
        val mmol = 4.0f
        val formatted = GlucoseUnit.MGDL.formatThreshold(mmol)
        val parsed = GlucoseUnit.MGDL.parseThreshold(formatted)!!
        assertEquals(mmol, parsed, 0.2f)
    }

    @Test
    fun `displayValue converts correctly`() {
        assertEquals(5.4, GlucoseUnit.MMOL.displayValue(5.4), 0.001)
        assertEquals(97.3, GlucoseUnit.MGDL.displayValue(5.4), 0.5)
    }
}
