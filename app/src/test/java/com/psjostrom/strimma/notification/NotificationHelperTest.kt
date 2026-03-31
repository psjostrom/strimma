package com.psjostrom.strimma.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationHelperTest {

    @Test
    fun `formatDeltaLine includes all parts separated by dot`() {
        val result = NotificationHelper.formatDeltaLine("+0.1", null, "IOB 0.5", "3m")
        assertEquals("+0.1 · IOB 0.5 · 3m", result)
    }

    @Test
    fun `formatDeltaLine with only since`() {
        val result = NotificationHelper.formatDeltaLine("", null, null, "0m")
        assertEquals("0m", result)
    }

    @Test
    fun `formatDeltaLine with zero minutes since`() {
        val result = NotificationHelper.formatDeltaLine("+0.2", null, null, "0m")
        assertEquals("+0.2 · 0m", result)
    }

    @Test
    fun `formatDeltaLine with all parts`() {
        val result = NotificationHelper.formatDeltaLine("+0.3", "Low 8m", "IOB 1.2", "5m")
        assertEquals("+0.3 · Low 8m · IOB 1.2 · 5m", result)
    }

    @Test
    fun `formatDeltaLine with no parts returns empty`() {
        val result = NotificationHelper.formatDeltaLine("", null, null, null)
        assertEquals("", result)
    }

    @Test
    fun `formatDeltaLine with delta and iob but no since`() {
        val result = NotificationHelper.formatDeltaLine("-0.1", null, "IOB 0.3", null)
        assertEquals("-0.1 · IOB 0.3", result)
    }
}
