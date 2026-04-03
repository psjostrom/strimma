package com.psjostrom.strimma.tidepool

import org.junit.Assert.*
import org.junit.Test

class TidepoolDateUtilTest {

    @Test
    fun `toUtcIso8601 produces standard ISO8601 ending with Z`() {
        val timestamp = 1711234567890L // 2024-03-23T...
        val result = TidepoolDateUtil.toUtcIso8601(timestamp)

        assertTrue("Should end with milliseconds + Z (e.g. .890Z)", result.matches(Regex(".*\\.\\d{3}Z$")))
    }

    @Test
    fun `toUtcIso8601 contains T separator`() {
        val timestamp = 1711234567890L
        val result = TidepoolDateUtil.toUtcIso8601(timestamp)

        assertTrue("Should contain T separator", result.contains("T"))
    }

    @Test
    fun `toUtcIso8601 starts with 20 for dates in 2000s`() {
        val timestamp = 1711234567890L // 2024
        val result = TidepoolDateUtil.toUtcIso8601(timestamp)

        assertTrue("Should start with 20", result.startsWith("20"))
    }

    @Test
    fun `toLocalNoZone produces string without Z`() {
        val timestamp = 1711234567890L
        val result = TidepoolDateUtil.toLocalNoZone(timestamp)

        assertFalse("Should not contain Z", result.contains("Z"))
    }

    @Test
    fun `toLocalNoZone contains T separator`() {
        val timestamp = 1711234567890L
        val result = TidepoolDateUtil.toLocalNoZone(timestamp)

        assertTrue("Should contain T separator", result.contains("T"))
    }

    @Test
    fun `getTimezoneOffsetMinutes returns value in valid range`() {
        val timestamp = 1711234567890L
        val offsetMinutes = TidepoolDateUtil.getTimezoneOffsetMinutes(timestamp)

        assertTrue(
            "Offset should be between -720 and 840 minutes",
            offsetMinutes in -720..840
        )
    }

    @Test
    fun `getTimezoneOffsetMinutes is deterministic for same timestamp`() {
        val timestamp = 1711234567890L
        val offset1 = TidepoolDateUtil.getTimezoneOffsetMinutes(timestamp)
        val offset2 = TidepoolDateUtil.getTimezoneOffsetMinutes(timestamp)

        assertEquals("Same timestamp should produce same offset", offset1, offset2)
    }
}
