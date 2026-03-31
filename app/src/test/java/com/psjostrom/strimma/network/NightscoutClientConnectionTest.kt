package com.psjostrom.strimma.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.*
import org.junit.Test

class NightscoutClientConnectionTest {

    @Test
    fun `parse status response with customTitle`() {
        val body = """{"status":"ok","name":"nightscout","version":"15.0.0","settings":{"customTitle":"My Nightscout"}}"""
        val name = Json.parseToJsonElement(body)
            .jsonObject["settings"]
            ?.jsonObject?.get("customTitle")
            ?.jsonPrimitive?.contentOrNull
        assertEquals("My Nightscout", name)
    }

    @Test
    fun `parse status response without customTitle`() {
        val body = """{"status":"ok","name":"nightscout","version":"15.0.0","settings":{}}"""
        val name = Json.parseToJsonElement(body)
            .jsonObject["settings"]
            ?.jsonObject?.get("customTitle")
            ?.jsonPrimitive?.contentOrNull
        assertNull(name)
    }

    @Test
    fun `parse status response without settings`() {
        val body = """{"status":"ok","name":"nightscout","version":"15.0.0"}"""
        val name = Json.parseToJsonElement(body)
            .jsonObject["settings"]
            ?.jsonObject?.get("customTitle")
            ?.jsonPrimitive?.contentOrNull
        assertNull(name)
    }

    @Test
    fun `parse minimal status response`() {
        val body = """{"status":"ok"}"""
        val name = Json.parseToJsonElement(body)
            .jsonObject["settings"]
            ?.jsonObject?.get("customTitle")
            ?.jsonPrimitive?.contentOrNull
        assertNull(name)
    }

    @Test
    fun `ConnectionTestResult success with server name`() {
        val result = NightscoutClient.ConnectionTestResult(true, serverName = "My NS")
        assertTrue(result.success)
        assertEquals("My NS", result.serverName)
        assertNull(result.error)
    }

    @Test
    fun `ConnectionTestResult failure with error`() {
        val result = NightscoutClient.ConnectionTestResult(false, error = "HTTP 401")
        assertFalse(result.success)
        assertNull(result.serverName)
        assertEquals("HTTP 401", result.error)
    }

    @Test
    fun `ConnectionTestResult success without server name`() {
        val result = NightscoutClient.ConnectionTestResult(true)
        assertTrue(result.success)
        assertNull(result.serverName)
    }
}
