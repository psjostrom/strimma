package com.psjostrom.strimma.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.data.Treatment
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.channels.UnresolvedAddressException

/**
 * Regression tests for the airplane-mode boot crash, applied at the
 * `NightscoutPuller` level. `NightscoutClient.fetchEntries` already swallows
 * `UnresolvedAddressException` and returns null, so in production an airplane-
 * mode boot reaches `NightscoutPuller` as a null page rather than a thrown
 * exception. The catch widening in `pullSince` is defense-in-depth: if a
 * future change ever stops swallowing at the `NightscoutClient` layer, the
 * `NightscoutPuller` boundary still keeps the foreground service alive. This
 * test exercises that defense by injecting a client that throws.
 */
@RunWith(RobolectricTestRunner::class)
class NightscoutPullerAirplaneModeTest {

    private lateinit var db: StrimmaDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var puller: NightscoutPuller

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        puller = NightscoutPuller(ThrowingNightscoutClient(), db.readingDao(), settings)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `pullHistory returns Result failure when fetchEntries throws DNS error`() = runTest {
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("secret")

        val result = puller.pullHistory(days = 1)

        assertTrue("Expected Result.failure, got $result", result.isFailure)
        assertTrue(
            "Expected UnresolvedAddressException, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is UnresolvedAddressException
        )
    }

    private class ThrowingNightscoutClient : NightscoutClient() {
        override suspend fun fetchEntries(
            baseUrl: String,
            apiSecret: String,
            since: Long,
            count: Int,
            before: Long?
        ): List<NightscoutEntryResponse>? = throw UnresolvedAddressException()

        override suspend fun pushReadings(
            baseUrl: String,
            apiSecret: String,
            readings: List<GlucoseReading>
        ): Boolean = false

        override suspend fun fetchTreatments(
            baseUrl: String,
            secret: String,
            since: Long,
            count: Int
        ): List<Treatment> = emptyList()
    }
}
