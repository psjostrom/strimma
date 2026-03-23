package com.psjostrom.strimma.tidepool

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TidepoolClientTest {

    private fun mockClient(handler: MockRequestHandler): TidepoolClient {
        val httpClient = HttpClient(MockEngine) {
            engine { addHandler(handler) }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return TidepoolClient(httpClient)
    }

    @Test
    fun `getExistingDataset returns uploadId when dataset exists`() = runTest {
        val client = mockClient { request ->
            assertEquals("https://api.tidepool.org/v1/users/user123/data_sets", request.url.toString().substringBefore('?'))
            assertTrue(request.url.toString().contains("client.name=com.psjostrom.strimma"))
            assertTrue(request.url.toString().contains("size=1"))
            assertEquals("token-abc", request.headers["x-tidepool-session-token"])
            assertEquals(HttpMethod.Get, request.method)

            respond(
                content = """[{"uploadId": "ds-abc"}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val uploadId = client.getExistingDataset(
            baseUrl = "https://api.tidepool.org",
            userId = "user123",
            token = "token-abc"
        )

        assertEquals("ds-abc", uploadId)
    }

    @Test
    fun `getExistingDataset returns null when no datasets`() = runTest {
        val client = mockClient { request ->
            respond(
                content = """[]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val uploadId = client.getExistingDataset(
            baseUrl = "https://api.tidepool.org",
            userId = "user123",
            token = "token-abc"
        )

        assertNull(uploadId)
    }

    @Test
    fun `getExistingDataset returns null on error`() = runTest {
        val client = mockClient { request ->
            respond(
                content = """{"error": "unauthorized"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val uploadId = client.getExistingDataset(
            baseUrl = "https://api.tidepool.org",
            userId = "user123",
            token = "token-abc"
        )

        assertNull(uploadId)
    }

    @Test
    fun `createDataset returns uploadId on success`() = runTest {
        val client = mockClient { request ->
            assertEquals("https://api.tidepool.org/v1/users/user123/data_sets", request.url.toString())
            assertEquals("token-abc", request.headers["x-tidepool-session-token"])
            assertEquals(HttpMethod.Post, request.method)

            respond(
                content = """{"uploadId": "ds-new"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val datasetRequest = DatasetRequest(
            client = DatasetRequest.ClientInfo(
                name = "com.psjostrom.strimma",
                version = "0.4.1"
            ),
            time = "2026-03-23T10:00:00.000Z",
            computerTime = "2026-03-23T11:00:00",
            timezoneOffset = 60,
            timezone = "Europe/Stockholm",
            version = "0.4.1"
        )

        val uploadId = client.createDataset(
            baseUrl = "https://api.tidepool.org",
            userId = "user123",
            token = "token-abc",
            datasetRequest = datasetRequest
        )

        assertEquals("ds-new", uploadId)
    }

    @Test
    fun `createDataset returns null on error`() = runTest {
        val client = mockClient { request ->
            respond(
                content = """{"error": "bad request"}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val datasetRequest = DatasetRequest(
            client = DatasetRequest.ClientInfo(
                name = "com.psjostrom.strimma",
                version = "0.4.1"
            ),
            time = "2026-03-23T10:00:00.000Z",
            computerTime = "2026-03-23T11:00:00",
            timezoneOffset = 60,
            timezone = "Europe/Stockholm",
            version = "0.4.1"
        )

        val uploadId = client.createDataset(
            baseUrl = "https://api.tidepool.org",
            userId = "user123",
            token = "token-abc",
            datasetRequest = datasetRequest
        )

        assertNull(uploadId)
    }

    @Test
    fun `uploadData returns true on success`() = runTest {
        val client = mockClient { request ->
            assertEquals("https://api.tidepool.org/v1/datasets/ds-abc/data", request.url.toString())
            assertEquals("token-abc", request.headers["x-tidepool-session-token"])
            assertEquals(HttpMethod.Post, request.method)

            respond(
                content = """[]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val records = listOf(
            CbgRecord(
                value = 120,
                time = "2026-03-23T10:00:00.000Z",
                deviceTime = "2026-03-23T11:00:00",
                timezoneOffset = 60,
                origin = CbgRecord.Origin(id = "strimma-cbg-1234567890")
            )
        )

        val success = client.uploadData(
            baseUrl = "https://api.tidepool.org",
            datasetId = "ds-abc",
            token = "token-abc",
            records = records
        )

        assertTrue(success)
    }

    @Test
    fun `uploadData returns false on 400 error`() = runTest {
        val client = mockClient { request ->
            respond(
                content = """{"error": "invalid data"}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val records = listOf(
            CbgRecord(
                value = 120,
                time = "2026-03-23T10:00:00.000Z",
                deviceTime = "2026-03-23T11:00:00",
                timezoneOffset = 60,
                origin = CbgRecord.Origin(id = "strimma-cbg-1234567890")
            )
        )

        val success = client.uploadData(
            baseUrl = "https://api.tidepool.org",
            datasetId = "ds-abc",
            token = "token-abc",
            records = records
        )

        assertFalse(success)
    }

    @Test
    fun `uploadData returns false on network error`() = runTest {
        val client = mockClient { request ->
            throw Exception("Network timeout")
        }

        val records = listOf(
            CbgRecord(
                value = 120,
                time = "2026-03-23T10:00:00.000Z",
                deviceTime = "2026-03-23T11:00:00",
                timezoneOffset = 60,
                origin = CbgRecord.Origin(id = "strimma-cbg-1234567890")
            )
        )

        val success = client.uploadData(
            baseUrl = "https://api.tidepool.org",
            datasetId = "ds-abc",
            token = "token-abc",
            records = records
        )

        assertFalse(success)
    }
}
