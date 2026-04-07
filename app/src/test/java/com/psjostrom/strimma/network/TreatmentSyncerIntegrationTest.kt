package com.psjostrom.strimma.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.data.Treatment
import com.psjostrom.strimma.data.TreatmentDao
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TreatmentSyncerIntegrationTest {

    private val now = 1_700_000_000_000L
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun treatment(id: String, createdAt: Long, insulin: Double? = null, carbs: Double? = null) =
        Treatment(
            id = id, createdAt = createdAt, eventType = "Bolus",
            insulin = insulin, carbs = carbs, basalRate = null,
            duration = null, enteredBy = "test", fetchedAt = now
        )

    private fun TestScope.advanceAndSettle() {
        repeat(5) {
            advanceUntilIdle()
            Thread.sleep(20)
        }
    }

    private data class Env(
        val db: StrimmaDatabase,
        val treatmentDao: TreatmentDao,
        val settings: SettingsRepository,
        val fakeClient: FakeClient
    )

    private fun TestScope.createEnv(): Env {
        val db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return Env(
            db = db,
            treatmentDao = db.treatmentDao(),
            settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore(this)),
            fakeClient = FakeClient()
        )
    }

    @Test
    fun `start performs full sync when no prior fetch`() = runTest {
        val env = createEnv()
        env.settings.setNightscoutUrl("https://ns.example.com")
        env.settings.setNightscoutSecret("secret")
        env.fakeClient.treatments = listOf(
            treatment("t1", now - 60_000, insulin = 2.0),
            treatment("t2", now - 30_000, carbs = 15.0)
        )

        val syncer = TreatmentSyncer(env.fakeClient, env.treatmentDao, env.settings)
        val job = syncer.start(this)
        advanceAndSettle()

        assertEquals(2, env.treatmentDao.allSince(0).size)

        job.cancel()
        env.db.close()
    }

    @Test
    fun `start inserts treatments and prunes old ones`() = runTest {
        val env = createEnv()
        env.settings.setNightscoutUrl("https://ns.example.com")
        env.settings.setNightscoutSecret("secret")

        val oldTs = now - 31L * 24 * 60 * 60 * 1000
        env.treatmentDao.upsert(listOf(treatment("old", oldTs, insulin = 1.0)))
        env.fakeClient.treatments = listOf(treatment("new", now - 60_000, insulin = 2.0))

        val syncer = TreatmentSyncer(env.fakeClient, env.treatmentDao, env.settings)
        val job = syncer.start(this)
        advanceAndSettle()

        val stored = env.treatmentDao.allSince(0)
        assertEquals("Old treatment should be pruned", 1, stored.size)
        assertEquals("new", stored[0].id)

        job.cancel()
        env.db.close()
    }

    @Test
    fun `start skips sync when NS not configured`() = runTest {
        val env = createEnv()
        // URL blank by default — not configured
        env.fakeClient.treatments = listOf(treatment("t1", now, insulin = 1.0))

        val syncer = TreatmentSyncer(env.fakeClient, env.treatmentDao, env.settings)
        val job = syncer.start(this)
        advanceAndSettle()

        assertTrue("DB should remain empty", env.treatmentDao.allSince(0).isEmpty())

        job.cancel()
        env.db.close()
    }

    @Test
    fun `start updates status to Connected on success`() = runTest {
        val env = createEnv()
        env.settings.setNightscoutUrl("https://ns.example.com")
        env.settings.setNightscoutSecret("secret")
        env.fakeClient.treatments = listOf(treatment("t1", now, insulin = 1.0))

        val syncer = TreatmentSyncer(env.fakeClient, env.treatmentDao, env.settings)
        val job = syncer.start(this)
        advanceAndSettle()

        assertTrue(syncer.status.value is IntegrationStatus.Connected)

        job.cancel()
        env.db.close()
    }

    @Test
    fun `start updates status to Error on fetch failure`() = runTest {
        val env = createEnv()
        env.settings.setNightscoutUrl("https://ns.example.com")
        env.settings.setNightscoutSecret("secret")
        env.fakeClient.throwOnFetch = true

        val syncer = TreatmentSyncer(env.fakeClient, env.treatmentDao, env.settings)
        val job = syncer.start(this)
        advanceAndSettle()

        assertTrue(syncer.status.value is IntegrationStatus.Error)

        job.cancel()
        env.db.close()
    }

    @Test
    fun `pullHistory inserts treatments`() = runTest {
        val env = createEnv()
        env.settings.setNightscoutUrl("https://ns.example.com")
        env.settings.setNightscoutSecret("secret")
        env.fakeClient.treatments = listOf(
            treatment("t1", now - 60_000, insulin = 2.0),
            treatment("t2", now - 30_000, carbs = 20.0)
        )

        val syncer = TreatmentSyncer(env.fakeClient, env.treatmentDao, env.settings)
        val result = syncer.pullHistory(7)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow())
        assertEquals(2, env.treatmentDao.allSince(0).size)

        env.db.close()
    }

    @Test
    fun `pullHistory returns failure when not configured`() = runTest {
        val env = createEnv()

        val syncer = TreatmentSyncer(env.fakeClient, env.treatmentDao, env.settings)
        val result = syncer.pullHistory(7)

        assertTrue(result.isFailure)

        env.db.close()
    }

    private class FakeClient : NightscoutClient() {
        var treatments: List<Treatment> = emptyList()
        var throwOnFetch = false

        override suspend fun fetchTreatments(
            baseUrl: String,
            secret: String,
            since: Long,
            count: Int
        ): List<Treatment> {
            if (throwOnFetch) throw java.io.IOException("Connection failed")
            return treatments
        }

        override suspend fun pushReadings(
            baseUrl: String,
            apiSecret: String,
            readings: List<GlucoseReading>
        ): Boolean = true

        override suspend fun fetchEntries(
            baseUrl: String,
            apiSecret: String,
            since: Long,
            count: Int,
            before: Long?
        ): List<NightscoutEntryResponse>? = emptyList()
    }
}
