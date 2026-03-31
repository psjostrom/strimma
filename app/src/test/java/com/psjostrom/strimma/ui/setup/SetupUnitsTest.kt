package com.psjostrom.strimma.ui.setup

import com.psjostrom.strimma.data.GlucoseUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupUnitsTest {

    @Test
    fun `US locale defaults to mgdl`() {
        val result = defaultUnitForLocale(java.util.Locale.US)
        assertEquals(GlucoseUnit.MGDL, result)
    }

    @Test
    fun `Japan locale defaults to mgdl`() {
        val result = defaultUnitForLocale(java.util.Locale.JAPAN)
        assertEquals(GlucoseUnit.MGDL, result)
    }

    @Test
    fun `Sweden locale defaults to mmol`() {
        val result = defaultUnitForLocale(java.util.Locale("sv", "SE"))
        assertEquals(GlucoseUnit.MMOL, result)
    }

    @Test
    fun `UK locale defaults to mmol`() {
        val result = defaultUnitForLocale(java.util.Locale.UK)
        assertEquals(GlucoseUnit.MMOL, result)
    }

    @Test
    fun `Germany locale defaults to mmol`() {
        val result = defaultUnitForLocale(java.util.Locale.GERMANY)
        assertEquals(GlucoseUnit.MMOL, result)
    }

    @Test
    fun `Australia locale defaults to mmol`() {
        val result = defaultUnitForLocale(java.util.Locale("en", "AU"))
        assertEquals(GlucoseUnit.MMOL, result)
    }

    @Test
    fun `Canada locale defaults to mmol`() {
        val result = defaultUnitForLocale(java.util.Locale.CANADA)
        assertEquals(GlucoseUnit.MMOL, result)
    }

    @Test
    fun `unknown country defaults to mgdl`() {
        val result = defaultUnitForLocale(java.util.Locale("xx", "ZZ"))
        assertEquals(GlucoseUnit.MGDL, result)
    }
}
