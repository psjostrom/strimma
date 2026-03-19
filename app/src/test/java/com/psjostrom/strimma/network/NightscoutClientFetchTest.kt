package com.psjostrom.strimma.network

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NightscoutClientFetchTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parse valid sgv entry`() {
        val raw = """[{"sgv": 120, "date": 1710700000000, "type": "sgv"}]"""
        val entries = json.decodeFromString<List<NightscoutEntryResponse>>(raw)
        assertEquals(1, entries.size)
        assertEquals(120, entries[0].sgv)
        assertEquals(1710700000000L, entries[0].date)
        assertEquals("sgv", entries[0].type)
    }

    @Test
    fun `parse entry with unknown fields`() {
        val raw = """[{"sgv": 100, "date": 1710700000000, "type": "sgv", "direction": "Flat", "noise": 1, "rssi": -50}]"""
        val entries = json.decodeFromString<List<NightscoutEntryResponse>>(raw)
        assertEquals(1, entries.size)
        assertEquals(100, entries[0].sgv)
    }

    @Test
    fun `parse entry with null sgv`() {
        val raw = """[{"date": 1710700000000, "type": "cal"}]"""
        val entries = json.decodeFromString<List<NightscoutEntryResponse>>(raw)
        assertEquals(1, entries.size)
        assertNull(entries[0].sgv)
    }

    @Test
    fun `parse empty array`() {
        val raw = """[]"""
        val entries = json.decodeFromString<List<NightscoutEntryResponse>>(raw)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `parse mixed entry types`() {
        val raw = """[
            {"sgv": 120, "date": 1710700000000, "type": "sgv"},
            {"date": 1710700100000, "type": "mbg"},
            {"sgv": 130, "date": 1710700200000, "type": "sgv"}
        ]"""
        val entries = json.decodeFromString<List<NightscoutEntryResponse>>(raw)
        assertEquals(3, entries.size)
        assertEquals("sgv", entries[0].type)
        assertEquals("mbg", entries[1].type)
        assertEquals("sgv", entries[2].type)
    }

    @Test
    fun `buildFetchUrl constructs correct query string`() {
        val url = NightscoutClient.buildFetchUrl("https://ns.example.com", since = 1710700000000L, count = 100)
        assertEquals("https://ns.example.com/api/v1/entries.json?find[date][\$gt]=1710700000000&count=100", url)
    }

    @Test
    fun `buildFetchUrl trims trailing slash`() {
        val url = NightscoutClient.buildFetchUrl("https://ns.example.com/", since = 1710700000000L, count = 50)
        assertEquals("https://ns.example.com/api/v1/entries.json?find[date][\$gt]=1710700000000&count=50", url)
    }
}
