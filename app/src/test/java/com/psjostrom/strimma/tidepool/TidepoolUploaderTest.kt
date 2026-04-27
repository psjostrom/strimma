package com.psjostrom.strimma.tidepool

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TidepoolUploaderTest {
    private fun mockClient(handler: MockRequestHandler): TidepoolClient {
        val httpClient = HttpClient(MockEngine) {
            engine { addHandler(handler) }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                })
            }
        }
        return TidepoolClient(httpClient)
    }

    private fun seedLoggedIn(authManager: TidepoolAuthManager, settings: SettingsRepository, token: String) {
        settings.setTidepoolRefreshToken("refresh-token")
        authManager.seedTokensForTest(token, expiryMs = System.currentTimeMillis() + 60_000L)
    }

    @Test
    fun `computeChunkEnd caps at 7 days from start`() {
        val start = 1774375800000L
        val now = start + 14 * 86_400_000L
        val end = TidepoolUploader.computeChunkEnd(start, now)
        assertEquals(start + 7 * 86_400_000L, end)
    }

    @Test
    fun `computeChunkEnd uses now minus buffer when within 7 days`() {
        val start = 1774375800000L
        val now = start + 3600_000L
        val end = TidepoolUploader.computeChunkEnd(start, now)
        assertEquals(now - TidepoolUploader.UPLOAD_BUFFER_MS, end)
    }

    @Test
    fun `computeChunkEnd returns value at or below start when now is too close`() {
        val start = 1774375800000L
        val now = start + 60_000L
        val end = TidepoolUploader.computeChunkEnd(start, now)
        assertTrue(end <= start)
    }

    @Test
    fun `clampLastUploadEnd clamps zero to 2 months ago`() {
        val now = System.currentTimeMillis()
        val result = TidepoolUploader.clampLastUploadEnd(0L, now)
        val twoMonthsAgo = now - 60L * 86_400_000L
        assertTrue(result >= twoMonthsAgo - 1000)
    }

    @Test
    fun `stop then uploadPending does not throw (scope is recreated)`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        val uploader = TidepoolUploader(context, TidepoolClient(), TidepoolAuthManager(context, settings), db.readingDao(), settings)

        uploader.stop()
        // Should not throw — scope is recreated after stop()
        uploader.uploadPending()
        uploader.onNewReading()

        uploader.stop()
        db.close()
    }

    @Test
    fun `uploadIfReady does not rate limit when userId is blank`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        val authManager = TidepoolAuthManager(context, settings)
        seedLoggedIn(authManager, settings, token = "token-abc")

        settings.setTidepoolEnabled(true)
        settings.setTidepoolDatasetId("dataset-1")

        val now = System.currentTimeMillis()
        settings.setTidepoolLastUploadEnd(now - 30 * 60_000L)
        db.readingDao().insert(
            GlucoseReading(
                ts = now - 20 * 60_000L,
                sgv = 120,
                direction = "Flat",
                delta = 0.0,
                pushed = 1
            )
        )

        var uploadCalls = 0
        val uploader = TidepoolUploader(
            context = context,
            client = mockClient { request ->
                when (request.url.encodedPath) {
                    "/v1/datasets/dataset-1/data" -> {
                        uploadCalls += 1
                        respond(
                            content = "ok",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        )
                    }

                    else -> respond(
                        content = "not found",
                        status = HttpStatusCode.NotFound,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            },
            authManager = authManager,
            dao = db.readingDao(),
            settings = settings
        )

        uploader.uploadIfReady()

        assertEquals(0, uploadCalls)
        assertEquals(0L, settings.tidepoolLastUploadTime.first())

        settings.setTidepoolUserId("user123")
        uploader.uploadIfReady()

        assertEquals(1, uploadCalls)
        assertNotEquals(0L, settings.tidepoolLastUploadTime.first())

        db.close()
    }

    @Test
    fun `uploadIfReady does not rate limit when there are no eligible readings`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        val authManager = TidepoolAuthManager(context, settings)
        seedLoggedIn(authManager, settings, token = "token-abc")

        settings.setTidepoolEnabled(true)
        settings.setTidepoolUserId("user123")
        settings.setTidepoolDatasetId("dataset-1")

        val now = System.currentTimeMillis()
        settings.setTidepoolLastUploadEnd(now - 30 * 60_000L)

        var uploadCalls = 0
        val uploader = TidepoolUploader(
            context = context,
            client = mockClient { request ->
                when (request.url.encodedPath) {
                    "/v1/datasets/dataset-1/data" -> {
                        uploadCalls += 1
                        respond(
                            content = "ok",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        )
                    }

                    else -> respond(
                        content = "not found",
                        status = HttpStatusCode.NotFound,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            },
            authManager = authManager,
            dao = db.readingDao(),
            settings = settings
        )

        uploader.uploadIfReady()

        val noOpLastEnd = settings.tidepoolLastUploadEnd.first()
        assertEquals(0, uploadCalls)
        assertEquals(0L, settings.tidepoolLastUploadTime.first())

        db.readingDao().insert(
            GlucoseReading(
                ts = noOpLastEnd,
                sgv = 125,
                direction = "Flat",
                delta = 0.0,
                pushed = 1
            )
        )

        uploader.uploadIfReady()

        assertEquals(1, uploadCalls)
        assertNotEquals(0L, settings.tidepoolLastUploadTime.first())

        db.close()
    }

    @Test
    fun `uploadIfReady still rate limits real failed upload attempts`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        val authManager = TidepoolAuthManager(context, settings)
        seedLoggedIn(authManager, settings, token = "token-abc")

        settings.setTidepoolEnabled(true)
        settings.setTidepoolUserId("user123")
        settings.setTidepoolDatasetId("dataset-1")

        val now = System.currentTimeMillis()
        settings.setTidepoolLastUploadEnd(now - 30 * 60_000L)
        db.readingDao().insert(
            GlucoseReading(
                ts = now - 20 * 60_000L,
                sgv = 120,
                direction = "Flat",
                delta = 0.0,
                pushed = 1
            )
        )

        var uploadCalls = 0
        val uploader = TidepoolUploader(
            context = context,
            client = mockClient { request ->
                when (request.url.encodedPath) {
                    "/v1/datasets/dataset-1/data" -> {
                        uploadCalls += 1
                        respond(
                            content = "failure",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        )
                    }

                    else -> respond(
                        content = "not found",
                        status = HttpStatusCode.NotFound,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            },
            authManager = authManager,
            dao = db.readingDao(),
            settings = settings
        )

        try {
            uploader.uploadIfReady()
            fail("Expected uploadIfReady to throw on failed upload")
        } catch (_: IllegalStateException) {
        }

        val firstAttempt = settings.tidepoolLastUploadTime.first()
        assertNotEquals(0L, firstAttempt)
        assertEquals(1, uploadCalls)

        uploader.uploadIfReady()

        assertEquals(1, uploadCalls)
        assertEquals(firstAttempt, settings.tidepoolLastUploadTime.first())

        db.close()
    }

    @Test
    fun `concurrent forceUpload calls serialize via mutex without double-uploading`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        val authManager = TidepoolAuthManager(context, settings)
        seedLoggedIn(authManager, settings, token = "token-abc")

        settings.setTidepoolEnabled(true)
        settings.setTidepoolUserId("user123")
        settings.setTidepoolDatasetId("dataset-1")

        val now = System.currentTimeMillis()
        settings.setTidepoolLastUploadEnd(now - 30 * 60_000L)
        db.readingDao().insert(
            GlucoseReading(
                ts = now - 20 * 60_000L,
                sgv = 120,
                direction = "Flat",
                delta = 0.0,
                pushed = 1
            )
        )

        var uploadCalls = 0
        val uploader = TidepoolUploader(
            context = context,
            client = mockClient { request ->
                when (request.url.encodedPath) {
                    "/v1/datasets/dataset-1/data" -> {
                        uploadCalls += 1
                        respond(
                            content = "ok",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        )
                    }

                    else -> respond(
                        content = "not found",
                        status = HttpStatusCode.NotFound,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            },
            authManager = authManager,
            dao = db.readingDao(),
            settings = settings
        )

        // Two simultaneous force uploads — the mutex must serialize them so only one
        // POST is observed. The second call sees the first's advanced lastUploadEnd
        // and returns 0 records.
        val results = listOf(
            async { uploader.forceUpload() },
            async { uploader.forceUpload() }
        ).awaitAll()

        assertEquals("Mutex must serialize concurrent uploads — only one POST", 1, uploadCalls)
        assertEquals("Total records uploaded must equal what was on disk", 1, results.sum())

        db.close()
    }

    @Test
    fun `forceUpload still rate limits subsequent background retry after failed attempt`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        val authManager = TidepoolAuthManager(context, settings)
        seedLoggedIn(authManager, settings, token = "token-abc")

        settings.setTidepoolEnabled(true)
        settings.setTidepoolUserId("user123")
        settings.setTidepoolDatasetId("dataset-1")

        val now = System.currentTimeMillis()
        settings.setTidepoolLastUploadEnd(now - 30 * 60_000L)
        db.readingDao().insert(
            GlucoseReading(
                ts = now - 20 * 60_000L,
                sgv = 120,
                direction = "Flat",
                delta = 0.0,
                pushed = 1
            )
        )

        var uploadCalls = 0
        val uploader = TidepoolUploader(
            context = context,
            client = mockClient { request ->
                when (request.url.encodedPath) {
                    "/v1/datasets/dataset-1/data" -> {
                        uploadCalls += 1
                        respond(
                            content = "failure",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        )
                    }

                    else -> respond(
                        content = "not found",
                        status = HttpStatusCode.NotFound,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            },
            authManager = authManager,
            dao = db.readingDao(),
            settings = settings
        )

        try {
            uploader.forceUpload()
            fail("Expected forceUpload to throw on failed upload")
        } catch (_: IllegalStateException) {
        }

        val firstAttempt = settings.tidepoolLastUploadTime.first()
        assertNotEquals(0L, firstAttempt)
        assertEquals(1, uploadCalls)

        uploader.uploadIfReady()

        assertEquals(1, uploadCalls)
        assertEquals(firstAttempt, settings.tidepoolLastUploadTime.first())

        db.close()
    }
}
