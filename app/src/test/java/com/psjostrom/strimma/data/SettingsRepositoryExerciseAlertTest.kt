package com.psjostrom.strimma.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.workout.AlertProtocol
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryExerciseAlertTest {

    private fun kotlinx.coroutines.test.TestScope.makeFixture(): SettingsRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore(this))
    }

    private fun kotlinx.coroutines.test.TestScope.makeMigratedFixture(
        legacyPreferences: Preferences,
    ): Pair<SettingsRepository, DataStore<Preferences>> {
        val seedLegacyPreferences = object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences) = true
            override suspend fun migrate(currentData: Preferences) = legacyPreferences
            override suspend fun cleanUp() = Unit
        }
        val dataStore = createTestDataStore(
            this,
            migrations = listOf(seedLegacyPreferences, ExerciseAlertSettingsMigration),
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        return SettingsRepository(context, WidgetSettingsRepository(context), dataStore) to dataStore
    }

    @Test
    fun `exercise thresholds preserve current workout defaults`() = runTest {
        val settings = makeFixture()

        assertEquals(108f, settings.exerciseAlertLow.first())
        assertEquals(90f, settings.exerciseAlertUrgentLow.first())
        assertEquals(252f, settings.exerciseAlertHigh.first())
        assertEquals(288f, settings.exerciseAlertUrgentHigh.first())
    }

    @Test
    fun `exercise enablement persists independently`() = runTest {
        val settings = makeFixture()

        settings.setAlertLowEnabled(true)
        settings.setExerciseAlertLowEnabled(false)

        assertTrue(settings.alertLowEnabled.first())
        assertFalse(settings.exerciseAlertLowEnabled.first())
    }

    @Test
    fun `export and import preserve exercise protocol`() = runTest {
        val source = makeFixture()
        source.setExerciseAlertLowEnabled(false)
        source.setExerciseAlertHighEnabled(false)
        source.setExerciseAlertUrgentLowEnabled(false)
        source.setExerciseAlertUrgentHighEnabled(false)
        source.setExerciseAlertLowSoonEnabled(false)
        source.setExerciseAlertHighSoonEnabled(false)
        source.setExerciseAlertStaleEnabled(false)
        source.setExerciseAlertLow(120f)
        source.setExerciseAlertHigh(260f)
        source.setExerciseAlertUrgentLow(100f)
        source.setExerciseAlertUrgentHigh(300f)

        val json = source.exportToJson()
        val root = JSONObject(json)
        val exportedSettings = root.getJSONObject("settings")
        assertEquals(3, root.getInt("version"))
        assertFalse(exportedSettings.getBoolean("exercise_alert_low_enabled"))
        assertFalse(exportedSettings.getBoolean("exercise_alert_high_enabled"))
        assertFalse(exportedSettings.getBoolean("exercise_alert_urgent_low_enabled"))
        assertFalse(exportedSettings.getBoolean("exercise_alert_urgent_high_enabled"))
        assertFalse(exportedSettings.getBoolean("exercise_alert_low_soon_enabled"))
        assertFalse(exportedSettings.getBoolean("exercise_alert_high_soon_enabled"))
        assertFalse(exportedSettings.getBoolean("exercise_alert_stale_enabled"))
        assertEquals(120.0, exportedSettings.getDouble("exercise_alert_low"), 0.0)
        assertEquals(260.0, exportedSettings.getDouble("exercise_alert_high"), 0.0)
        assertEquals(100.0, exportedSettings.getDouble("exercise_alert_urgent_low"), 0.0)
        assertEquals(300.0, exportedSettings.getDouble("exercise_alert_urgent_high"), 0.0)

        val target = makeFixture()
        target.importFromJson(json)

        assertEquals(source.exerciseAlertProtocol.first(), target.exerciseAlertProtocol.first())
    }

    @Test
    fun `v2 import copies regular enablement into exercise protocol`() = runTest {
        val settings = makeFixture()
        settings.setExerciseAlertLowEnabled(true)
        settings.setExerciseAlertLow(120f)

        settings.importFromJson(
            JSONObject()
                .put("version", 2)
                .put("settings", JSONObject().put("alert_low_enabled", false))
                .toString()
        )

        assertFalse(settings.alertLowEnabled.first())
        assertFalse(settings.exerciseAlertLowEnabled.first())
        assertEquals(120f, settings.exerciseAlertLow.first())
    }

    @Test
    fun `import with exercise fields writes the initialization marker`() = runTest {
        val dataStore = createTestDataStore(this)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context, WidgetSettingsRepository(context), dataStore)
        settings.setExerciseAlertLowEnabled(false)
        settings.setExerciseAlertLow(120f)

        settings.importFromJson(
            JSONObject()
                .put("version", 3)
                .put("settings", JSONObject()
                    .put("exercise_alert_low_enabled", false)
                    .put("exercise_alert_low", 120.0))
                .toString()
        )

        assertFalse(ExerciseAlertSettingsMigration.shouldMigrate(dataStore.data.first()))
        assertFalse(settings.exerciseAlertLowEnabled.first())
        assertEquals(120f, settings.exerciseAlertLow.first())
    }

    @Test
    fun `import rejects unordered exercise thresholds without changing protocol`() = runTest {
        val settings = makeFixture()
        val before = settings.exerciseAlertProtocol.first()

        val result = runCatching {
            settings.importFromJson(
                JSONObject()
                    .put("version", 3)
                    .put(
                        "settings",
                        JSONObject()
                            .put("exercise_alert_low_enabled", false)
                            .put("exercise_alert_low", 260.0)
                            .put("exercise_alert_high", 200.0),
                    )
                    .toString()
            )
        }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, settings.exerciseAlertProtocol.first())
    }

    @Test
    fun `v1 threshold import still converts mmol to mgdl`() = runTest {
        val settings = makeFixture()

        settings.importFromJson(
            JSONObject()
                .put("version", 1)
                .put("settings", JSONObject().put("alert_low", 6.0))
                .toString()
        )

        assertEquals(108f, settings.alertLow.first())
    }

    @Test
    fun `regular and exercise protocol flows expose coherent snapshots`() = runTest {
        val settings = makeFixture()
        settings.setAlertLowEnabled(false)
        settings.setExerciseAlertLowEnabled(true)
        settings.setExerciseAlertLow(120f)

        assertEquals(
            AlertProtocol(
                urgentLowEnabled = true,
                lowEnabled = false,
                highEnabled = true,
                urgentHighEnabled = true,
                urgentLowMgdl = 54f,
                lowMgdl = 72f,
                highMgdl = 180f,
                urgentHighMgdl = 234f,
                lowSoonEnabled = true,
                highSoonEnabled = true,
                staleEnabled = true,
            ),
            settings.regularAlertProtocol.first()
        )
        assertEquals(
            AlertProtocol(
                urgentLowEnabled = true,
                lowEnabled = true,
                highEnabled = true,
                urgentHighEnabled = true,
                urgentLowMgdl = 90f,
                lowMgdl = 120f,
                highMgdl = 252f,
                urgentHighMgdl = 288f,
                lowSoonEnabled = true,
                highSoonEnabled = true,
                staleEnabled = true,
            ),
            settings.exerciseAlertProtocol.first()
        )
    }

    @Test
    fun `exercise settings migration copies regular enablement and marks initialization`() = runTest {
        val (settings, dataStore) = makeMigratedFixture(
            preferencesOf(
                booleanPreferencesKey("alert_low_enabled") to false,
                booleanPreferencesKey("alert_high_enabled") to false,
                booleanPreferencesKey("alert_urgent_low_enabled") to false,
                booleanPreferencesKey("alert_urgent_high_enabled") to false,
                booleanPreferencesKey("alert_low_soon_enabled") to false,
                booleanPreferencesKey("alert_high_soon_enabled") to false,
                booleanPreferencesKey("alert_stale_enabled") to false,
            )
        )
        val protocol = settings.exerciseAlertProtocol.first()

        assertFalse(protocol.lowEnabled)
        assertFalse(protocol.highEnabled)
        assertFalse(protocol.urgentLowEnabled)
        assertFalse(protocol.urgentHighEnabled)
        assertFalse(protocol.lowSoonEnabled)
        assertFalse(protocol.highSoonEnabled)
        assertFalse(protocol.staleEnabled)
        assertFalse(ExerciseAlertSettingsMigration.shouldMigrate(dataStore.data.first()))
    }

    @Test
    fun `exercise settings migration uses regular defaults when values are absent`() = runTest {
        val (settings, dataStore) = makeMigratedFixture(preferencesOf())
        val protocol = settings.exerciseAlertProtocol.first()

        assertTrue(protocol.lowEnabled)
        assertTrue(protocol.highEnabled)
        assertTrue(protocol.urgentLowEnabled)
        assertTrue(protocol.urgentHighEnabled)
        assertTrue(protocol.lowSoonEnabled)
        assertTrue(protocol.highSoonEnabled)
        assertTrue(protocol.staleEnabled)
        assertFalse(ExerciseAlertSettingsMigration.shouldMigrate(dataStore.data.first()))
    }

    @Test
    fun `exercise settings migration does not overwrite initialized exercise values`() = runTest {
        val marker = booleanPreferencesKey("exercise_alerts_initialized")
        val exerciseLow = booleanPreferencesKey("exercise_alert_low_enabled")
        val (settings, dataStore) = makeMigratedFixture(
            preferencesOf(
                booleanPreferencesKey("alert_low_enabled") to false,
                exerciseLow to true,
                marker to true,
            )
        )
        val protocol = settings.exerciseAlertProtocol.first()

        assertTrue(protocol.lowEnabled)
        assertFalse(ExerciseAlertSettingsMigration.shouldMigrate(dataStore.data.first()))
    }
}
