package com.psjostrom.strimma.receiver

import org.junit.Assert.*
import org.junit.Test

class GlucoseParserTest {

    @Test
    fun `parses dot decimal`() {
        assertEquals(13.5, tryParseGlucose("13.5")!!, 0.001)
    }

    @Test
    fun `parses comma decimal`() {
        assertEquals(13.5, tryParseGlucose("13,5")!!, 0.001)
    }

    @Test
    fun `parses single digit`() {
        assertEquals(7.8, tryParseGlucose("7.8")!!, 0.001)
    }

    @Test
    fun `strips mmol unit suffix`() {
        assertEquals(5.6, tryParseGlucose("5.6 mmol/L")!!, 0.001)
        assertEquals(5.6, tryParseGlucose("5,6 mmol/l")!!, 0.001)
    }

    @Test
    fun `strips mg unit suffix`() {
        // mg/dL values like "120" won't match (no decimal) — this is correct,
        // we only parse mmol values with decimals
        assertNull(tryParseGlucose("120 mg/dL"))
    }

    @Test
    fun `strips non-breaking space`() {
        assertEquals(8.3, tryParseGlucose("8,3\u00a0mmol/L")!!, 0.001)
    }

    @Test
    fun `strips word joiner`() {
        assertEquals(6.1, tryParseGlucose("\u20606,1")!!, 0.001)
    }

    @Test
    fun `strips unicode arrows`() {
        assertEquals(9.2, tryParseGlucose("↗ 9,2")!!, 0.001)
        assertEquals(5.5, tryParseGlucose("5,5 ↓")!!, 0.001)
        assertEquals(7.0, tryParseGlucose("→7,0")!!, 0.001)
    }

    @Test
    fun `strips inequality signs`() {
        assertEquals(3.9, tryParseGlucose("≤3,9")!!, 0.001)
        assertEquals(3.9, tryParseGlucose("≥3.9")!!, 0.001)
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
    fun `rejects integer without decimal`() {
        assertNull(tryParseGlucose("120"))
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
        // Real notification text patterns from CamAPS FX
        assertEquals(5.7, tryParseGlucose("5,7")!!, 0.001)
        assertEquals(13.5, tryParseGlucose("13,5")!!, 0.001)
        assertEquals(3.9, tryParseGlucose("3,9")!!, 0.001)
    }
}
