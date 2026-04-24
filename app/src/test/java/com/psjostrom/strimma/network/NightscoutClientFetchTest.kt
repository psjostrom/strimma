package com.psjostrom.strimma.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class NightscoutClientFetchTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun mockClient(handler: MockRequestHandler): NightscoutClient {
        val httpClient = HttpClient(MockEngine) {
            engine { addHandler(handler) }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return NightscoutClient(httpClient)
    }

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
    fun `normalizeUrl returns empty for blank input`() {
        assertEquals("", NightscoutClient.normalizeUrl(""))
        assertEquals("", NightscoutClient.normalizeUrl("   "))
    }

    @Test
    fun `normalizeUrl prepends https when no scheme`() {
        assertEquals("https://ns.example.com", NightscoutClient.normalizeUrl("ns.example.com"))
    }

    @Test
    fun `normalizeUrl preserves existing http scheme`() {
        assertEquals("http://ns.example.com", NightscoutClient.normalizeUrl("http://ns.example.com"))
    }

    @Test
    fun `normalizeUrl preserves existing https scheme`() {
        assertEquals("https://ns.example.com", NightscoutClient.normalizeUrl("https://ns.example.com"))
    }

    @Test
    fun `normalizeUrl strips trailing slashes`() {
        assertEquals("https://ns.example.com", NightscoutClient.normalizeUrl("https://ns.example.com/"))
        assertEquals("https://ns.example.com", NightscoutClient.normalizeUrl("https://ns.example.com///"))
    }

    @Test
    fun `normalizeUrl trims whitespace`() {
        assertEquals("https://ns.example.com", NightscoutClient.normalizeUrl("  https://ns.example.com  "))
    }

    @Test
    fun `normalizeUrl handles combined edge cases`() {
        assertEquals("https://ns.example.com", NightscoutClient.normalizeUrl("  ns.example.com/  "))
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

    @Test
    fun `fetchTreatments throws on HTTP error instead of returning empty success`() = runTest {
        val client = mockClient {
            respond(
                content = "server error",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        try {
            client.fetchTreatments(
                baseUrl = "https://ns.example.com",
                secret = "secret",
                since = 1710700000000L,
                count = 100
            )
            fail("Expected fetchTreatments to throw on HTTP 500")
        } catch (e: IOException) {
            assertEquals("HTTP 500", e.message)
        }
    }

    @Test
    fun `fetchTreatments returns empty list on 404 unsupported treatments`() = runTest {
        val client = mockClient {
            respond(
                content = "not found",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val treatments = client.fetchTreatments(
            baseUrl = "https://ns.example.com",
            secret = "secret",
            since = 1710700000000L,
            count = 100
        )

        assertTrue(treatments.isEmpty())
    }
}
