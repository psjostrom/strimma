package com.psjostrom.strimma.tidepool

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.toByteArray
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
    fun `a reading stored after a newer one was uploaded still reaches Tidepool`() = runTest {
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

        val uploadedBodies = mutableListOf<String>()
        val uploader = TidepoolUploader(
            context = context,
            client = mockClient { request ->
                uploadedBodies.add(String(request.body.toByteArray()))
                respond(
                    content = "ok",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            },
            authManager = authManager,
            dao = db.readingDao(),
            settings = settings
        )
        val now = System.currentTimeMillis()
        val newer = now - 20 * 60_000L
        val lateArrival = now - 40 * 60_000L

        db.readingDao().insert(GlucoseReading(ts = newer, sgv = 120, direction = "Flat", delta = 0.0))
        assertEquals(1, uploader.forceUpload())

        // A follower catch-up stores a reading older than the one already uploaded.
        db.readingDao().insert(GlucoseReading(ts = lateArrival, sgv = 140, direction = "Flat", delta = 0.0))
        assertEquals(1, uploader.forceUpload())
        assertEquals(0, uploader.forceUpload())

        assertEquals(2, uploadedBodies.size)
        assertTrue(uploadedBodies[1].contains("strimma-cbg-$lateArrival"))

        uploader.stop()
        db.close()
    }

    private class UploadEnv(
        val db: StrimmaDatabase,
        val dataStore: DataStore<Preferences>,
        val uploader: TidepoolUploader,
        val uploadedBodies: List<String>,
    )

    /** A logged-in uploader whose Tidepool server accepts every upload and keeps the bodies. */
    private suspend fun uploadEnv(): UploadEnv {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dataStore = createTestDataStore()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), dataStore)
        val authManager = TidepoolAuthManager(context, settings)
        seedLoggedIn(authManager, settings, token = "token-abc")
        settings.setTidepoolEnabled(true)
        settings.setTidepoolUserId("user123")
        settings.setTidepoolDatasetId("dataset-1")
        val bodies = mutableListOf<String>()
        val client = mockClient { request ->
            bodies.add(String(request.body.toByteArray()))
            respond(
                content = "ok",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val uploader = TidepoolUploader(context, client, authManager, db.readingDao(), settings)
        return UploadEnv(db, dataStore, uploader, bodies)
    }

    private fun reading(ts: Long, sgv: Int = 120) = GlucoseReading(ts = ts, sgv = sgv, direction = "Flat", delta = 0.0)

    @Test
    fun `readings up to the old upload cursor are not sent again after the update`() = runTest {
        // The cursor key as the previous version stored it.
        val oldCursorKey = longPreferencesKey("tidepool_last_upload_end")
        val env = uploadEnv()
        val now = System.currentTimeMillis()
        val sentBefore = now - 60 * 60_000L
        val notSentYet = now - 20 * 60_000L
        env.dataStore.edit { it[oldCursorKey] = now - 30 * 60_000L }
        env.db.readingDao().insertBatch(listOf(reading(sentBefore), reading(notSentYet)))

        assertEquals(1, env.uploader.forceUpload())
        assertTrue(env.uploadedBodies.single().contains("strimma-cbg-$notSentYet"))
        assertEquals(null, env.dataStore.data.first()[oldCursorKey])

        env.uploader.stop()
        env.db.close()
    }

    @Test
    fun `an invalid reading at the front of the queue does not block valid ones`() = runTest {
        val env = uploadEnv()
        val now = System.currentTimeMillis()
        env.db.readingDao().insertBatch(listOf(reading(now - 40 * 60_000L, sgv = 30), reading(now - 20 * 60_000L)))

        assertEquals(1, env.uploader.forceUpload())
        assertEquals(0, env.uploader.forceUpload())

        env.uploader.stop()
        env.db.close()
    }

    @Test
    fun `a backlog larger than one upload drains over two uploads`() = runTest {
        val env = uploadEnv()
        val newest = System.currentTimeMillis() - 20 * 60_000L
        val backlog = (0 until 7 * 24 * 60 + 1).map { reading(newest - it * 60_000L) }
        env.db.readingDao().insertBatch(backlog)

        assertEquals(7 * 24 * 60, env.uploader.forceUpload())
        assertEquals(1, env.uploader.forceUpload())
        assertEquals(0, env.uploader.forceUpload())

        env.uploader.stop()
        env.db.close()
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

        db.readingDao().insert(
            GlucoseReading(
                ts = now - 20 * 60_000L,
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
        // POST is observed. The second call finds the reading already marked uploaded
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
