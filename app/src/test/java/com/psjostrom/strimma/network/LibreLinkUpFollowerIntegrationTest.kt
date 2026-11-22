package com.psjostrom.strimma.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.DirectionComputer
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibreLinkUpFollowerIntegrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The reading a fake LibreLinkUp server returns as the current measurement. */
    private class CurrentMeasurement(var sgv: Int, var ts: Long)

    /** A LibreLinkUp client whose server answers login, connections and graph requests. */
    private fun lluClient(current: CurrentMeasurement): LibreLinkUpClient {
        val factoryFormat = DateTimeFormatter.ofPattern("M/d/yyyy h:mm:ss a", Locale.US).withZone(ZoneOffset.UTC)
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/auth/login") ->
                    """{"status":0,"data":{"authTicket":{"token":"token"},"user":{"id":"user"}}}"""
                path.endsWith("/graph") -> {
                    val factoryTimestamp = factoryFormat.format(Instant.ofEpochMilli(current.ts))
                    """{"status":0,"data":{"connection":{"patientId":"patient","glucoseMeasurement":""" +
                        """{"ValueInMgPerDl":${current.sgv},"FactoryTimestamp":"$factoryTimestamp"}},""" +
                        """"graphData":[]}}"""
                }
                else -> """{"status":0,"data":[{"patientId":"patient","firstName":"Test"}]}"""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        return LibreLinkUpClient(HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } })
    }

    @Test
    fun `a reading callback that throws does not stop the follower`() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(executor)
            .setTransactionExecutor(executor)
            .build()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        settings.setLluEmail("follower@example.com")
        settings.setLluPassword("password")
        val start = System.currentTimeMillis() / 1000 * 1000
        val current = CurrentMeasurement(sgv = 130, ts = start - 60_000)

        val follower = LibreLinkUpFollower(lluClient(current), db.readingDao(), DirectionComputer(), settings)
        val delivered = mutableListOf<Int>()
        var calls = 0
        val job = follower.start(this) { reading ->
            calls++
            check(calls > 1) { "first callback fails" }
            delivered.add(reading.sgv)
        }
        settleFollower(executor)
        assertEquals(IntegrationStatus.Error("Poll failed"), follower.status.value)

        current.sgv = 140
        current.ts = start
        advanceTimeBy(61_000)
        settleFollower(executor)

        assertTrue(follower.status.value is IntegrationStatus.Connected)
        assertEquals(listOf(140), delivered)
        assertTrue(job.isActive)

        job.cancel()
        db.close()
    }
}
