package com.psjostrom.strimma.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.channels.UnresolvedAddressException

/**
 * Regression tests for the airplane-mode boot crash, applied at the
 * `TreatmentSyncer` level. Unlike `NightscoutClient.fetchEntries`,
 * `fetchTreatments` intentionally rethrows after logging — the catch
 * widening in `TreatmentSyncer.pullHistory` and `syncSince` is therefore on
 * the primary regression path: an airplane-mode boot reaches these methods as
 * a thrown `UnresolvedAddressException`, and they must convert it to
 * `Result.failure` / `IntegrationStatus.Error` instead of letting it crash
 * the foreground service.
 */
@RunWith(RobolectricTestRunner::class)
class TreatmentSyncerAirplaneModeTest {

    private lateinit var db: StrimmaDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var syncer: TreatmentSyncer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())

        // Real NightscoutClient with a MockEngine that throws on every request,
        // so fetchTreatments rethrows UnresolvedAddressException to TreatmentSyncer.
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler { throw UnresolvedAddressException() }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        val airplaneClient = NightscoutClient(httpClient)
        syncer = TreatmentSyncer(airplaneClient, db.treatmentDao(), settings)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `pullHistory returns Result failure when DNS fails`() = runTest {
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("secret")

        val result = syncer.pullHistory(days = 1)

        assertTrue("Expected Result.failure, got $result", result.isFailure)
        assertTrue(
            "Expected UnresolvedAddressException, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is UnresolvedAddressException
        )
    }

    // Note: `syncSince` (which sets IntegrationStatus.Error with a sanitized message
    // for network failures) is private and called from a `start()` polling loop. The
    // existing TreatmentSyncerIntegrationTest covers status-on-failure behavior with
    // an IOException; the catch-widening this PR adds is exercised by `pullHistory`
    // above, which goes through the same `withNetworkBoundary` path.
}
