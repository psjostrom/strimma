package com.psjostrom.strimma.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HbA1cUnitTest {

    @Test
    fun `format percent shows one decimal with percent sign`() {
        assertEquals("7.0%", HbA1cUnit.PERCENT.format(7.0))
        assertEquals("6.5%", HbA1cUnit.PERCENT.format(6.5))
    }

    @Test
    fun `format mmol_mol converts and shows integer`() {
        // 7.0% DCCT = (7.0 - 2.15) * 10.929 = 53.0 mmol/mol
        assertEquals("53 mmol/mol", HbA1cUnit.MMOL_MOL.format(7.0))
    }

    @Test
    fun `toIfcc converts known values`() {
        // Standard conversion table:
        // 6.0% = 42 mmol/mol
        // 7.0% = 53 mmol/mol
        // 8.0% = 64 mmol/mol
        // 9.0% = 75 mmol/mol
        assertEquals(42.0, HbA1cUnit.toIfcc(6.0), 1.0)
        assertEquals(53.0, HbA1cUnit.toIfcc(7.0), 1.0)
        assertEquals(64.0, HbA1cUnit.toIfcc(8.0), 1.0)
        assertEquals(75.0, HbA1cUnit.toIfcc(9.0), 1.0)
    }

    @Test
    fun `label returns correct strings`() {
        assertEquals("%", HbA1cUnit.PERCENT.label)
        assertEquals("mmol/mol", HbA1cUnit.MMOL_MOL.label)
    }
}
