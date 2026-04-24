package com.psjostrom.strimma.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.GlucoseSource
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.data.Treatment
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NightscoutPullerTest {

    private lateinit var db: StrimmaDatabase
    private lateinit var dao: ReadingDao
    private lateinit var settings: SettingsRepository
    private lateinit var fakeClient: FakeClient
    private lateinit var puller: NightscoutPuller

    private val baseTs = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.readingDao()
        fakeClient = FakeClient()
        settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        puller = NightscoutPuller(fakeClient, dao, settings)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entry(
        sgv: Int,
        ts: Long,
        direction: String = "Flat",
        delta: Double? = null
    ): NightscoutEntryResponse =
        NightscoutEntryResponse(sgv = sgv, date = ts, type = "sgv", direction = direction, delta = delta)

    // --- pullIfEmpty ---

    @Test
    fun `pullIfEmpty inserts readings when DB is empty and NS configured`() = runTest {
        settings.setGlucoseSource(GlucoseSource.COMPANION)
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("secret")


        fakeClient.entries = listOf(
            entry(120, baseTs - 60_000),
            entry(130, baseTs)
        )

        puller.pullIfEmpty()

        val readings = dao.lastN(10)
        assertEquals(2, readings.size)
        assertEquals(130, readings[0].sgv)
        assertEquals(120, readings[1].sgv)
    }

    @Test
    fun `pullIfEmpty skips when DB has readings`() = runTest {
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("secret")

        dao.insert(GlucoseReading(ts = baseTs, sgv = 100, direction = "Flat", delta = null, pushed = 1))
        fakeClient.entries = listOf(entry(120, baseTs - 60_000))

        puller.pullIfEmpty()

        assertEquals("DB should still have only the pre-inserted reading", 1, dao.lastN(10).size)
    }

    @Test
    fun `pullIfEmpty skips when in follower mode`() = runTest {
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("secret")
        settings.setGlucoseSource(GlucoseSource.NIGHTSCOUT_FOLLOWER)

        fakeClient.entries = listOf(entry(120, baseTs))

        puller.pullIfEmpty()

        assertTrue("DB should remain empty", dao.lastN(10).isEmpty())
    }

    @Test
    fun `pullIfEmpty skips when NS not configured`() = runTest {
        fakeClient.entries = listOf(entry(120, baseTs))

        puller.pullIfEmpty()

        assertTrue("DB should remain empty", dao.lastN(10).isEmpty())
    }

    // --- pullHistory ---

    @Test
    fun `pullHistory inserts readings and marks as pushed`() = runTest {
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("secret")

        fakeClient.entries = listOf(
            entry(100, baseTs - 120_000),
            entry(110, baseTs - 60_000),
            entry(120, baseTs)
        )

        val result = puller.pullHistory(7)

        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrThrow())
        val readings = dao.lastN(10)
        assertEquals(3, readings.size)
        assertTrue("All readings should be marked pushed", readings.all { it.pushed == 1 })
    }

    @Test
    fun `pullHistory returns failure when NS not configured`() = runTest {
        val result = puller.pullHistory(7)

        assertTrue(result.isFailure)
    }

    @Test
    fun `pullHistory paginates until partial page`() = runTest {
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("secret")


        // First call returns full page (PAGE_SIZE = 2016), second returns partial
        val fullPage = (0 until 2016).map { i -> entry(100 + (i % 100), baseTs - i * 60_000L) }
        val partialPage = listOf(entry(150, baseTs - 2016 * 60_000L))
        fakeClient.entriesPages = mutableListOf(fullPage, partialPage)

        val result = puller.pullHistory(7)

        assertTrue(result.isSuccess)
        val totalInserted = result.getOrThrow()
        assertTrue("Should have inserted readings from both pages", totalInserted > 2016)
    }

    @Test
    fun `pullHistory returns failure when fetch fails`() = runTest {
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("secret")

        fakeClient.fetchReturnsNull = true

        val result = puller.pullHistory(7)

        assertTrue(result.isFailure)
    }

    @Test
    fun `pullHistory filters out non-sgv entries`() = runTest {
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("secret")

        fakeClient.entries = listOf(
            entry(120, baseTs),
            NightscoutEntryResponse(sgv = 130, date = baseTs + 60_000, type = "cal"),
            NightscoutEntryResponse(sgv = null, date = baseTs + 120_000, type = "sgv")
        )

        val result = puller.pullHistory(7)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow())
    }

    @Test
    fun `pullHistory continues past full invalid page to older valid entries`() = runTest {
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("secret")

        val invalidPage = (0 until 2016).map { i ->
            NightscoutEntryResponse(sgv = 100 + (i % 20), date = baseTs - i * 60_000L, type = "cal")
        }
        val validPage = listOf(entry(115, baseTs - 2016 * 60_000L))
        fakeClient.entriesPages = mutableListOf(invalidPage, validPage)

        val result = puller.pullHistory(7)

        assertTrue(result.isSuccess)
        assertEquals("Pull should continue to the older valid page", 1, result.getOrThrow())
        assertEquals(1, dao.lastN(10).size)
    }

    @Test
    fun `pullHistory preserves Nightscout payload direction and delta for speed`() = runTest {
        settings.setNightscoutUrl("https://ns.example.com")
        settings.setNightscoutSecret("secret")

        fakeClient.entries = listOf(
            entry(100, baseTs - 10 * 60_000L, direction = "Flat", delta = 0.0),
            entry(150, baseTs - 5 * 60_000L, direction = "Flat", delta = 0.0),
            entry(200, baseTs, direction = "Flat", delta = 0.0)
        )

        val result = puller.pullHistory(7)

        assertTrue(result.isSuccess)
        val latest = dao.lastN(10).first()
        assertEquals("Flat", latest.direction)
        assertEquals(0.0, latest.delta ?: Double.NaN, 0.0)
    }

    private class FakeClient : NightscoutClient() {
        var entries: List<NightscoutEntryResponse> = emptyList()
        var entriesPages: MutableList<List<NightscoutEntryResponse>>? = null
        var fetchReturnsNull = false

        override suspend fun fetchEntries(
            baseUrl: String,
            apiSecret: String,
            since: Long,
            count: Int,
            before: Long?
        ): List<NightscoutEntryResponse>? {
            if (fetchReturnsNull) return null
            val pages = entriesPages
            if (pages != null && pages.isNotEmpty()) {
                return pages.removeFirst()
            }
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
