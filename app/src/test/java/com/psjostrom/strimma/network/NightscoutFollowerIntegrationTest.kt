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

    @Test
    fun `poll skips duplicate entry already present in DB within dedup window`() = runTest {
        val env = createEnv()
        env.settings.setNightscoutUrl("https://ns.example.com")
        env.settings.setNightscoutSecret("secret")
        env.settings.setFollowerPollSeconds(60)

        val existingTs = System.currentTimeMillis() - 60_000
        env.dao.insert(
            GlucoseReading(ts = existingTs, sgv = 110, direction = "Flat", delta = null, pushed = 1)
        )

        val follower = NightscoutFollower(env.fakeClient, env.dao, env.directionComputer, env.settings)
        val newReadings = mutableListOf<GlucoseReading>()
        val job = follower.start(this) { newReadings.add(it) }
        advanceAndSettle(env)

        // Poll receives an entry with the same ts (within the 3 s dedup window).
        env.fakeClient.entries = listOf(entry(125, existingTs + 1_000))
        advanceTimeBy(61_000)
        advanceAndSettle(env)

        val readings = env.dao.lastN(10)
        assertEquals("Duplicate entry within 3 s of an existing reading must not be inserted", 1, readings.size)
        assertEquals("Original reading must be preserved", 110, readings[0].sgv)
        assertTrue("onNewReading must not fire for a deduplicated entry", newReadings.isEmpty())

        job.cancel()
    }

    @Test
    fun `backfill continues past full invalid page to later valid readings`() = runTest {
        val env = createEnv()
        env.settings.setNightscoutUrl("https://ns.example.com")
        env.settings.setNightscoutSecret("secret")

        val invalidPage = (0 until 2016).map { i ->
            NightscoutEntryResponse(sgv = 100 + (i % 20), date = baseTs - i * 60_000L, type = "cal")
        }
        val validPage = listOf(
            entry(115, baseTs - 2016 * 60_000L),
            entry(120, baseTs - 2017 * 60_000L)
        )
        env.fakeClient.entries = invalidPage + validPage

        val follower = NightscoutFollower(env.fakeClient, env.dao, env.directionComputer, env.settings)
        val newReadings = mutableListOf<GlucoseReading>()
        val job = follower.start(this) { newReadings.add(it) }
        advanceAndSettle(env)

        val readings = env.dao.lastN(10)
        assertEquals("Backfill should continue to the later valid page", 2, readings.size)
        assertEquals("Only the final backfill reading should trigger onNewReading", 1, newReadings.size)

        job.cancel()
    }

    @Test
    fun `backfill stores every entry of a gap that spans several pages`() = runTest {
        val env = createEnv()
        env.settings.setNightscoutUrl("https://ns.example.com")
        env.settings.setNightscoutSecret("secret")
        val count = 6 * 1440
        env.fakeClient.entries = (0 until count).map { i -> entry(100 + (i % 50), baseTs - i * 60_000L) }

        val follower = NightscoutFollower(env.fakeClient, env.dao, env.directionComputer, env.settings)
        val job = follower.start(this) { }
        advanceAndSettle(env)
        // Each page makes its own Room calls, so one settle round is not enough.
        repeat(10) { if (follower.status.value is IntegrationStatus.Connecting) advanceAndSettle(env) }

        assertTrue(follower.status.value is IntegrationStatus.Connected)
        assertEquals(count, env.dao.since(0).size)

        job.cancel()
    }

    @Test
    fun `poll stores every entry of a gap longer than one page`() = runTest {
        val env = createEnv()
        env.settings.setNightscoutUrl("https://ns.example.com")
        env.settings.setNightscoutSecret("secret")
        env.settings.setFollowerPollSeconds(60)
        val gapStart = System.currentTimeMillis() - 3 * 1440 * 60_000L
        env.dao.insert(GlucoseReading(ts = gapStart, sgv = 100, direction = "Flat", delta = null, pushed = 1))

        val follower = NightscoutFollower(env.fakeClient, env.dao, env.directionComputer, env.settings)
        val job = follower.start(this) { }
        advanceAndSettle(env)

        val gapCount = 3 * 1440
        env.fakeClient.entries = (1..gapCount).map { i -> entry(100 + (i % 50), gapStart + i * 60_000L) }
        advanceTimeBy(61_000)
        advanceAndSettle(env)

        assertEquals(gapCount + 1, env.dao.since(0).size)

        job.cancel()
    }

    @Test
    fun `poll stores the newest page when an older page fails`() = runTest {
        val env = createEnv()
        env.settings.setNightscoutUrl("https://ns.example.com")
        env.settings.setNightscoutSecret("secret")
        env.settings.setFollowerPollSeconds(60)
        val gapStart = System.currentTimeMillis() - 3 * 1440 * 60_000L
        env.dao.insert(GlucoseReading(ts = gapStart, sgv = 100, direction = "Flat", delta = null, pushed = 1))

        val follower = NightscoutFollower(env.fakeClient, env.dao, env.directionComputer, env.settings)
        val newReadings = mutableListOf<GlucoseReading>()
        val job = follower.start(this) { newReadings.add(it) }
        advanceAndSettle(env)

        val newestTs = gapStart + 3 * 1440 * 60_000L
        env.fakeClient.entries = (0 until 3 * 1440).map { i -> entry(100 + (i % 50), newestTs - i * 60_000L) }
        env.fakeClient.failOlderPages = true
        advanceTimeBy(61_000)
        advanceAndSettle(env)

        assertEquals(newestTs, env.dao.latestOnce()!!.ts)
        assertEquals(listOf(newestTs), newReadings.map { it.ts })
        assertTrue(follower.status.value is IntegrationStatus.Error)

        job.cancel()
    }

    @Test
    fun `poll ends with an error when the server ignores the before cursor`() = runTest {
        val env = createEnv()
        env.settings.setNightscoutUrl("https://ns.example.com")
        env.settings.setNightscoutSecret("secret")
        env.settings.setFollowerPollSeconds(60)
        val gapStart = System.currentTimeMillis() - 3 * 1440 * 60_000L
        env.dao.insert(GlucoseReading(ts = gapStart, sgv = 100, direction = "Flat", delta = null, pushed = 1))

        val follower = NightscoutFollower(env.fakeClient, env.dao, env.directionComputer, env.settings)
        val job = follower.start(this) { }
        advanceAndSettle(env)

        env.fakeClient.entries = (1..3 * 1440).map { i -> entry(100 + (i % 50), gapStart + i * 60_000L) }
        env.fakeClient.ignoreBefore = true
        advanceTimeBy(61_000)
        advanceAndSettle(env)

        assertTrue(follower.status.value is IntegrationStatus.Error)

        job.cancel()
    }

    @Test
    fun `processNightscoutEntries batches inserts and computes direction without future pollution`() = runTest {
        val env = createEnv()
        val t0 = baseTs - 15 * 60_000L
        val t1 = baseTs - 10 * 60_000L
        val t2 = baseTs - 5 * 60_000L
        val t3 = baseTs

        // Pre-insert an existing reading in DB at t0 and a future reading at t3
        env.dao.insert(GlucoseReading(ts = t0, sgv = 100, direction = "Flat", delta = null, pushed = 1))
        env.dao.insert(GlucoseReading(ts = t3, sgv = 200, direction = "SingleUp", delta = 20.0, pushed = 1))

        // Batch contains duplicate of t0, and new readings at t2 (115) and t1 (110) (unsorted)
        val batch = listOf(
            entry(100, t0),
            entry(115, t2),
            entry(110, t1)
        )

        val inserted = processNightscoutEntries(batch, env.dao, env.directionComputer)
        assertEquals(2, inserted.size)
        assertEquals(t1, inserted[0].ts)
        assertEquals(t2, inserted[1].ts)

        // Reading at t2 compares with t1 (110) five minutes earlier: +5 mg/dL is Flat.
        // The reading at t3 (200) is newer than t2, so it must not change the result.
        assertEquals("Flat", inserted[1].direction)
        assertEquals(5.0, inserted[1].delta!!, 0.001)

        // All readings in DB should now be 4 (t0, t1, t2, t3)
        assertEquals(4, env.dao.lastN(10).size)
    }

    @Test
    fun `processNightscoutEntries drops entries more than five minutes in the future`() = runTest {
        val env = createEnv()
        val now = System.currentTimeMillis()
        val batch = listOf(
            entry(110, now - 60_000),
            entry(115, now + 4 * 60_000),
            entry(200, now + 60 * 60_000)
        )

        processNightscoutEntries(batch, env.dao, env.directionComputer)

        assertEquals(listOf(now + 4 * 60_000, now - 60_000), env.dao.lastN(10).map { it.ts })
    }

    private class FakeClient : NightscoutClient() {
        var entries: List<NightscoutEntryResponse> = emptyList()
        var fetchReturnsNull = false
        var failOlderPages = false
        var ignoreBefore = false

        // Answers the way a Nightscout server answers /api/v1/entries: newest first,
        // date after `since`, date before `before`, at most `count` entries.
        override suspend fun fetchEntries(
            baseUrl: String,
            apiSecret: String,
            since: Long,
            count: Int,
            before: Long?
        ): List<NightscoutEntryResponse>? {
            if (fetchReturnsNull) return null
            if (failOlderPages && before != null) return null
            val upper = if (ignoreBefore) null else before
            return entries
                .filter { it.date!! > since && (upper == null || it.date!! < upper) }
                .sortedByDescending { it.date }
                .take(count)
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
