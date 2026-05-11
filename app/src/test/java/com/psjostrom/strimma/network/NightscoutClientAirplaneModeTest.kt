package com.psjostrom.strimma.network

import com.psjostrom.strimma.data.GlucoseReading
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.channels.UnresolvedAddressException

/**
 * Regression tests for the airplane-mode boot crash. When the device has no
 * network, Ktor's CIO engine surfaces DNS lookup failures as
 * `UnresolvedAddressException`, which extends `IllegalArgumentException` —
 * NOT `IOException`. A previous version of these clients only caught
 * `IOException`, so the exception escaped the coroutine launched by
 * `SyncOrchestrator.start()` on `Dispatchers.Main` and killed the foreground
 * service on every boot, leading to an infinite restart-and-crash loop.
 *
 * Each test simulates the airplane-mode condition by having `MockEngine`
 * throw `UnresolvedAddressException` from the request handler, then asserts
 * the client surfaces the failure as a return value rather than propagating.
 */
@RunWith(RobolectricTestRunner::class)
class NightscoutClientAirplaneModeTest {

    private fun airplaneModeClient(): NightscoutClient {
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler { throw UnresolvedAddressException() }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return NightscoutClient(httpClient)
    }

    @Test
    fun `pushReadings returns false instead of crashing when DNS fails`() = runTest {
        val client = airplaneModeClient()
        val reading = GlucoseReading(ts = 1L, sgv = 120, direction = "Flat", delta = 0.0)

        val result = client.pushReadings("https://example.com", "secret", listOf(reading))

        assertFalse(result)
    }

    @Test
    fun `fetchEntries returns null instead of crashing when DNS fails`() = runTest {
        val client = airplaneModeClient()

        val result = client.fetchEntries("https://example.com", "secret", since = 0L)

        assertNull(result)
    }

    @Test
    fun `testConnection returns failure instead of crashing when DNS fails`() = runTest {
        val client = airplaneModeClient()

        val result = client.testConnection("https://example.com", "secret")

        assertFalse(result.success)
    }

    // fetchTreatments is the only HTTP method that intentionally rethrows after logging
    // (vs. swallowing and returning a failure value). The fix here is the same — the
    // catch was widened from `IOException` to `Exception` so UnresolvedAddressException
    // is logged and rethrown rather than escaping uncaught. The caller (TreatmentSyncer)
    // owns the outer catch that prevents the crash.
    @Test
    fun `fetchTreatments rethrows network failure after logging`() = runTest {
        val client = airplaneModeClient()

        assertThrows(UnresolvedAddressException::class.java) {
            kotlinx.coroutines.runBlocking {
                client.fetchTreatments("https://example.com", "secret", since = 0L)
            }
        }
    }
}
