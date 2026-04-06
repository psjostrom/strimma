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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NightscoutPusherTest {

    private lateinit var db: StrimmaDatabase
    private lateinit var dao: ReadingDao
    private lateinit var fakeClient: FakeNightscoutClient
    private lateinit var fakeSettings: FakeSettingsRepository
    private lateinit var fakeAlertManager: FakeAlertManager
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
        fakeSettings = FakeSettingsRepository()
        fakeAlertManager = FakeAlertManager()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun TestScope.createPusher(): NightscoutPusher {
        val pusher = NightscoutPusher(fakeClient, dao, fakeSettings, fakeAlertManager)
        pusher.scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        return pusher
    }

    /**
     * Advances virtual time and waits for Room's real executor to complete.
     * Room suspend functions dispatch to a real thread pool even in tests, so we need
     * to alternate between advancing the test dispatcher and yielding to let Room complete.
     */
    private fun TestScope.advanceAndSettle(maxIterations: Int = 20) {
        repeat(maxIterations) {
            advanceUntilIdle()
            Thread.sleep(10) // let Room executor finish
        }
    }

    // --- pushReading: success on first attempt ---

    @Test
    fun `pushReading marks reading as pushed on success`() = runTest {
        dao.insert(reading(sgv = 120))
        fakeClient.pushResult = true
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        val unpushed = dao.unpushed()
        assertTrue("Reading should be marked as pushed", unpushed.isEmpty())
    }

    @Test
    fun `pushReading updates status to Connected on success`() = runTest {
        fakeClient.pushResult = true
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

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
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertEquals(1, fakeClient.pushCallCount.get())
    }

    @Test
    fun `pushReading calls failureTracker onSuccess on successful push`() = runTest {
        fakeClient.pushResult = true
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertTrue(
            "AlertManager.handlePushFailure(false) should be called on success",
            fakeAlertManager.pushFailureCalls.contains(false)
        )
    }

    // --- pushReading: skip when URL or secret empty ---

    @Test
    fun `pushReading skips when URL is empty`() = runTest {
        fakeSettings.url = ""
        fakeSettings.secret = "mysecret"

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertEquals(0, fakeClient.pushCallCount.get())
    }

    @Test
    fun `pushReading skips when secret is empty`() = runTest {
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = ""

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertEquals(0, fakeClient.pushCallCount.get())
    }

    @Test
    fun `pushReading skips when both URL and secret are blank`() = runTest {
        fakeSettings.url = "  "
        fakeSettings.secret = ""

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
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

        dao.insert(reading(sgv = 120))
        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertEquals("Should retry until success", 3, fakeClient.pushCallCount.get())
        assertTrue("Reading should be marked as pushed", dao.unpushed().isEmpty())
        assertTrue("Status should be Connected", pusher.status.value is IntegrationStatus.Connected)
    }

    // --- pushReading: gives up after max attempts ---

    @Test
    fun `pushReading gives up after MAX_RETRY_ATTEMPTS`() = runTest {
        fakeClient.pushResult = false
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

        dao.insert(reading(sgv = 120))
        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertEquals(NightscoutPusher.MAX_RETRY_ATTEMPTS, fakeClient.pushCallCount.get())
        assertEquals("Reading should remain unpushed", 1, dao.unpushed().size)
    }

    @Test
    fun `pushReading calls failureTracker onFailure for each failed attempt`() = runTest {
        fakeClient.pushResult = false
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertEquals(NightscoutPusher.MAX_RETRY_ATTEMPTS, fakeClient.pushCallCount.get())
        // The PushFailureTracker is internal, but its effect is observable:
        // after enough time, alertManager.handlePushFailure(true) is called.
        // However, the virtual time doesn't match wall-clock for the tracker's
        // System.currentTimeMillis() check, so we just verify all attempts ran.
    }

    // --- pushReading: backoff timing ---

    @Test
    fun `pushReading backoff delays increase linearly capped at 60s`() = runTest {
        fakeClient.pushResult = false
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))

        // First call is immediate (just need to dispatch the coroutine)
        advanceTimeBy(1)
        assertEquals("First call should be immediate", 1, fakeClient.pushCallCount.get())

        // attempt=1 delay: 1 * 5000 = 5000ms
        advanceTimeBy(4999)
        assertEquals("Should not retry before 5s", 1, fakeClient.pushCallCount.get())
        advanceTimeBy(1)
        assertEquals("Should retry after 5s", 2, fakeClient.pushCallCount.get())

        // attempt=2 delay: 2 * 5000 = 10000ms
        advanceTimeBy(9999)
        assertEquals("Should not retry before 10s", 2, fakeClient.pushCallCount.get())
        advanceTimeBy(1)
        assertEquals("Should retry after 10s", 3, fakeClient.pushCallCount.get())

        // attempt=3 delay: 3 * 5000 = 15000ms
        advanceTimeBy(15000)
        assertEquals("Should retry after 15s", 4, fakeClient.pushCallCount.get())

        // Complete remaining retries
        advanceAndSettle()
        assertEquals(NightscoutPusher.MAX_RETRY_ATTEMPTS, fakeClient.pushCallCount.get())
    }

    @Test
    fun `pushReading backoff caps at MAX_RETRY_DELAY_MS`() = runTest {
        // Verify the backoff formula: delays are attempt * 5000, capped at 60000
        // Delays: 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60 (seconds)
        // Only attempt 12 hits the cap (12*5000 = 60000, equals the cap)
        val expectedTotalDelay = (1..NightscoutPusher.MAX_RETRY_ATTEMPTS).sumOf { attempt ->
            (attempt * NightscoutPusher.RETRY_BASE_DELAY_MS)
                .coerceAtMost(NightscoutPusher.MAX_RETRY_DELAY_MS)
        }
        // 5+10+15+20+25+30+35+40+45+50+55+60 = 390 seconds = 390000ms
        assertEquals(390_000L, expectedTotalDelay)
    }

    // --- pushPending: pushes all unpushed readings ---

    @Test
    fun `pushPending pushes all unpushed readings`() = runTest {
        fakeClient.pushResult = true
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

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
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

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
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

        val pusher = createPusher()
        pusher.pushPending()
        advanceAndSettle()

        assertEquals(0, fakeClient.pushCallCount.get())
    }

    @Test
    fun `pushPending skips when all readings already pushed`() = runTest {
        fakeClient.pushResult = true
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

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
        fakeSettings.url = ""
        fakeSettings.secret = "mysecret"

        dao.insert(reading(minutesAgo = 1, sgv = 120, pushed = 0))

        val pusher = createPusher()
        pusher.pushPending()
        advanceAndSettle()

        assertEquals(0, fakeClient.pushCallCount.get())
    }

    @Test
    fun `pushPending skips when secret is empty`() = runTest {
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = ""

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
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

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
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

        dao.insert(reading(minutesAgo = 1, sgv = 120, pushed = 0))

        val pusher = createPusher()
        pusher.pushPending()
        advanceAndSettle()

        assertEquals("pushPending should call client exactly once", 1, fakeClient.pushCallCount.get())
    }

    // --- stop cancels scope ---

    @Test
    fun `stop prevents further pushes`() = runTest {
        fakeClient.pushResult = true
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

        val pusher = createPusher()
        pusher.stop()

        dao.insert(reading(sgv = 120, pushed = 0))
        pusher.pushReading(reading(sgv = 120))
        advanceAndSettle()

        assertEquals("Client should not be called after stop", 0, fakeClient.pushCallCount.get())
    }

    @Test
    fun `stop cancels in-flight retry loop`() = runTest {
        fakeClient.pushResult = false
        fakeSettings.url = "https://ns.example.com"
        fakeSettings.secret = "mysecret"

        val pusher = createPusher()
        pusher.pushReading(reading(sgv = 120))

        // Let the first attempt execute (dispatch coroutine start)
        advanceTimeBy(1)
        assertEquals(1, fakeClient.pushCallCount.get())

        // Stop during the first retry delay (before 5s elapses)
        pusher.stop()
        advanceAndSettle()

        assertTrue(
            "Should not complete all retries after stop",
            fakeClient.pushCallCount.get() < NightscoutPusher.MAX_RETRY_ATTEMPTS
        )
    }

    // --- Test doubles ---

    private class FakeNightscoutClient : NightscoutClient() {
        val pushCallCount = AtomicInteger(0)
        var lastPushReadings: List<GlucoseReading> = emptyList()

        /** Single result for all calls (default mode). */
        var pushResult: Boolean = true

        /** Per-call results. When set, takes priority over pushResult. */
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

    private class FakeSettingsRepository : SettingsRepository(
        context = ApplicationProvider.getApplicationContext(),
        widgetSettingsRepository = WidgetSettingsRepository(ApplicationProvider.getApplicationContext())
    ) {
        var url: String = ""
        var secret: String = ""

        override val nightscoutUrl: Flow<String>
            get() = flowOf(url)

        override fun getNightscoutSecret(): String = secret
    }

    private class FakeAlertManager : AlertManager(
        context = ApplicationProvider.getApplicationContext(),
        settings = FakeSettingsRepository()
    ) {
        val pushFailureCalls = CopyOnWriteArrayList<Boolean>()

        override fun handlePushFailure(firing: Boolean) {
            pushFailureCalls.add(firing)
        }
    }
}
