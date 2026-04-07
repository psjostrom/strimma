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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NightscoutFollowerIntegrationTest {

    private val baseTs = System.currentTimeMillis()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun entry(sgv: Int, ts: Long): NightscoutEntryResponse =
        NightscoutEntryResponse(sgv = sgv, date = ts, type = "sgv")

    /**
     * Advances virtual time and waits for async executors to complete.
     * Room dispatches to [Env.roomExecutor] (drained via submit/get).
     * DataStore dispatches to Dispatchers.IO (brief sleep between rounds).
     * Uses advanceTimeBy (not advanceUntilIdle) to avoid hanging on the infinite polling loop.
     */
    private fun TestScope.advanceAndSettle(env: Env) {
        repeat(10) {
            advanceTimeBy(100)
            runCurrent()
            env.roomExecutor.submit {}.get()
            Thread.sleep(10)
        }
    }

    private data class Env(
        val db: StrimmaDatabase,
        val dao: ReadingDao,
        val settings: SettingsRepository,
        val fakeClient: FakeClient,
        val directionComputer: DirectionComputer,
        val roomExecutor: java.util.concurrent.ExecutorService
    )

    private fun TestScope.createEnv(): Env {
        val executor = Executors.newSingleThreadExecutor()
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(executor)
            .setTransactionExecutor(executor)
            .build()
        return Env(
            db = db,
            dao = db.readingDao(),
            settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore()),
            fakeClient = FakeClient(),
            directionComputer = DirectionComputer(),
            roomExecutor = executor
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
        advanceAndSettle(env)

        assertTrue("Backfill should insert readings", env.dao.lastN(10).isNotEmpty())
        assertEquals(1, newReadings.size)

        job.cancel()
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
        advanceAndSettle(env)

        assertTrue(follower.status.value is IntegrationStatus.Connected)

        job.cancel()
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
        advanceAndSettle(env)

        env.fakeClient.entries = listOf(entry(130, System.currentTimeMillis()))
        advanceTimeBy(61_000)
        advanceAndSettle(env)

        assertTrue("Should have received new readings from poll", newReadings.isNotEmpty())

        job.cancel()
    }

    @Test
    fun `start does nothing when NS not configured`() = runTest {
        val env = createEnv()
        // URL blank by default

        val follower = NightscoutFollower(env.fakeClient, env.dao, env.directionComputer, env.settings)
        val job = follower.start(this) { }
        advanceAndSettle(env)

        assertEquals(IntegrationStatus.Idle, follower.status.value)
        assertTrue("DB should remain empty", env.dao.lastN(10).isEmpty())

        job.cancel()
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
        advanceAndSettle(env)

        advanceTimeBy(61_000)
        advanceAndSettle(env)

        assertTrue(follower.status.value is IntegrationStatus.Error)

        job.cancel()
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
