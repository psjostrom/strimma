package com.psjostrom.strimma.network

import org.junit.Assert.*
import org.junit.Test

class NightscoutFollowerTest {

    @Test
    fun `filterValidEntries skips non-sgv entries`() {
        val entries = listOf(
            NightscoutEntryResponse(sgv = 120, date = 1000L, type = "sgv"),
            NightscoutEntryResponse(sgv = null, date = 2000L, type = "mbg"),
            NightscoutEntryResponse(sgv = 130, date = 3000L, type = "cal"),
            NightscoutEntryResponse(sgv = 140, date = 4000L, type = "sgv")
        )
        val valid = NightscoutFollower.filterValidEntries(entries)
        assertEquals(2, valid.size)
        assertEquals(120, valid[0].sgv)
        assertEquals(140, valid[1].sgv)
    }

    @Test
    fun `filterValidEntries skips entries with null sgv`() {
        val entries = listOf(
            NightscoutEntryResponse(sgv = null, date = 1000L, type = "sgv"),
            NightscoutEntryResponse(sgv = 120, date = 2000L, type = "sgv")
        )
        val valid = NightscoutFollower.filterValidEntries(entries)
        assertEquals(1, valid.size)
        assertEquals(120, valid[0].sgv)
    }

    @Test
    fun `filterValidEntries skips entries with null date`() {
        val entries = listOf(
            NightscoutEntryResponse(sgv = 120, date = null, type = "sgv"),
            NightscoutEntryResponse(sgv = 130, date = 2000L, type = "sgv")
        )
        val valid = NightscoutFollower.filterValidEntries(entries)
        assertEquals(1, valid.size)
        assertEquals(130, valid[0].sgv)
    }

    @Test
    fun `filterValidEntries skips out-of-range sgv values`() {
        val entries = listOf(
            NightscoutEntryResponse(sgv = 10, date = 1000L, type = "sgv"),
            NightscoutEntryResponse(sgv = 120, date = 2000L, type = "sgv"),
            NightscoutEntryResponse(sgv = 1000, date = 3000L, type = "sgv")
        )
        val valid = NightscoutFollower.filterValidEntries(entries)
        assertEquals(1, valid.size)
        assertEquals(120, valid[0].sgv)
    }

    @Test
    fun `filterValidEntries returns sorted by date ascending`() {
        val entries = listOf(
            NightscoutEntryResponse(sgv = 130, date = 3000L, type = "sgv"),
            NightscoutEntryResponse(sgv = 120, date = 1000L, type = "sgv"),
            NightscoutEntryResponse(sgv = 125, date = 2000L, type = "sgv")
        )
        val valid = NightscoutFollower.filterValidEntries(entries)
        assertEquals(3, valid.size)
        assertEquals(1000L, valid[0].date)
        assertEquals(2000L, valid[1].date)
        assertEquals(3000L, valid[2].date)
    }

    @Test
    fun `filterValidEntries handles empty list`() {
        val valid = NightscoutFollower.filterValidEntries(emptyList())
        assertTrue(valid.isEmpty())
    }
}
