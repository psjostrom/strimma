package com.psjostrom.strimma.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.MS_PER_DAY
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * A year of a looping user's treatments is far more than one Nightscout response should
 * carry. These tests run TreatmentSyncer against a MockEngine that answers
 * `/api/v1/treatments.json` the way cgm-remote-monitor does: it applies the
 * `find[created_at]` bounds, sorts newest first and returns at most `count` records.
 */
@RunWith(RobolectricTestRunner::class)
class TreatmentPagingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now = System.currentTimeMillis()

    private class ServerTreatment(val id: String, val createdAtMs: Long)

    private class FakeNightscout(val treatments: List<ServerTreatment>, val failingRequest: Int? = null) {
        val requestedCounts = mutableListOf<Int>()

        val client = NightscoutClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val params = request.url.parameters
                        val gte = params["find[created_at][\$gte]"]?.let { Instant.parse(it).toEpochMilli() }
                        val lte = params["find[created_at][\$lte]"]?.let { Instant.parse(it).toEpochMilli() }
                        val count = params["count"]!!.toInt()
                        requestedCounts += count
                        if (requestedCounts.size == failingRequest) {
                            return@addHandler respondError(HttpStatusCode.InternalServerError)
                        }
                        val body = treatments
                            .filter { gte == null || it.createdAtMs >= gte }
                            .filter { lte == null || it.createdAtMs <= lte }
                            .sortedByDescending { it.createdAtMs }
                            .take(count)
                            .joinToString(",", "[", "]") {
                                """{"_id":"${it.id}","eventType":"Temp Basal","absolute":0.8,"duration":30,""" +
                                    """"created_at":"${Instant.ofEpochMilli(it.createdAtMs)}"}"""
                            }
                        respond(body, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                    }
                }
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        )
    }

    private suspend fun pullYear(server: FakeNightscout, check: suspend (Result<Int>, Int) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
            settings.setNightscoutUrl("https://ns.example.com")
            settings.setNightscoutSecret("secret")
            val result = TreatmentSyncer(server.client, db.treatmentDao(), settings).pullHistory(365)
            check(result, db.treatmentDao().allSince(0).size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `a one-year pull stores every treatment without one oversized request`() = runTest {
        // One temp basal every 5 minutes for 300 days: 86,400 treatments.
        val server = FakeNightscout(
            (0 until 86_400).map { i -> ServerTreatment("t$i", now - 300 * MS_PER_DAY + i * 300_000L) }
        )

        pullYear(server) { result, stored ->
            assertEquals(86_400, result.getOrThrow())
            assertEquals(86_400, stored)
        }
        assertTrue("every request stays small", server.requestedCounts.all { it <= 500 })
    }

    @Test
    fun `treatments that share a created_at across a page edge are all stored`() = runTest {
        val shared = now - MS_PER_DAY
        // 498 newer treatments, then 5 that share one created_at, so the first page of 500
        // ends in the middle of the shared group.
        val newer = (0 until 498).map { i -> ServerTreatment("n$i", shared + (i + 1) * 60_000L) }
        val group = (0 until 5).map { i -> ServerTreatment("s$i", shared) }
        val older = (0 until 10).map { i -> ServerTreatment("o$i", shared - (i + 1) * 60_000L) }
        val server = FakeNightscout(newer + group + older)

        pullYear(server) { result, stored ->
            assertEquals(513, result.getOrThrow())
            assertEquals(513, stored)
        }
    }

    @Test
    fun `a pull that fails on a later page stores nothing`() = runTest {
        // The next start picks a full sync only while nothing has been fetched, so a pull
        // that stored its first pages and then failed would leave the older ones missing.
        val server = FakeNightscout(
            (0 until 2_000).map { i -> ServerTreatment("t$i", now - i * 300_000L) },
            failingRequest = 3,
        )

        pullYear(server) { result, stored ->
            assertTrue(result.isFailure)
            assertEquals(0, stored)
        }
    }
}
