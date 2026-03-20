package com.psjostrom.strimma.receiver

import org.junit.Assert.*
import org.junit.Test

private const val MGDL_FACTOR = 18.0182

class GlucoseParserTest {

    @Test
    fun `parses dot decimal as mmol and converts to mgdl`() {
        assertEquals(13.5 * MGDL_FACTOR, tryParseGlucose("13.5")!!, 0.1)
    }

    @Test
    fun `parses comma decimal as mmol and converts to mgdl`() {
        assertEquals(13.5 * MGDL_FACTOR, tryParseGlucose("13,5")!!, 0.1)
    }

    @Test
    fun `parses single digit mmol`() {
        assertEquals(7.8 * MGDL_FACTOR, tryParseGlucose("7.8")!!, 0.1)
    }

    @Test
    fun `strips mmol unit suffix`() {
        assertEquals(5.6 * MGDL_FACTOR, tryParseGlucose("5.6 mmol/L")!!, 0.1)
        assertEquals(5.6 * MGDL_FACTOR, tryParseGlucose("5,6 mmol/l")!!, 0.1)
    }

    @Test
    fun `parses mg-dL with unit suffix`() {
        assertEquals(120.0, tryParseGlucose("120 mg/dL")!!, 0.01)
    }

    @Test
    fun `strips non-breaking space`() {
        assertEquals(8.3 * MGDL_FACTOR, tryParseGlucose("8,3\u00a0mmol/L")!!, 0.1)
    }

    @Test
    fun `strips word joiner`() {
        assertEquals(6.1 * MGDL_FACTOR, tryParseGlucose("\u20606,1")!!, 0.1)
    }

    @Test
    fun `strips unicode arrows`() {
        assertEquals(9.2 * MGDL_FACTOR, tryParseGlucose("↗ 9,2")!!, 0.1)
        assertEquals(5.5 * MGDL_FACTOR, tryParseGlucose("5,5 ↓")!!, 0.1)
        assertEquals(7.0 * MGDL_FACTOR, tryParseGlucose("→7,0")!!, 0.1)
    }

    @Test
    fun `strips inequality signs`() {
        assertEquals(3.9 * MGDL_FACTOR, tryParseGlucose("≤3,9")!!, 0.1)
        assertEquals(3.9 * MGDL_FACTOR, tryParseGlucose("≥3.9")!!, 0.1)
    }

    @Test
    fun `rejects empty string`() {
        assertNull(tryParseGlucose(""))
    }

    @Test
    fun `rejects blank string`() {
        assertNull(tryParseGlucose("   "))
    }

    @Test
    fun `rejects arrow only`() {
        assertNull(tryParseGlucose("↗"))
    }

    @Test
    fun `parses mg-dL integer directly`() {
        assertEquals(120.0, tryParseGlucose("120")!!, 0.01)
        assertEquals(180.0, tryParseGlucose("180")!!, 0.01)
        assertEquals(55.0, tryParseGlucose("55")!!, 0.01)
    }

    @Test
    fun `rejects ambiguous integers 20-50`() {
        assertNull(tryParseGlucose("40"))
        assertNull(tryParseGlucose("20"))
        assertNull(tryParseGlucose("50"))
    }

    @Test
    fun `rejects mg-dL out of range`() {
        assertNull(tryParseGlucose("501"))
        assertNull(tryParseGlucose("10"))
    }

    @Test
    fun `rejects negative values`() {
        assertNull(tryParseGlucose("-3.5"))
    }

    @Test
    fun `rejects three-digit whole part`() {
        assertNull(tryParseGlucose("120.5"))
    }

    @Test
    fun `rejects text without numbers`() {
        assertNull(tryParseGlucose("Strimma"))
        assertNull(tryParseGlucose("CamAPS FX"))
    }

    @Test
    fun `handles realistic CamAPS notification text`() {
        // Real notification text patterns from CamAPS FX (mmol/L → converted to mg/dL)
        assertEquals(5.7 * MGDL_FACTOR, tryParseGlucose("5,7")!!, 0.1)
        assertEquals(13.5 * MGDL_FACTOR, tryParseGlucose("13,5")!!, 0.1)
        assertEquals(3.9 * MGDL_FACTOR, tryParseGlucose("3,9")!!, 0.1)
    }
}
