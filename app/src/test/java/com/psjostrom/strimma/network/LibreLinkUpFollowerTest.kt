package com.psjostrom.strimma.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibreLinkUpFollowerTest {

    @Test
    fun `parses FactoryTimestamp 12h format`() {
        val ts = LibreLinkUpFollower.parseLluTimestamp("3/25/2026 2:30:45 PM")
        assertNotNull(ts)
    }

    @Test
    fun `parses FactoryTimestamp 24h format`() {
        val ts = LibreLinkUpFollower.parseLluTimestamp("3/25/2026 14:30:45")
        assertNotNull(ts)
    }

    @Test
    fun `12h and 24h formats produce same epoch`() {
        val h12 = LibreLinkUpFollower.parseLluTimestamp("3/25/2026 2:30:45 PM")
        val h24 = LibreLinkUpFollower.parseLluTimestamp("3/25/2026 14:30:45")
        assertNotNull(h12)
        assertNotNull(h24)
        assertEquals(h12, h24)
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(LibreLinkUpFollower.parseLluTimestamp(""))
        assertNull(LibreLinkUpFollower.parseLluTimestamp("   "))
    }

    @Test
    fun `returns null for garbage input`() {
        assertNull(LibreLinkUpFollower.parseLluTimestamp("not-a-date"))
        assertNull(LibreLinkUpFollower.parseLluTimestamp("2026-03-25T14:30:45Z"))
    }

    @Test
    fun `returns null for EU day-first format`() {
        // FactoryTimestamp uses M/d/yyyy (month-first), not d/M/yyyy
        // Day 25 > 12 so the M/d parser rejects it correctly
        assertNull(LibreLinkUpFollower.parseLluTimestamp("25/3/2026 14:30:45"))
    }

    @Test
    fun `midnight parses correctly`() {
        val ts = LibreLinkUpFollower.parseLluTimestamp("1/1/2026 0:00:00")
        assertNotNull(ts)
        // 2026-01-01T00:00:00Z in epoch millis
        assertEquals(1767225600000L, ts)
    }

    @Test
    fun `single digit day and month parse correctly`() {
        val ts = LibreLinkUpFollower.parseLluTimestamp("3/5/2026 9:05:30")
        assertNotNull(ts)
        // M/d/yyyy: March 5, 2026 09:05:30 UTC
        val expected = LibreLinkUpFollower.parseLluTimestamp("3/5/2026 9:05:30 AM")
        assertEquals(expected, ts)
    }
}
