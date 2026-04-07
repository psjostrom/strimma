package com.psjostrom.strimma.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NightscoutPusherTest {

    private lateinit var db: StrimmaDatabase
    private lateinit var dao: ReadingDao
    private lateinit var settings: SettingsRepository
    private lateinit var alertManager: AlertManager
    private lateinit var fakeClient: FakeNightscoutClient
    private val roomExecutor = Executors.newSingleThreadExecutor()

    private val baseTs = 1_700_000_000_000L

    private fun reading(minutesAgo: Int = 0, sgv: Int = 120, pushed: Int = 0): GlucoseReading {
        return GlucoseReading(
            ts = baseTs - minutesAgo * 60_000L,
            sgv = sgv,
            direction = "Flat",
            delta = 0.0,
            pushed = pushed
        )
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(roomExecutor)
            .setTransactionExecutor(roomExecutor)
            .build()
        dao = db.readingDao()
        fakeClient = FakeNightscoutClient()
        settings = SettingsRepository(context, WidgetSettingsRepository(context))
        alertManager = AlertManager(context, settings)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun TestScope.createPusher(): NightscoutPusher {
        return NightscoutPusher(fakeClient, dao, settings, alertManager, StandardTestDispatcher(testScheduler))
    }

    /**
     * Advances virtual time and waits for async executors to complete.
     * Room dispatches to [roomExecutor] (drained deterministically via submit/get).
     * DataStore dispatches internally to Dispatchers.IO (brief sleep between rounds).
     */
    private fun TestScope.advanceAndSettle() {
        repeat(5) {
            advanceUntilIdle()
            roomExecutor.submit {}.get()
            Thread.sleep(10)
        }
    }

    // --- pushReading: success on first attempt ---

    @Test
    fun `pushReading marks reading as pushed on success`() = runTest {
        dao.insert(reading(sgv = 120))
        fakeClient.pushResult = true
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("mysecret")

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        val unpushed = dao.unpushed()
        assertTrue("Reading should be marked as pushed", unpushed.isEmpty())
    }

    @Test
    fun `pushReading updates status to Connected on success`() = runTest {
        fakeClient.pushResult = true
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("mysecret")

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertTrue(
            "Status should be Connected but was ${pusher.status.value}",
            pusher.status.value is IntegrationStatus.Connected
        )
    }

    @Test
    fun `pushReading calls client exactly once on first success`() = runTest {
        fakeClient.pushResult = true
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("mysecret")

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertEquals(1, fakeClient.pushCallCount.get())
    }

    // --- pushReading: skip when URL or secret empty ---

    @Test
    fun `pushReading skips when URL is empty`() = runTest {
        settings.setNightscoutUrl("")
        settings.setNightscoutSecret("mysecret")

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertEquals(0, fakeClient.pushCallCount.get())
    }

    @Test
    fun `pushReading skips when secret is empty`() = runTest {
        settings.setNightscoutUrl("https://ns.example.com")

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertEquals(0, fakeClient.pushCallCount.get())
    }

    @Test
    fun `pushReading skips when both URL and secret are blank`() = runTest {
        settings.setNightscoutUrl("  ")

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertEquals(0, fakeClient.pushCallCount.get())
        assertEquals(IntegrationStatus.Idle, pusher.status.value)
    }

    // --- pushReading: retries on failure ---

    @Test
    fun `pushReading retries on failure then succeeds`() = runTest {
        fakeClient.pushResults = mutableListOf(false, false, true)
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("mysecret")

        dao.insert(reading(sgv = 120))
        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertEquals("Should retry until success", 3, fakeClient.pushCallCount.get())
        assertTrue("Reading should be marked as pushed", dao.unpushed().isEmpty())
        assertTrue("Status should be Connected", pusher.status.value is IntegrationStatus.Connected)
    }

    // --- pushReading: gives up after exhausting retries ---

    @Test
    fun `pushReading eventually gives up on persistent failure`() = runTest {
        fakeClient.pushResult = false
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("mysecret")

        dao.insert(reading(sgv = 120))
        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertTrue("Should have retried multiple times", fakeClient.pushCallCount.get() > 1)
        assertEquals("Reading should remain unpushed", 1, dao.unpushed().size)
    }

    // --- pushReading: backoff timing ---

    @Test
    fun `pushReading retries with increasing delays`() = runTest {
        fakeClient.pushResult = false
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("mysecret")

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))

        // Let DataStore's internal IO dispatcher deliver the cached value,
        // then process pending coroutine tasks without advancing virtual time.
        repeat(5) { Thread.sleep(20); testScheduler.runCurrent() }
        assertEquals("First call should be immediate", 1, fakeClient.pushCallCount.get())

        // First retry after 5s delay (use 5001 to avoid exact-boundary edge cases)
        advanceTimeBy(5001)
        assertEquals("Should retry after 5s", 2, fakeClient.pushCallCount.get())

        // Second retry after 10s (delays increase)
        advanceTimeBy(10001)
        assertEquals("Should retry after 10s", 3, fakeClient.pushCallCount.get())

        // Third retry after 15s
        advanceTimeBy(15001)
        assertEquals("Should retry after 15s", 4, fakeClient.pushCallCount.get())
    }

    // --- pushPending: pushes all unpushed readings ---

    @Test
    fun `pushPending pushes all unpushed readings`() = runTest {
        fakeClient.pushResult = true
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("mysecret")

        dao.insert(reading(minutesAgo = 3, sgv = 90, pushed = 0))
        dao.insert(reading(minutesAgo = 2, sgv = 108, pushed = 0))
        dao.insert(reading(minutesAgo = 1, sgv = 126, pushed = 0))

        val pusher = createPusher()
        pusher.pushPending()
        advanceAndSettle()

        assertEquals("Batch push should call client once", 1, fakeClient.pushCallCount.get())
        assertEquals("Batch should contain 3 readings", 3, fakeClient.lastPushReadings.size)
        assertTrue("All readings should be marked as pushed", dao.unpushed().isEmpty())
    }

    @Test
    fun `pushPending updates status to Connected on success`() = runTest {
        fakeClient.pushResult = true
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("mysecret")

        dao.insert(reading(minutesAgo = 1, sgv = 120, pushed = 0))

        val pusher = createPusher()
        pusher.pushPending()
        advanceAndSettle()

        assertTrue(
            "Status should be Connected but was ${pusher.status.value}",
            pusher.status.value is IntegrationStatus.Connected
        )
    }

    // --- pushPending: skips when no pending readings ---

    @Test
    fun `pushPending skips when no pending readings`() = runTest {
        fakeClient.pushResult = true
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("mysecret")

        val pusher = createPusher()
        pusher.pushPending()
        advanceAndSettle()

        assertEquals(0, fakeClient.pushCallCount.get())
    }

    @Test
    fun `pushPending skips when all readings already pushed`() = runTest {
        fakeClient.pushResult = true
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("mysecret")

        dao.insert(reading(minutesAgo = 1, sgv = 120, pushed = 1))
        dao.insert(reading(minutesAgo = 2, sgv = 130, pushed = 1))

        val pusher = createPusher()
        pusher.pushPending()
        advanceAndSettle()

        assertEquals(0, fakeClient.pushCallCount.get())
    }

    // --- pushPending: skips when URL or secret empty ---

    @Test
    fun `pushPending skips when URL is empty`() = runTest {
        settings.setNightscoutUrl("")
        settings.setNightscoutSecret("mysecret")

        dao.insert(reading(minutesAgo = 1, sgv = 120, pushed = 0))

        val pusher = createPusher()
        pusher.pushPending()
        advanceAndSettle()

        assertEquals(0, fakeClient.pushCallCount.get())
    }

    @Test
    fun `pushPending skips when secret is empty`() = runTest {
        settings.setNightscoutUrl("https://ns.example.com")

        dao.insert(reading(minutesAgo = 1, sgv = 120, pushed = 0))

        val pusher = createPusher()
        pusher.pushPending()
        advanceAndSettle()

        assertEquals(0, fakeClient.pushCallCount.get())
    }

    // --- pushPending: batch failure ---

    @Test
    fun `pushPending leaves readings unpushed on failure`() = runTest {
        fakeClient.pushResult = false
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("mysecret")

        dao.insert(reading(minutesAgo = 2, sgv = 108, pushed = 0))
        dao.insert(reading(minutesAgo = 1, sgv = 126, pushed = 0))

        val pusher = createPusher()
        pusher.pushPending()
        advanceAndSettle()

        assertEquals("Readings should remain unpushed", 2, dao.unpushed().size)
    }

    @Test
    fun `pushPending does not retry on failure`() = runTest {
        fakeClient.pushResult = false
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("mysecret")

        dao.insert(reading(minutesAgo = 1, sgv = 120, pushed = 0))

        val pusher = createPusher()
        pusher.pushPending()
        advanceAndSettle()

        assertEquals("pushPending should call client exactly once", 1, fakeClient.pushCallCount.get())
    }

    // --- stop cancels scope but pusher remains usable ---

    @Test
    fun `stop then push still works (scope is recreated)`() = runTest {
        fakeClient.pushResult = true
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("mysecret")

        val pusher = createPusher()
        pusher.stop()

        dao.insert(reading(sgv = 120, pushed = 0))
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertEquals("Client should be called after stop + new push", 1, fakeClient.pushCallCount.get())
    }

    @Test
    fun `stop cancels in-flight retry loop`() = runTest {
        fakeClient.pushResult = false
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("mysecret")

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))

        // Let DataStore deliver, then process coroutine without advancing clock
        repeat(3) { Thread.sleep(20); testScheduler.runCurrent() }
        assertEquals(1, fakeClient.pushCallCount.get())

        // Stop during the first retry delay (before 5s elapses)
        pusher.stop()
        advanceAndSettle()

        assertEquals("Should stop after first attempt", 1, fakeClient.pushCallCount.get())
    }

    // --- Test double: network boundary only ---

    private class FakeNightscoutClient : NightscoutClient() {
        val pushCallCount = AtomicInteger(0)
        var lastPushReadings: List<GlucoseReading> = emptyList()

        var pushResult: Boolean = true
        var pushResults: MutableList<Boolean>? = null

        override suspend fun pushReadings(
            baseUrl: String,
            apiSecret: String,
            readings: List<GlucoseReading>
        ): Boolean {
            val callIndex = pushCallCount.getAndIncrement()
            lastPushReadings = readings
            val results = pushResults
            return if (results != null && callIndex < results.size) {
                results[callIndex]
            } else {
                pushResult
            }
        }
    }
}
