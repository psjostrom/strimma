package com.psjostrom.strimma.tidepool

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.channels.UnresolvedAddressException

/**
 * Regression tests for the airplane-mode boot crash. See
 * `NightscoutClientAirplaneModeTest` for the full root-cause writeup —
 * `UnresolvedAddressException` extends `IllegalArgumentException` (NOT
 * `IOException`), so the catches across all five HTTP-touching clients had
 * to be widened. These tests verify TidepoolClient holds the line.
 */
@RunWith(RobolectricTestRunner::class)
class TidepoolClientAirplaneModeTest {

    private fun airplaneModeClient(): TidepoolClient {
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler { throw UnresolvedAddressException() }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return TidepoolClient(httpClient)
    }

    @Test
    fun `getExistingDataset returns null instead of crashing when DNS fails`() = runTest {
        val client = airplaneModeClient()

        val result = client.getExistingDataset(
            baseUrl = "https://api.tidepool.org",
            userId = "user123",
            token = "token-abc"
        )

        assertNull(result)
    }

    @Test
    fun `createDataset returns null instead of crashing when DNS fails`() = runTest {
        val client = airplaneModeClient()

        val datasetRequest = DatasetRequest(
            client = DatasetRequest.ClientInfo(name = "com.psjostrom.strimma", version = "test"),
            time = "2026-05-11T08:00:00Z",
            computerTime = "2026-05-11T10:00:00",
            timezoneOffset = 120,
            timezone = "Europe/Stockholm",
            version = "test"
        )
        val result = client.createDataset(
            baseUrl = "https://api.tidepool.org",
            userId = "user123",
            token = "token-abc",
            datasetRequest = datasetRequest
        )

        assertNull(result)
    }

    @Test
    fun `uploadData returns false instead of crashing when DNS fails`() = runTest {
        val client = airplaneModeClient()

        val record = CbgRecord(
            value = 100,
            time = "2026-05-11T08:00:00Z",
            deviceTime = "2026-05-11T10:00:00",
            timezoneOffset = 120,
            origin = CbgRecord.Origin(id = "test-1"),
        )
        val result = client.uploadData(
            baseUrl = "https://api.tidepool.org",
            datasetId = "ds-abc",
            token = "token-abc",
            records = listOf(record)
        )

        assertFalse(result)
    }
}
