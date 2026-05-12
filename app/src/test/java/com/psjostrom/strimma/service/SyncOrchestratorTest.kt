package com.psjostrom.strimma.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.RetentionPolicy
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.data.Treatment
import com.psjostrom.strimma.data.health.ExerciseSyncer
import com.psjostrom.strimma.data.health.StoredExerciseSession
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
            pusher, tidepoolUploader,
            db.readingDao(), db.treatmentDao(), db.exerciseDao(),
            settings,
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
     * Pins the default-retention contract. INDEFINITE is the default policy; the
     * Story view depends on it to scroll arbitrarily far back. The seeded data
     * is **older than the longest finite policy** ([RetentionPolicy.FIVE_YEARS]
     * = 1825 days), so a regression that flipped the default to any finite
     * value would still prune it — only `INDEFINITE` survives.
     *
     * Background loops launched from start() run on Dispatchers.Unconfined and
     * execute their first body synchronously up to the first suspend; the
     * `runCurrent()` below drains any continuation that picks up after the
     * DataStore read so the assertion isn't racing the prune.
     */
    @Test
    fun `start with default retention keeps data older than the longest finite policy`() = runTest {
        // 6 years — older than FIVE_YEARS (1825 days) so any finite policy would prune it.
        val ancient = System.currentTimeMillis() - 6L * 365 * 24 * 60 * 60 * 1000
        seedAncient(ancient)

        orchestrator.start()
        runCurrent()

        assertEquals(1, db.readingDao().since(0).size)
        assertEquals(1, db.treatmentDao().allSince(0).size)
        assertEquals(1, db.exerciseDao().getSessionsInRange(0, Long.MAX_VALUE).size)
    }

    /**
     * Pins the bounded-retention contract. When the user opts in to a finite
     * window, the orchestrator's retention loop must apply it uniformly to all
     * three tables — readings, treatments, exercise sessions — so the user has
     * one mental model ("Strimma keeps my data for X") instead of per-table
     * surprises.
     */
    @Test
    fun `start with bounded retention prunes ancient data across all three tables`() = runTest {
        settings.setRetentionPolicy(RetentionPolicy.THREE_MONTHS)

        val ancient = System.currentTimeMillis() - 1000L * 24 * 60 * 60 * 1000 // ~3 years ago
        seedAncient(ancient)

        orchestrator.start()
        runCurrent()

        assertEquals(0, db.readingDao().since(0).size)
        assertEquals(0, db.treatmentDao().allSince(0).size)
        assertEquals(0, db.exerciseDao().getSessionsInRange(0, Long.MAX_VALUE).size)
    }

    /**
     * Pins the unconditional unpushed-cleanup contract. Even with INDEFINITE
     * retention, `pushed = 0` rows older than 30 days are dropped on every
     * retention tick — bounds the worst case where a poison row at the head
     * of `unpushed()` keeps batch-failing and starves all newer pushes.
     */
    @Test
    fun `start always prunes stale unpushed rows even with INDEFINITE retention`() = runTest {
        val now = System.currentTimeMillis()
        val staleUnpushed = now - 35L * 24 * 60 * 60 * 1000 // 35 days old, never pushed
        val freshUnpushed = now - 60_000L // 1 minute old, never pushed
        db.readingDao().insert(
            GlucoseReading(ts = staleUnpushed, sgv = 100, direction = "Flat", delta = 0.0, pushed = 0)
        )
        db.readingDao().insert(
            GlucoseReading(ts = freshUnpushed, sgv = 110, direction = "Flat", delta = 0.0, pushed = 0)
        )

        orchestrator.start()
        runCurrent()

        // Stale unpushed gone, fresh unpushed kept — even though the user
        // never opted into a finite retention policy.
        val survivors = db.readingDao().since(0)
        assertEquals(1, survivors.size)
        assertEquals(110, survivors[0].sgv)
    }

    private suspend fun seedAncient(ts: Long) {
        db.readingDao().insert(
            GlucoseReading(ts = ts, sgv = 120, direction = "Flat", delta = 0.0, pushed = 1)
        )
        db.treatmentDao().upsert(listOf(
            Treatment(
                id = "ancient", createdAt = ts, eventType = "Bolus",
                insulin = 1.0, carbs = null, basalRate = null, duration = null,
                enteredBy = "test", fetchedAt = ts
            )
        ))
        db.exerciseDao().upsertSession(
            StoredExerciseSession(
                id = "ancient-session",
                type = 8, // RUNNING
                startTime = ts,
                endTime = ts + 3_600_000L,
                title = "Ancient run",
                totalSteps = 6000,
                activeCalories = 350.0
            )
        )
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
