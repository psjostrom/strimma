package com.psjostrom.strimma.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.data.Treatment
import com.psjostrom.strimma.data.health.ExerciseSyncer
import com.psjostrom.strimma.data.health.HealthConnectManager
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import com.psjostrom.strimma.network.NightscoutClient
import com.psjostrom.strimma.network.NightscoutEntryResponse
import com.psjostrom.strimma.network.NightscoutPuller
import com.psjostrom.strimma.network.NightscoutPusher
import com.psjostrom.strimma.network.TreatmentSyncer
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.testutil.workout.FakeCalendarPoller
import com.psjostrom.strimma.testutil.workout.MutableClock
import com.psjostrom.strimma.tidepool.TidepoolAuthManager
import com.psjostrom.strimma.tidepool.TidepoolClient
import com.psjostrom.strimma.tidepool.TidepoolUploader
import com.psjostrom.strimma.update.UpdateChecker
import com.psjostrom.strimma.webserver.LocalWebServer
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [SyncOrchestrator] lifecycle: idempotent `start()`, scope rebuild
 * across `stop()` → `start()` cycles, and safety of `stop()` without a prior
 * `start()`. The full collaborator graph is constructed because the project's
 * testing rules require real internal modules — only the network boundary is
 * stubbed via [SilentNightscoutClient], which always returns "no data" so the
 * launched coroutines exit cleanly without any real HTTP calls.
 */
@RunWith(RobolectricTestRunner::class)
class SyncOrchestratorTest {

    private lateinit var db: StrimmaDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var managerScope: CoroutineScope
    private lateinit var orchestrator: SyncOrchestrator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
        // Cancelled in @After so eager tickers (AlertManager, WorkoutModeManager) don't leak.
        managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val poller = FakeCalendarPoller()
        val clock = MutableClock(System.currentTimeMillis())
        val workoutModeManager = WorkoutModeManager(settings, poller, clock, managerScope)
        val alertManager = AlertManager(context, settings, workoutModeManager, managerScope)
        val nsClient = SilentNightscoutClient()
        val healthConnect = HealthConnectManager(context)

        val pusher = NightscoutPusher(
            nsClient, db.readingDao(), settings, alertManager, Dispatchers.Unconfined
        )
        val tidepoolUploader = TidepoolUploader(
            context, TidepoolClient(), TidepoolAuthManager(context, settings), db.readingDao(), settings
        )
        val treatmentSyncer = TreatmentSyncer(nsClient, db.treatmentDao(), settings)
        val localWebServer = LocalWebServer(db.readingDao(), db.treatmentDao(), settings, workoutModeManager)
        val exerciseSyncer = ExerciseSyncer(healthConnect, db.exerciseDao(), settings)
        val nightscoutPuller = NightscoutPuller(nsClient, db.readingDao(), settings)
        val updateChecker = UpdateChecker()

        orchestrator = SyncOrchestrator(
            pusher, tidepoolUploader, db.readingDao(), settings,
            treatmentSyncer, localWebServer, exerciseSyncer,
            nightscoutPuller, updateChecker, Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        orchestrator.stop()
        managerScope.cancel()
        db.close()
    }

    @Test
    fun `start sets started flag`() {
        assertFalse(orchestrator.started)
        orchestrator.start()
        assertTrue(orchestrator.started)
    }

    @Test
    fun `start is idempotent — second call does not throw or change state`() {
        orchestrator.start()
        assertTrue(orchestrator.started)
        // A stray double-call must not duplicate the periodic loops or stack
        // two DataStore collectors. The guard inside start() makes it a no-op.
        orchestrator.start()
        assertTrue(orchestrator.started)
    }

    @Test
    fun `stop clears started flag`() {
        orchestrator.start()
        orchestrator.stop()
        assertFalse(orchestrator.started)
    }

    @Test
    fun `stop then start works — scope is rebuilt for re-entry`() {
        orchestrator.start()
        orchestrator.stop()
        assertFalse(orchestrator.started)
        // After stop(), the scope was cancelled and rebuilt. A fresh start()
        // must succeed — the singleton survives service teardown and is reused
        // when StrimmaService is re-created (e.g. via START_STICKY).
        orchestrator.start()
        assertTrue(orchestrator.started)
    }

    @Test
    fun `stop without start is safe`() {
        assertFalse(orchestrator.started)
        orchestrator.stop()
        assertFalse(orchestrator.started)
    }

    /**
     * NightscoutClient subclass that returns null/empty for every call. Keeps the
     * test's launched coroutines from making any real HTTP requests when they
     * read (blank) settings and find nothing to do.
     */
    private class SilentNightscoutClient : NightscoutClient() {
        override suspend fun fetchEntries(
            baseUrl: String,
            apiSecret: String,
            since: Long,
            count: Int,
            before: Long?,
        ): List<NightscoutEntryResponse>? = null

        override suspend fun pushReadings(
            baseUrl: String,
            apiSecret: String,
            readings: List<GlucoseReading>,
        ): Boolean = false

        override suspend fun fetchTreatments(
            baseUrl: String,
            secret: String,
            since: Long,
            count: Int,
        ): List<Treatment> = emptyList()
    }
}
