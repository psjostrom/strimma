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
    fun `parses US 12h format`() {
        val ts = LibreLinkUpFollower.parseLluTimestamp("3/25/2026 2:30:45 PM")
        assertNotNull(ts)
    }

    @Test
    fun `parses US 24h format`() {
        val ts = LibreLinkUpFollower.parseLluTimestamp("3/25/2026 14:30:45")
        assertNotNull(ts)
    }

    @Test
    fun `parses EU 24h format`() {
        val ts = LibreLinkUpFollower.parseLluTimestamp("25/3/2026 14:30:45")
        assertNotNull(ts)
    }

    @Test
    fun `parses EU 12h format`() {
        val ts = LibreLinkUpFollower.parseLluTimestamp("25/3/2026 2:30:45 PM")
        assertNotNull(ts)
    }

    @Test
    fun `US and EU 24h formats produce same epoch for unambiguous dates`() {
        val us = LibreLinkUpFollower.parseLluTimestamp("1/25/2026 14:30:45")
        val eu = LibreLinkUpFollower.parseLluTimestamp("25/1/2026 14:30:45")
        assertNotNull(us)
        assertNotNull(eu)
        assertEquals(us, eu)
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
    }
}
