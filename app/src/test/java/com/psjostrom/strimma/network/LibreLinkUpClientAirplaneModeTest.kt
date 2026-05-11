package com.psjostrom.strimma.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.channels.UnresolvedAddressException

/**
 * Regression tests for the airplane-mode boot crash. See
 * `NightscoutClientAirplaneModeTest` for the full root-cause writeup —
 * `UnresolvedAddressException` extends `IllegalArgumentException` (NOT
 * `IOException`). LibreLinkUpFollower runs on the foreground-service Main
 * scope, so an escaped exception from any of these four methods would have
 * crashed the process the same way the Nightscout pull did.
 */
@RunWith(RobolectricTestRunner::class)
class LibreLinkUpClientAirplaneModeTest {

    private fun airplaneModeClient(): LibreLinkUpClient {
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler { throw UnresolvedAddressException() }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return LibreLinkUpClient(httpClient)
    }

    @Test
    fun `login returns null instead of crashing when DNS fails`() = runTest {
        val client = airplaneModeClient()

        val result = client.login(email = "user@example.com", password = "password")

        assertNull(result)
    }

    @Test
    fun `getConnections returns null instead of crashing when DNS fails`() = runTest {
        val client = airplaneModeClient()
        val session = LluSession(token = "tok", accountId = "acct", baseUrl = "https://api.libreview.io")

        val result = client.getConnections(session)

        assertNull(result)
    }

    @Test
    fun `getGraph returns null instead of crashing when DNS fails`() = runTest {
        val client = airplaneModeClient()
        val session = LluSession(token = "tok", accountId = "acct", baseUrl = "https://api.libreview.io")

        val result = client.getGraph(session, patientId = "patient-1")

        assertNull(result)
    }

    @Test
    fun `resolveRegionUrl returns null instead of crashing when DNS fails`() = runTest {
        val client = airplaneModeClient()

        val result = client.resolveRegionUrl(baseUrl = "https://api.libreview.io", region = "eu")

        assertNull(result)
    }
}
