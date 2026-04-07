package com.psjostrom.strimma.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.DirectionComputer
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.data.Treatment
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NightscoutFollowerIntegrationTest {

    private val baseTs = 1_700_000_000_000L
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun entry(sgv: Int, ts: Long): NightscoutEntryResponse =
        NightscoutEntryResponse(sgv = sgv, date = ts, type = "sgv")

    private fun TestScope.advanceAndSettle() {
        repeat(5) {
            advanceUntilIdle()
            Thread.sleep(20)
        }
    }

    private data class Env(
        val db: StrimmaDatabase,
        val dao: ReadingDao,
        val settings: SettingsRepository,
        val fakeClient: FakeClient,
        val directionComputer: DirectionComputer
    )

    private fun TestScope.createEnv(): Env {
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return Env(
            db = db,
            dao = db.readingDao(),
            settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore(this)),
            fakeClient = FakeClient(),
            directionComputer = DirectionComputer()
        )
    }

    @Test
    fun `start backfills when DB is empty`() = runTest {
        val env = createEnv()
        env.settings.setNightscoutUrl("https://ns.example.com")
        env.settings.setNightscoutSecret("secret")
        env.fakeClient.entries = listOf(
            entry(100, baseTs - 3_600_000),
            entry(120, baseTs)
        )

        val follower = NightscoutFollower(env.fakeClient, env.dao, env.directionComputer, env.settings)
        val newReadings = mutableListOf<GlucoseReading>()
        val job = follower.start(this) { newReadings.add(it) }
        advanceAndSettle()

        assertTrue("Backfill should insert readings", env.dao.lastN(10).isNotEmpty())
        assertEquals(1, newReadings.size)

        job.cancel()
        env.db.close()
    }

    @Test
    fun `start skips backfill when DB has recent data`() = runTest {
        val env = createEnv()
        env.settings.setNightscoutUrl("https://ns.example.com")
        env.settings.setNightscoutSecret("secret")
        env.dao.insert(GlucoseReading(ts = System.currentTimeMillis() - 60_000, sgv = 100, direction = "Flat", delta = null, pushed = 1))
        env.fakeClient.entries = emptyList()

        val follower = NightscoutFollower(env.fakeClient, env.dao, env.directionComputer, env.settings)
        val job = follower.start(this) { }
        advanceAndSettle()

        assertTrue(follower.status.value is IntegrationStatus.Connected)

        job.cancel()
        env.db.close()
    }

    @Test
    fun `polls and inserts new readings`() = runTest {
        val env = createEnv()
        env.settings.setNightscoutUrl("https://ns.example.com")
        env.settings.setNightscoutSecret("secret")
        env.settings.setFollowerPollSeconds(60)
        env.dao.insert(GlucoseReading(ts = System.currentTimeMillis() - 60_000, sgv = 100, direction = "Flat", delta = null, pushed = 1))

        val follower = NightscoutFollower(env.fakeClient, env.dao, env.directionComputer, env.settings)
        val newReadings = mutableListOf<GlucoseReading>()
        val job = follower.start(this) { newReadings.add(it) }
        advanceAndSettle()

        env.fakeClient.entries = listOf(entry(130, System.currentTimeMillis()))
        advanceTimeBy(61_000)
        advanceAndSettle()

        assertTrue("Should have received new readings from poll", newReadings.isNotEmpty())

        job.cancel()
        env.db.close()
    }

    @Test
    fun `start does nothing when NS not configured`() = runTest {
        val env = createEnv()
        // URL blank by default

        val follower = NightscoutFollower(env.fakeClient, env.dao, env.directionComputer, env.settings)
        val job = follower.start(this) { }
        advanceAndSettle()

        assertEquals(IntegrationStatus.Idle, follower.status.value)
        assertTrue("DB should remain empty", env.dao.lastN(10).isEmpty())

        job.cancel()
        env.db.close()
    }

    @Test
    fun `status updates to Error on fetch failure`() = runTest {
        val env = createEnv()
        env.settings.setNightscoutUrl("https://ns.example.com")
        env.settings.setNightscoutSecret("secret")
        env.settings.setFollowerPollSeconds(60)
        env.dao.insert(GlucoseReading(ts = System.currentTimeMillis() - 60_000, sgv = 100, direction = "Flat", delta = null, pushed = 1))
        env.fakeClient.fetchReturnsNull = true

        val follower = NightscoutFollower(env.fakeClient, env.dao, env.directionComputer, env.settings)
        val job = follower.start(this) { }
        advanceAndSettle()

        advanceTimeBy(61_000)
        advanceAndSettle()

        assertTrue(follower.status.value is IntegrationStatus.Error)

        job.cancel()
        env.db.close()
    }

    private class FakeClient : NightscoutClient() {
        var entries: List<NightscoutEntryResponse> = emptyList()
        var fetchReturnsNull = false

        override suspend fun fetchEntries(
            baseUrl: String,
            apiSecret: String,
            since: Long,
            count: Int,
            before: Long?
        ): List<NightscoutEntryResponse>? {
            if (fetchReturnsNull) return null
            return entries
        }

        override suspend fun pushReadings(
            baseUrl: String,
            apiSecret: String,
            readings: List<GlucoseReading>
        ): Boolean = true

        override suspend fun fetchTreatments(
            baseUrl: String,
            secret: String,
            since: Long,
            count: Int
        ): List<Treatment> = emptyList()
    }
}
