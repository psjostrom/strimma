package com.psjostrom.strimma.tidepool

import com.psjostrom.strimma.data.GlucoseReading
import org.junit.Assert.*
import org.junit.Test

class TidepoolModelsTest {

    @Test
    fun `CbgRecord fromReading sets type to cbg`() {
        val reading = GlucoseReading(
            ts = 1711234567890L,
            sgv = 120,
            direction = "Flat",
            delta = 0.5,
            pushed = 1
        )

        val cbg = CbgRecord.fromReading(reading)

        assertEquals("cbg", cbg.type)
    }

    @Test
    fun `CbgRecord fromReading sets units to mg per dL`() {
        val reading = GlucoseReading(
            ts = 1711234567890L,
            sgv = 120,
            direction = "Flat",
            delta = 0.5,
            pushed = 1
        )

        val cbg = CbgRecord.fromReading(reading)

        assertEquals("mg/dL", cbg.units)
    }

    @Test
    fun `CbgRecord fromReading sets correct value`() {
        val reading = GlucoseReading(
            ts = 1711234567890L,
            sgv = 120,
            direction = "Flat",
            delta = 0.5,
            pushed = 1
        )

        val cbg = CbgRecord.fromReading(reading)

        assertEquals(120, cbg.value)
    }

    @Test
    fun `CbgRecord origin id is deterministic`() {
        val reading = GlucoseReading(
            ts = 1711234567890L,
            sgv = 120,
            direction = "Flat",
            delta = 0.5,
            pushed = 1
        )

        val cbg = CbgRecord.fromReading(reading)

        assertEquals("strimma-cbg-1711234567890", cbg.origin.id)
    }

    @Test
    fun `isValidForUpload rejects sgv below 39`() {
        val reading = GlucoseReading(
            ts = 1711234567890L,
            sgv = 38,
            direction = "Flat",
            delta = 0.0,
            pushed = 1
        )

        assertFalse(
            "Should reject glucose below 39 mg/dL",
            CbgRecord.isValidForUpload(reading)
        )
    }

    @Test
    fun `isValidForUpload accepts sgv at 39`() {
        val reading = GlucoseReading(
            ts = 1711234567890L,
            sgv = 39,
            direction = "Flat",
            delta = 0.0,
            pushed = 1
        )

        assertTrue(
            "Should accept glucose at 39 mg/dL",
            CbgRecord.isValidForUpload(reading)
        )
    }

    @Test
    fun `isValidForUpload accepts sgv at 500`() {
        val reading = GlucoseReading(
            ts = 1711234567890L,
            sgv = 500,
            direction = "Flat",
            delta = 0.0,
            pushed = 1
        )

        assertTrue(
            "Should accept glucose at 500 mg/dL",
            CbgRecord.isValidForUpload(reading)
        )
    }

    @Test
    fun `isValidForUpload rejects sgv above 500`() {
        val reading = GlucoseReading(
            ts = 1711234567890L,
            sgv = 501,
            direction = "Flat",
            delta = 0.0,
            pushed = 1
        )

        assertFalse(
            "Should reject glucose above 500 mg/dL",
            CbgRecord.isValidForUpload(reading)
        )
    }

    @Test
    fun `isValidForUpload rejects future timestamps`() {
        val futureTimestamp = System.currentTimeMillis() + 60_000L // 1 minute in future
        val reading = GlucoseReading(
            ts = futureTimestamp,
            sgv = 120,
            direction = "Flat",
            delta = 0.0,
            pushed = 1
        )

        assertFalse(
            "Should reject future timestamps",
            CbgRecord.isValidForUpload(reading)
        )
    }

    @Test
    fun `isValidForUpload rejects timestamps before 2020`() {
        val timestamp2019 = 1577836799000L // 2019-12-31 23:59:59 UTC
        val reading = GlucoseReading(
            ts = timestamp2019,
            sgv = 120,
            direction = "Flat",
            delta = 0.0,
            pushed = 1
        )

        assertFalse(
            "Should reject timestamps before 2020-01-01",
            CbgRecord.isValidForUpload(reading)
        )
    }

    @Test
    fun `isValidForUpload accepts timestamp at 2020-01-01`() {
        val timestamp2020 = 1577836800000L // 2020-01-01 00:00:00 UTC
        val reading = GlucoseReading(
            ts = timestamp2020,
            sgv = 120,
            direction = "Flat",
            delta = 0.0,
            pushed = 1
        )

        assertTrue(
            "Should accept timestamp at 2020-01-01",
            CbgRecord.isValidForUpload(reading)
        )
    }

    @Test
    fun `isValidForUpload accepts valid reading`() {
        val reading = GlucoseReading(
            ts = 1711234567890L, // 2024-03-23
            sgv = 120,
            direction = "Flat",
            delta = 0.5,
            pushed = 1
        )

        assertTrue(
            "Should accept valid reading",
            CbgRecord.isValidForUpload(reading)
        )
    }
}
