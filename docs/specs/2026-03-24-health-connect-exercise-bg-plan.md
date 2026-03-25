# Health Connect + Exercise-BG Context — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Health Connect to read exercise sessions and write glucose readings, then compute and display BG arc analysis for each exercise.

**Architecture:** Data layer (Room entities + HC manager + syncer) → computation layer (ExerciseBGAnalyzer) → UI layer (graph bands, bottom sheet, history screen, settings). Each layer is independently testable. Follows existing patterns: `TreatmentSyncer` for polling, `IOBComputer` for pure computation, `SettingsScreen` for drill-down settings.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Hilt, Health Connect SDK (`androidx.health.connect:connect-client`), Coroutines/Flow, JUnit 4

**Spec:** `docs/specs/2026-03-24-health-connect-exercise-bg-design.md`

---

## File Structure

**New files:**
| File | Responsibility |
|------|---------------|
| `data/health/StoredExerciseSession.kt` | Room entity for exercise sessions |
| `data/health/HeartRateSample.kt` | Room entity for HR samples |
| `data/health/ExerciseDao.kt` | Room DAO for exercise queries |
| `data/health/ExerciseCategory.kt` | Enum mapping HC types → emoji/label |
| `data/health/ExerciseBGAnalyzer.kt` | Pure BG arc computation |
| `data/health/HealthConnectManager.kt` | HC SDK wrapper (singleton) |
| `data/health/ExerciseSyncer.kt` | Background HC poller |
| `ui/ExerciseDetailSheet.kt` | Bottom sheet with BG context |
| `ui/ExerciseHistoryScreen.kt` | Card list of exercise sessions |
| `ui/settings/ExerciseSettings.kt` | HC connection + toggle settings |
| `ui/HealthConnectPermissionRationale.kt` | HC-required rationale activity |
| Tests for all above |

**Modified files:**
| File | Change |
|------|--------|
| `app/build.gradle.kts` | Add HC dependency |
| `AndroidManifest.xml` | Permissions, queries, rationale activity |
| `data/StrimmaDatabase.kt` | New entities, auto-migration v3→v4 |
| `data/ReadingDao.kt` | Add `readingsInRange()` query |
| `di/AppModule.kt` | ExerciseDao provider |
| `ui/theme/Color.kt` | Exercise DEFAULT color |
| `graph/GraphColors.kt` | Canvas constant for exercise band |
| `service/StrimmaService.kt` | Inject ExerciseSyncer + BG write |
| `ui/MainScreen.kt` | Exercise bands on graph + header icon |
| `ui/MainViewModel.kt` | Exercise data flows |
| `ui/SettingsScreen.kt` | Exercise menu item |
| `ui/MainActivity.kt` | New nav routes |
| `notification/GraphRenderer.kt` | Exercise bands on notification graph |
| `res/values/strings.xml` + 4 translations | New strings |

---

## Task 1: Project setup — dependency + manifest + database

**Files:**
- Modify: `app/build.gradle.kts:122-172` (dependencies block)
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/psjostrom/strimma/data/health/StoredExerciseSession.kt`
- Create: `app/src/main/java/com/psjostrom/strimma/data/health/HeartRateSample.kt`
- Create: `app/src/main/java/com/psjostrom/strimma/data/health/ExerciseDao.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/data/StrimmaDatabase.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/di/AppModule.kt`
- Test: `app/src/test/java/com/psjostrom/strimma/data/health/ExerciseDaoTest.kt`

- [ ] **Step 1: Add Health Connect dependency**

In `app/build.gradle.kts`, add to the dependencies block:

```kotlin
implementation("androidx.health.connect:connect-client:1.1.0-alpha10")
```

Check Maven Central for latest version at implementation time. Sync Gradle.

- [ ] **Step 2: Add manifest permissions and queries**

In `AndroidManifest.xml`, add permissions before `<application>`:

```xml
<!-- Health Connect -->
<uses-permission android:name="android.permission.health.READ_EXERCISE" />
<uses-permission android:name="android.permission.health.READ_HEART_RATE" />
<uses-permission android:name="android.permission.health.READ_STEPS" />
<uses-permission android:name="android.permission.health.READ_ACTIVE_CALORIES_BURNED" />
<uses-permission android:name="android.permission.health.WRITE_BLOOD_GLUCOSE" />
```

Add `<queries>` block after `</application>` closing tag but before `</manifest>`:

```xml
<queries>
    <package android:name="com.google.android.apps.healthdata" />
</queries>
```

Add rationale activity inside `<application>`:

```xml
<activity
    android:name=".ui.HealthConnectPermissionRationale"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
    </intent-filter>
</activity>
```

- [ ] **Step 3: Create HealthConnectPermissionRationale activity**

Create `app/src/main/java/com/psjostrom/strimma/ui/HealthConnectPermissionRationale.kt`:

```kotlin
package com.psjostrom.strimma.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import com.psjostrom.strimma.ui.theme.ThemeMode

class HealthConnectPermissionRationale : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StrimmaTheme(themeMode = ThemeMode.System) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.hc_rationale_title),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.hc_rationale_body))
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { finish() }) {
                            Text(stringResource(R.string.common_ok))
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Create Room entities**

Create `app/src/main/java/com/psjostrom/strimma/data/health/StoredExerciseSession.kt`:

```kotlin
package com.psjostrom.strimma.data.health

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exercise_sessions")
data class StoredExerciseSession(
    @PrimaryKey val id: String,
    val type: Int,
    val startTime: Long,
    val endTime: Long,
    val title: String?,
    val totalSteps: Int?,
    val activeCalories: Double?
)
```

Create `app/src/main/java/com/psjostrom/strimma/data/health/HeartRateSample.kt`:

```kotlin
package com.psjostrom.strimma.data.health

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "heart_rate_samples",
    foreignKeys = [ForeignKey(
        entity = StoredExerciseSession::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class HeartRateSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val time: Long,
    val bpm: Int
)
```

- [ ] **Step 5: Create ExerciseDao**

Create `app/src/main/java/com/psjostrom/strimma/data/health/ExerciseDao.kt`:

```kotlin
package com.psjostrom.strimma.data.health

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercise_sessions WHERE startTime >= :start AND startTime <= :end ORDER BY startTime DESC")
    suspend fun getSessionsInRange(start: Long, end: Long): List<StoredExerciseSession>

    @Query("SELECT * FROM exercise_sessions WHERE id = :id")
    suspend fun getSessionById(id: String): StoredExerciseSession?

    @Query("SELECT * FROM heart_rate_samples WHERE sessionId = :sessionId ORDER BY time ASC")
    suspend fun getHeartRateForSession(sessionId: String): List<HeartRateSample>

    @Query("SELECT * FROM exercise_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<StoredExerciseSession>>

    @Upsert
    suspend fun upsertSession(session: StoredExerciseSession)

    @Query("DELETE FROM heart_rate_samples WHERE sessionId = :sessionId")
    suspend fun deleteHeartRateForSession(sessionId: String)

    @Insert
    suspend fun insertHeartRateSamples(samples: List<HeartRateSample>)

    @Query("DELETE FROM exercise_sessions WHERE startTime < :cutoff")
    suspend fun deleteSessionsOlderThan(cutoff: Long)

    @Transaction
    suspend fun upsertSessionWithHeartRate(
        session: StoredExerciseSession,
        heartRateSamples: List<HeartRateSample>
    ) {
        deleteHeartRateForSession(session.id)
        upsertSession(session)
        if (heartRateSamples.isNotEmpty()) {
            insertHeartRateSamples(heartRateSamples)
        }
    }
}
```

- [ ] **Step 6: Update StrimmaDatabase**

Modify `app/src/main/java/com/psjostrom/strimma/data/StrimmaDatabase.kt`:

```kotlin
package com.psjostrom.strimma.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.data.health.HeartRateSample
import com.psjostrom.strimma.data.health.StoredExerciseSession

@Database(
    entities = [GlucoseReading::class, Treatment::class, StoredExerciseSession::class, HeartRateSample::class],
    version = 4,
    autoMigrations = [AutoMigration(from = 3, to = 4)]
)
abstract class StrimmaDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao
    abstract fun treatmentDao(): TreatmentDao
    abstract fun exerciseDao(): ExerciseDao

    companion object {
        const val DB_NAME = "strimma.db"

        @Volatile
        private var INSTANCE: StrimmaDatabase? = null

        fun getInstance(context: Context): StrimmaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StrimmaDatabase::class.java,
                    DB_NAME
                ).build().also { INSTANCE = it }
            }
        }
    }
}
```

- [ ] **Step 7: Update AppModule**

Add to `app/src/main/java/com/psjostrom/strimma/di/AppModule.kt`:

```kotlin
@Provides
fun provideExerciseDao(db: StrimmaDatabase): ExerciseDao = db.exerciseDao()
```

- [ ] **Step 7b: Add readingsInRange query to ReadingDao**

Add to `app/src/main/java/com/psjostrom/strimma/data/ReadingDao.kt`:

```kotlin
@Query("SELECT * FROM readings WHERE ts >= :start AND ts <= :end ORDER BY ts ASC")
suspend fun readingsInRange(start: Long, end: Long): List<GlucoseReading>
```

This is needed by `ExerciseBGAnalyzer` to load BG readings for a specific time window (30min pre through 4h post exercise).

- [ ] **Step 7c: Verify schema export is configured**

Check that `app/build.gradle.kts` has the KSP arg for Room schema export:
```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

This is required for `@AutoMigration` to work. It already exists in the current build file (line 49), but verify it's present after your changes.

- [ ] **Step 8: Write ExerciseDao tests**

Create `app/src/test/java/com/psjostrom/strimma/data/health/ExerciseDaoTest.kt`. Use Robolectric + in-memory Room (same pattern as `ReadingDaoTest`). Test:
- Insert and retrieve session by ID
- Get sessions in time range (inclusive boundaries)
- Upsert replaces session data
- `upsertSessionWithHeartRate` replaces HR samples (no duplicates)
- CASCADE delete removes HR when session deleted
- `deleteSessionsOlderThan` prunes correctly
- `getAllSessions()` Flow emits updates

- [ ] **Step 9: Run tests, verify build compiles**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass, build succeeds with new entities and migration.

- [ ] **Step 10: Commit**

```bash
git add -A && git commit -m "Add Room entities, DAO, and database migration for Health Connect exercise data"
```

---

## Task 2: ExerciseCategory enum

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/data/health/ExerciseCategory.kt`

- [ ] **Step 1: Create ExerciseCategory**

```kotlin
package com.psjostrom.strimma.data.health

import com.psjostrom.strimma.R

enum class ExerciseCategory(val emoji: String, val labelRes: Int) {
    RUNNING("🏃", R.string.exercise_type_running),
    WALKING("🚶", R.string.exercise_type_walking),
    CYCLING("🚴", R.string.exercise_type_cycling),
    SWIMMING("🏊", R.string.exercise_type_swimming),
    OTHER("🏋️", R.string.exercise_type_other);

    companion object {
        // HC exercise type constants from ExerciseSessionRecord
        private const val TYPE_RUNNING = 56
        private const val TYPE_RUNNING_TREADMILL = 57
        private const val TYPE_WALKING = 79
        private const val TYPE_BIKING = 8
        private const val TYPE_BIKING_STATIONARY = 9
        private const val TYPE_SWIMMING_OPEN_WATER = 74
        private const val TYPE_SWIMMING_POOL = 75

        fun fromHCType(type: Int): ExerciseCategory = when (type) {
            TYPE_RUNNING, TYPE_RUNNING_TREADMILL -> RUNNING
            TYPE_WALKING -> WALKING
            TYPE_BIKING, TYPE_BIKING_STATIONARY -> CYCLING
            TYPE_SWIMMING_OPEN_WATER, TYPE_SWIMMING_POOL -> SWIMMING
            else -> OTHER
        }
    }
}
```

**Important:** At implementation time, verify these HC type constants match `ExerciseSessionRecord.EXERCISE_TYPE_*` from the HC SDK. Use the SDK constants directly instead of hardcoded ints if possible (depends on whether the SDK is available in test scope).

- [ ] **Step 2: Add string resources**

Add to `res/values/strings.xml`:
```xml
<string name="exercise_type_running">Run</string>
<string name="exercise_type_walking">Walk</string>
<string name="exercise_type_cycling">Ride</string>
<string name="exercise_type_swimming">Swim</string>
<string name="exercise_type_other">Exercise</string>
```

Add equivalent translations to `values-sv`, `values-es`, `values-fr`, `values-de`.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "Add ExerciseCategory enum with HC type mapping"
```

---

## Task 3: Exercise colors

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/graph/GraphColors.kt`

- [ ] **Step 1: Add Compose color**

Add to `Color.kt` after the treatment marker colors:

```kotlin
// --- Exercise marker color ---
val ExerciseDefault = Color(0xFF8B8BBA)
```

- [ ] **Step 2: Add Canvas constant**

Add to `GraphColors.kt` after `CANVAS_CARB`:

```kotlin
// Exercise band color — keep in sync with Color.kt (ExerciseDefault)
const val CANVAS_EXERCISE = 0xFF8B8BBA.toInt()
```

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "Add exercise band color (lavender #8B8BBA)"
```

---

## Task 4: ExerciseBGAnalyzer — pure computation

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/data/health/ExerciseBGAnalyzer.kt`
- Test: `app/src/test/java/com/psjostrom/strimma/data/health/ExerciseBGAnalyzerTest.kt`

- [ ] **Step 1: Write failing tests**

Create `ExerciseBGAnalyzerTest.kt`. Key test cases:

```kotlin
package com.psjostrom.strimma.data.health

import com.psjostrom.strimma.data.GlucoseReading
import org.junit.Assert.*
import org.junit.Test

class ExerciseBGAnalyzerTest {

    private val analyzer = ExerciseBGAnalyzer()
    private val bgLow = 72.0 // mg/dL

    private fun reading(sgv: Int, minutesFromStart: Long, baseTime: Long = 1_000_000_000_000L): GlucoseReading {
        return GlucoseReading(
            ts = baseTime + minutesFromStart * 60_000,
            sgv = sgv,
            direction = "Flat",
            delta = null,
            pushed = 1
        )
    }

    private fun session(
        durationMinutes: Int = 45,
        baseTime: Long = 1_000_000_000_000L
    ): StoredExerciseSession {
        return StoredExerciseSession(
            id = "test-session",
            type = 56, // running
            startTime = baseTime,
            endTime = baseTime + durationMinutes * 60_000L,
            title = null,
            totalSteps = 5000,
            activeCalories = 350.0
        )
    }

    @Test
    fun `normal session with BG drop during exercise`() {
        val base = 1_000_000_000_000L
        val session = session(durationMinutes = 45, baseTime = base)
        // Pre: stable at 140 for 30 min before
        // During: drops from 140 to 100
        // Post: drops to 80 then recovers
        val readings = listOf(
            reading(140, -30, base), reading(140, -20, base), reading(140, -10, base),
            reading(138, 0, base), reading(130, 10, base), reading(120, 20, base),
            reading(110, 30, base), reading(100, 40, base),
            reading(95, 50, base), reading(85, 60, base), reading(80, 90, base),
            reading(85, 120, base), reading(95, 180, base), reading(100, 240, base)
        )
        val result = analyzer.analyze(session, readings, emptyList(), bgLow)
        assertNotNull(result)
        result!!
        assertEquals(140, result.entryBG)  // closest reading to start
        assertEquals(ExerciseBGAnalyzer.Trend.STABLE, result.entryTrend)
        assertEquals(100, result.minBG)    // lowest during exercise
        assertEquals(80, result.lowestBG)  // lowest in 4h post window
        assertFalse(result.postExerciseHypo) // 80 > 72
    }

    @Test
    fun `session with post-exercise hypo`() {
        val base = 1_000_000_000_000L
        val session = session(durationMinutes = 45, baseTime = base)
        val readings = listOf(
            reading(120, -30, base), reading(118, -20, base), reading(115, -10, base),
            reading(110, 0, base), reading(95, 15, base), reading(80, 30, base),
            reading(75, 45, base), reading(65, 60, base), reading(60, 90, base),
            reading(70, 120, base), reading(85, 180, base)
        )
        val result = analyzer.analyze(session, readings, emptyList(), bgLow)
        assertNotNull(result)
        result!!
        assertTrue(result.postExerciseHypo) // 60 < 72
        assertEquals(60, result.lowestBG)
    }

    @Test
    fun `returns null when coverage below 50 percent`() {
        val base = 1_000_000_000_000L
        val session = session(durationMinutes = 60, baseTime = base)
        // Only 2 readings in a 60-min session — way below 50%
        val readings = listOf(
            reading(140, 0, base), reading(130, 30, base)
        )
        val result = analyzer.analyze(session, readings, emptyList(), bgLow)
        assertNull(result)
    }

    @Test
    fun `pre-exercise rising trend detected`() {
        val base = 1_000_000_000_000L
        val session = session(durationMinutes = 30, baseTime = base)
        val readings = listOf(
            reading(100, -30, base), reading(110, -20, base), reading(120, -10, base),
            reading(130, 0, base), reading(125, 5, base), reading(120, 10, base),
            reading(115, 15, base), reading(110, 20, base), reading(108, 25, base),
            reading(105, 30, base),
            reading(100, 45, base), reading(100, 60, base)
        )
        val result = analyzer.analyze(session, readings, emptyList(), bgLow)
        assertNotNull(result)
        assertEquals(ExerciseBGAnalyzer.Trend.RISING, result!!.entryTrend)
    }

    @Test
    fun `very short session under 10 minutes`() {
        val base = 1_000_000_000_000L
        val session = session(durationMinutes = 8, baseTime = base)
        val readings = ((-30..250) step 1).map { min ->
            reading((140 - (min.coerceIn(0, 8) * 2)), min.toLong(), base)
        }
        val result = analyzer.analyze(session, readings, emptyList(), bgLow)
        // Should still produce a result if coverage is adequate
        assertNotNull(result)
    }

    @Test
    fun `HR data aggregated correctly`() {
        val base = 1_000_000_000_000L
        val session = session(durationMinutes = 30, baseTime = base)
        val readings = ((-30..270) step 1).map { min ->
            reading(120, min.toLong(), base)
        }
        val hrSamples = listOf(
            HeartRateSample(sessionId = "test-session", time = base, bpm = 120),
            HeartRateSample(sessionId = "test-session", time = base + 10 * 60_000, bpm = 150),
            HeartRateSample(sessionId = "test-session", time = base + 20 * 60_000, bpm = 160),
            HeartRateSample(sessionId = "test-session", time = base + 30 * 60_000, bpm = 140),
        )
        val result = analyzer.analyze(session, readings, hrSamples, bgLow)
        assertNotNull(result)
        result!!
        assertEquals(142, result.avgHR) // (120+150+160+140)/4 = 142.5 → 142
        assertEquals(160, result.maxHR)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "*.ExerciseBGAnalyzerTest"`
Expected: Compilation error — `ExerciseBGAnalyzer` doesn't exist yet.

- [ ] **Step 3: Implement ExerciseBGAnalyzer**

Create `app/src/main/java/com/psjostrom/strimma/data/health/ExerciseBGAnalyzer.kt`:

```kotlin
package com.psjostrom.strimma.data.health

import com.psjostrom.strimma.data.GlucoseReading
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExerciseBGAnalyzer @Inject constructor() {

    enum class Trend { RISING, FALLING, STABLE }

    data class ExerciseBGContext(
        val entryBG: Int?,
        val entryTrend: Trend?,
        val entryStability: Double?,
        val minBG: Int?,
        val maxDropRate: Double?,
        val dropPer10Min: List<Double>,
        val lowestBG: Int?,
        val lowestBGTime: Instant?,
        val timeToStable: Duration?,
        val postExerciseHypo: Boolean,
        val avgHR: Int?,
        val maxHR: Int?,
        val totalSteps: Int?,
        val activeCalories: Double?,
        val bgCoveragePercent: Double
    )

    companion object {
        private const val PRE_WINDOW_MS = 30 * 60_000L
        private const val POST_WINDOW_MS = 4 * 60 * 60_000L
        private const val DROP_BUCKET_MS = 10 * 60_000L
        private const val MIN_COVERAGE_PERCENT = 50.0
        private const val TREND_THRESHOLD = 1.0  // mg/dL per minute
        private const val STABLE_RATE_THRESHOLD = 0.5  // mg/dL per minute
        private const val STABLE_SUSTAINED_MS = 15 * 60_000L
    }

    fun analyze(
        session: StoredExerciseSession,
        readings: List<GlucoseReading>,
        heartRateSamples: List<HeartRateSample>,
        bgLowMgdl: Double
    ): ExerciseBGContext? {
        val exerciseDurationMs = session.endTime - session.startTime
        val duringReadings = readings.filter { it.ts in session.startTime..session.endTime }

        // Coverage check: infer sensor interval from actual data spacing
        // This works for any sensor (1-min Libre 3, 5-min Dexcom, etc.)
        val sensorIntervalMs = inferSensorInterval(readings)
        val expectedReadings = (exerciseDurationMs.toDouble() / sensorIntervalMs).coerceAtLeast(1.0)
        val coveragePercent = (duringReadings.size / expectedReadings) * 100.0
        if (coveragePercent < MIN_COVERAGE_PERCENT) return null

        // Pre-activity analysis
        val preReadings = readings.filter {
            it.ts in (session.startTime - PRE_WINDOW_MS) until session.startTime
        }.sortedBy { it.ts }
        val entryBG = preReadings.lastOrNull()?.sgv ?: duringReadings.firstOrNull()?.sgv
        val (entryTrend, entryStability) = computeTrend(preReadings)

        // During analysis
        val minBG = duringReadings.minByOrNull { it.sgv }?.sgv
        val dropBuckets = computeDropBuckets(duringReadings, session.startTime, exerciseDurationMs)
        val maxDropRate = dropBuckets.maxOrNull()

        // Post-activity analysis (4h post window only — minBG already covers during)
        val postReadings = readings.filter {
            it.ts in (session.endTime + 1)..(session.endTime + POST_WINDOW_MS)
        }.sortedBy { it.ts }
        val lowestReading = postReadings.minByOrNull { it.sgv }
        val lowestBG = lowestReading?.sgv
        val lowestBGTime = lowestReading?.let { Instant.ofEpochMilli(it.ts) }
        val postExerciseHypo = lowestBG != null && lowestBG < bgLowMgdl

        val timeToStable = computeTimeToStable(postReadings, session.endTime)

        // HR aggregation
        val avgHR = if (heartRateSamples.isNotEmpty()) {
            heartRateSamples.map { it.bpm }.average().toInt()
        } else null
        val maxHR = heartRateSamples.maxByOrNull { it.bpm }?.bpm

        return ExerciseBGContext(
            entryBG = entryBG,
            entryTrend = entryTrend,
            entryStability = entryStability,
            minBG = minBG,
            maxDropRate = maxDropRate,
            dropPer10Min = dropBuckets,
            lowestBG = lowestBG,
            lowestBGTime = lowestBGTime,
            timeToStable = timeToStable,
            postExerciseHypo = postExerciseHypo,
            avgHR = avgHR,
            maxHR = maxHR,
            totalSteps = session.totalSteps,
            activeCalories = session.activeCalories,
            bgCoveragePercent = coveragePercent
        )
    }

    private fun computeTrend(preReadings: List<GlucoseReading>): Pair<Trend?, Double?> {
        if (preReadings.size < 2) return null to null
        // Linear regression: slope in mg/dL per minute
        val n = preReadings.size.toDouble()
        val times = preReadings.map { it.ts.toDouble() / 60_000.0 } // minutes
        val values = preReadings.map { it.sgv.toDouble() }
        val meanT = times.average()
        val meanV = values.average()
        val num = times.zip(values).sumOf { (t, v) -> (t - meanT) * (v - meanV) }
        val den = times.sumOf { t -> (t - meanT) * (t - meanT) }
        if (den == 0.0) return Trend.STABLE to 1.0
        val slope = num / den
        // R²
        val ssRes = times.zip(values).sumOf { (t, v) ->
            val predicted = meanV + slope * (t - meanT)
            (v - predicted) * (v - predicted)
        }
        val ssTot = values.sumOf { v -> (v - meanV) * (v - meanV) }
        val r2 = if (ssTot > 0) 1.0 - ssRes / ssTot else 1.0

        val trend = when {
            slope > TREND_THRESHOLD -> Trend.RISING
            slope < -TREND_THRESHOLD -> Trend.FALLING
            else -> Trend.STABLE
        }
        return trend to r2
    }

    private fun inferSensorInterval(readings: List<GlucoseReading>): Double {
        if (readings.size < 2) return 60_000.0 // default to 1 min
        val sorted = readings.sortedBy { it.ts }
        val intervals = sorted.zipWithNext().map { (a, b) -> b.ts - a.ts }
            .filter { it in 30_000..600_000 } // filter out gaps (30s to 10min)
        return if (intervals.isNotEmpty()) intervals.median() else 60_000.0
    }

    private fun List<Long>.median(): Double {
        val sorted = this.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0
        else sorted[mid].toDouble()
    }

    private fun computeDropBuckets(
        readings: List<GlucoseReading>,
        startTime: Long,
        durationMs: Long
    ): List<Double> {
        if (readings.size < 2) return emptyList()
        val sorted = readings.sortedBy { it.ts }
        val bucketCount = ((durationMs + DROP_BUCKET_MS - 1) / DROP_BUCKET_MS).toInt()
            .coerceAtLeast(1)
        return (0 until bucketCount).map { i ->
            val bucketStart = startTime + i * DROP_BUCKET_MS
            val bucketEnd = bucketStart + DROP_BUCKET_MS
            val bucketReadings = sorted.filter { it.ts in bucketStart until bucketEnd }
            if (bucketReadings.size >= 2) {
                val first = bucketReadings.first().sgv.toDouble()
                val last = bucketReadings.last().sgv.toDouble()
                first - last // positive = drop
            } else 0.0
        }
    }

    private fun computeTimeToStable(
        postReadings: List<GlucoseReading>,
        exerciseEndTime: Long
    ): Duration? {
        if (postReadings.size < 2) return null
        val sorted = postReadings.sortedBy { it.ts }
        // Sliding window: find first point where rate < threshold for STABLE_SUSTAINED_MS
        for (i in sorted.indices) {
            val windowEnd = sorted[i].ts + STABLE_SUSTAINED_MS
            val windowReadings = sorted.filter { it.ts in sorted[i].ts..windowEnd }
            if (windowReadings.size >= 2) {
                val rates = windowReadings.zipWithNext().map { (a, b) ->
                    val dt = (b.ts - a.ts).toDouble() / 60_000.0
                    if (dt > 0) Math.abs((b.sgv - a.sgv).toDouble() / dt) else 0.0
                }
                if (rates.all { it < STABLE_RATE_THRESHOLD }) {
                    return Duration.ofMillis(sorted[i].ts - exerciseEndTime)
                }
            }
        }
        return null
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "*.ExerciseBGAnalyzerTest"`
Expected: All tests pass. Iterate on implementation if needed — the tests define the contract.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add ExerciseBGAnalyzer with BG arc computation and tests"
```

---

## Task 5: HealthConnectManager

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/data/health/HealthConnectManager.kt`

- [ ] **Step 1: Implement HealthConnectManager**

```kotlin
package com.psjostrom.strimma.data.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.receiver.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

enum class HealthConnectStatus { AVAILABLE, NOT_INSTALLED, NOT_SUPPORTED }

@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val permissions = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(BloodGlucoseRecord::class)
    )

    private val client: HealthConnectClient? by lazy {
        if (isAvailable() == HealthConnectStatus.AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else null
    }

    fun isAvailable(): HealthConnectStatus {
        val status = HealthConnectClient.getSdkStatus(context)
        return when (status) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectStatus.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectStatus.NOT_INSTALLED
            else -> HealthConnectStatus.NOT_SUPPORTED
        }
    }

    suspend fun hasPermissions(): Boolean {
        val c = client ?: return false
        val granted = c.permissionController.getGrantedPermissions()
        return permissions.all { it in granted }
    }

    fun createPermissionContract(): ActivityResultContract<Set<String>, Set<String>>? {
        if (isAvailable() != HealthConnectStatus.AVAILABLE) return null
        return HealthConnectClient.getOrCreate(context)
            .permissionController
            .createRequestPermissionResultContract()
    }

    suspend fun getExerciseSessions(since: Instant): List<Pair<StoredExerciseSession, List<HeartRateSample>>> {
        val c = client ?: return emptyList()
        val response = c.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.after(since)
            )
        )
        return response.records.map { record ->
            val hrRecords = c.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        record.startTime, record.endTime
                    )
                )
            )
            val hrSamples = hrRecords.records.flatMap { hrRecord ->
                hrRecord.samples.map { sample ->
                    HeartRateSample(
                        sessionId = record.metadata.id,
                        time = sample.time.toEpochMilli(),
                        bpm = sample.beatsPerMinute.toInt()
                    )
                }
            }

            // Read steps
            val stepsRecords = c.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        record.startTime, record.endTime
                    )
                )
            )
            val totalSteps = stepsRecords.records.sumOf { it.count }.toInt()
                .takeIf { it > 0 }

            // Read calories
            val calRecords = c.readRecords(
                ReadRecordsRequest(
                    recordType = ActiveCaloriesBurnedRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        record.startTime, record.endTime
                    )
                )
            )
            val totalCalories = calRecords.records.sumOf { it.energy.inKilocalories }
                .takeIf { it > 0.0 }

            val session = StoredExerciseSession(
                id = record.metadata.id,
                type = record.exerciseType,
                startTime = record.startTime.toEpochMilli(),
                endTime = record.endTime.toEpochMilli(),
                title = record.title,
                totalSteps = totalSteps,
                activeCalories = totalCalories
            )
            session to hrSamples
        }
    }

    suspend fun writeGlucoseReading(reading: GlucoseReading) {
        val c = client ?: return
        try {
            val record = BloodGlucoseRecord(
                time = Instant.ofEpochMilli(reading.ts),
                zoneOffset = ZoneOffset.systemDefault().rules
                    .getOffset(Instant.ofEpochMilli(reading.ts)),
                level = BloodGlucoseRecord.BloodGlucoseLevel
                    .milligramsPerDeciliter(reading.sgv.toDouble())
            )
            c.insertRecords(listOf(record))
        } catch (e: Exception) {
            DebugLog.log("HC write failed: ${e.message}")
        }
    }

    suspend fun getChangesToken(): String? {
        val c = client ?: return null
        return try {
            c.getChangesToken(
                ChangesTokenRequest(
                    recordTypes = setOf(ExerciseSessionRecord::class)
                )
            )
        } catch (e: Exception) {
            DebugLog.log("HC getChangesToken failed: ${e.message}")
            null
        }
    }

    data class ChangesResult(
        val hasChanges: Boolean,
        val nextToken: String?,
        val tokenExpired: Boolean = false
    )

    suspend fun getChanges(token: String): ChangesResult {
        val c = client ?: return ChangesResult(false, null)
        return try {
            val response = c.getChanges(token)
            val hasExerciseChanges = response.changes.any { it is UpsertionChange }
            ChangesResult(
                hasChanges = hasExerciseChanges,
                nextToken = response.nextChangesToken,
                tokenExpired = false
            )
        } catch (e: Exception) {
            DebugLog.log("HC getChanges failed: ${e.message}")
            // Assume token expired on any error — will trigger full resync
            ChangesResult(false, null, tokenExpired = true)
        }
    }
}
```

**Note:** At implementation time, verify the HC SDK API surface matches — method names, parameter types, and return types may differ from this skeleton. Consult the [HC SDK reference](https://developer.android.com/reference/kotlin/androidx/health/connect/client/HealthConnectClient).

- [ ] **Step 2: Commit**

```bash
git add -A && git commit -m "Add HealthConnectManager wrapping HC SDK"
```

---

## Task 6: ExerciseSyncer

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/data/health/ExerciseSyncer.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/data/SettingsRepository.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/service/StrimmaService.kt`

- [ ] **Step 1: Add settings keys for HC**

Add to `SettingsRepository.kt`:

```kotlin
// Health Connect settings
private val KEY_HC_WRITE_ENABLED = booleanPreferencesKey("hc_write_enabled")
private val KEY_HC_CHANGES_TOKEN = stringPreferencesKey("hc_changes_token")
private val KEY_HC_LAST_SYNC = longPreferencesKey("hc_last_sync")

val hcWriteEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_HC_WRITE_ENABLED] ?: false }
val hcLastSync: Flow<Long> = dataStore.data.map { it[KEY_HC_LAST_SYNC] ?: 0L }

suspend fun setHcWriteEnabled(enabled: Boolean) {
    dataStore.edit { it[KEY_HC_WRITE_ENABLED] = enabled }
}

suspend fun getHcChangesToken(): String? {
    return dataStore.data.first()[KEY_HC_CHANGES_TOKEN]
}

suspend fun setHcChangesToken(token: String?) {
    dataStore.edit {
        if (token != null) it[KEY_HC_CHANGES_TOKEN] = token
        else it.remove(KEY_HC_CHANGES_TOKEN)
    }
}

suspend fun setHcLastSync(timestamp: Long) {
    dataStore.edit { it[KEY_HC_LAST_SYNC] = timestamp }
}
```

Also add the HC settings to `exportSettings()` and `importSettings()` if they exist.

- [ ] **Step 2: Create ExerciseSyncer**

```kotlin
package com.psjostrom.strimma.data.health

import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.receiver.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExerciseSyncer @Inject constructor(
    private val manager: HealthConnectManager,
    private val dao: ExerciseDao,
    private val settings: SettingsRepository
) {
    companion object {
        private const val POLL_INTERVAL_MS = 15 * 60 * 1000L
        private const val FULL_SYNC_DAYS = 30L
        private const val PRUNE_RETENTION_DAYS = 30L
        private const val MS_PER_DAY = 24 * 60 * 60 * 1000L
    }

    fun start(scope: CoroutineScope): Job {
        return scope.launch {
            while (isActive) {
                sync()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun sync() {
        if (manager.isAvailable() != HealthConnectStatus.AVAILABLE) return
        if (!manager.hasPermissions()) return

        try {
            val token = settings.getHcChangesToken()
            if (token == null) {
                fullSync()
            } else {
                val result = manager.getChanges(token)
                when {
                    result.tokenExpired -> {
                        DebugLog.log("HC changes token expired, doing full sync")
                        settings.setHcChangesToken(null)
                        fullSync()
                    }
                    result.hasChanges -> {
                        fullSync()
                        result.nextToken?.let { settings.setHcChangesToken(it) }
                    }
                    else -> {
                        result.nextToken?.let { settings.setHcChangesToken(it) }
                    }
                }
            }
            // Prune old sessions
            val cutoff = System.currentTimeMillis() - PRUNE_RETENTION_DAYS * MS_PER_DAY
            dao.deleteSessionsOlderThan(cutoff)
        } catch (e: SecurityException) {
            DebugLog.log("HC permissions revoked: ${e.message}")
        } catch (e: Exception) {
            DebugLog.log("HC sync failed: ${e.message}")
        }
    }

    private suspend fun fullSync() {
        val since = Instant.ofEpochMilli(
            System.currentTimeMillis() - FULL_SYNC_DAYS * MS_PER_DAY
        )
        val sessions = manager.getExerciseSessions(since)
        for ((session, hrSamples) in sessions) {
            dao.upsertSessionWithHeartRate(session, hrSamples)
        }
        // Get a fresh token for next delta check
        manager.getChangesToken()?.let { settings.setHcChangesToken(it) }
        settings.setHcLastSync(System.currentTimeMillis())
        DebugLog.log("HC synced ${sessions.size} exercise sessions")
    }
}
```

- [ ] **Step 3: Integrate into StrimmaService**

Add to `StrimmaService.kt`:

Inject:
```kotlin
@Inject lateinit var exerciseSyncer: ExerciseSyncer
@Inject lateinit var healthConnectManager: HealthConnectManager
```

Add field:
```kotlin
private var exerciseSyncJob: Job? = null
```

In `onCreate()`, after the treatment sync lifecycle block, add:
```kotlin
// Exercise sync lifecycle — always runs if HC is available
exerciseSyncJob = exerciseSyncer.start(scope)
```

In `processReading()`, after `broadcastBgIfEnabled(reading)`, add:
```kotlin
writeToHealthConnectIfEnabled(reading)
```

Add method:
```kotlin
private fun writeToHealthConnectIfEnabled(reading: GlucoseReading) {
    if (!hcWriteEnabled.value) return
    scope.launch {
        if (healthConnectManager.hasPermissions()) {
            healthConnectManager.writeGlucoseReading(reading)
        }
    }
}
```

Add StateFlow (alongside others in `onCreate`):
```kotlin
private lateinit var hcWriteEnabled: StateFlow<Boolean>

// In onCreate():
hcWriteEnabled = settings.hcWriteEnabled.stateIn(scope, SharingStarted.Eagerly, false)
```

In `onDestroy()`:
```kotlin
exerciseSyncJob?.cancel()
```

- [ ] **Step 4: Run full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add ExerciseSyncer with HC delta polling and BG write integration"
```

---

## Task 7: Settings UI — Exercise section

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/ui/settings/ExerciseSettings.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt`

- [ ] **Step 1: Add string resources**

Add to `res/values/strings.xml`:
```xml
<!-- Exercise settings -->
<string name="settings_exercise">Exercise</string>
<string name="settings_exercise_subtitle">Health Connect, workout history</string>
<string name="exercise_settings_title">Exercise</string>
<string name="exercise_hc_status_connected">Connected</string>
<string name="exercise_hc_status_permissions">Permissions needed</string>
<string name="exercise_hc_status_not_installed">Not installed</string>
<string name="exercise_hc_status_not_supported">Not supported</string>
<string name="exercise_hc_status_label">Health Connect</string>
<string name="exercise_hc_install_prompt">Install from Play Store</string>
<string name="exercise_hc_write_toggle">Write glucose to Health Connect</string>
<string name="exercise_hc_write_subtitle">Share glucose readings with other health apps</string>
<string name="exercise_hc_last_sync">Last sync</string>
<string name="exercise_hc_last_sync_never">Never</string>
<string name="hc_rationale_title">Health Connect Permissions</string>
<string name="hc_rationale_body">Strimma uses Health Connect to read your exercise sessions, heart rate, steps, and calories to show how exercise affects your glucose. Optionally, Strimma can write glucose readings so other health apps can see them.</string>
<string name="common_ok">OK</string>
```

Add translations to `values-sv`, `values-es`, `values-fr`, `values-de`.

- [ ] **Step 2: Add ViewModel flows for HC**

Add to `MainViewModel.kt`:

```kotlin
val hcWriteEnabled: StateFlow<Boolean> = settings.hcWriteEnabled
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
val hcLastSync: StateFlow<Long> = settings.hcLastSync
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

fun setHcWriteEnabled(enabled: Boolean) {
    viewModelScope.launch { settings.setHcWriteEnabled(enabled) }
}
```

- [ ] **Step 3: Create ExerciseSettings screen**

Create `app/src/main/java/com/psjostrom/strimma/ui/settings/ExerciseSettings.kt`. Follow the pattern of `TreatmentsSettings.kt`.

The screen needs access to `HealthConnectManager` for status checks and the permission contract. Use a dedicated `@HiltViewModel` for this screen:

```kotlin
@HiltViewModel
class ExerciseSettingsViewModel @Inject constructor(
    val healthConnectManager: HealthConnectManager,
    private val settings: SettingsRepository
) : ViewModel() {
    val hcWriteEnabled = settings.hcWriteEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val hcLastSync = settings.hcLastSync
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun setHcWriteEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setHcWriteEnabled(enabled) }
    }
}
```

The composable uses `hiltViewModel()` to get the ViewModel, then:
- TopAppBar with back button, title "Exercise"
- HC status row showing colored dot + status text + action button
- Toggle for BG write (gated by HC available + permissions)
- Last sync timestamp
- Permission launcher via `rememberLauncherForActivityResult(manager.createPermissionContract()!!)`
  - Only show the permission button when `manager.createPermissionContract()` returns non-null (HC available but permissions not granted)

- [ ] **Step 4: Add Exercise menu item to SettingsScreen**

In `SettingsScreen.kt`, in the first `SettingsMenuGroup`, add after the Treatments item (before Display):

```kotlin
HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
SettingsMenuItem(
    icon = Icons.Default.FitnessCenter,
    title = stringResource(R.string.settings_exercise),
    subtitle = stringResource(R.string.settings_exercise_subtitle),
    onClick = { onNavigate("settings/exercise") }
)
```

Add import: `import androidx.compose.material.icons.filled.FitnessCenter`

- [ ] **Step 5: Add nav route in MainActivity**

Add composable in `NavHost`:
```kotlin
composable("settings/exercise") {
    ExerciseSettings(
        onBack = { navController.popBackStack() }
    )
}
```

No need to wire StateFlows from MainViewModel — ExerciseSettings has its own ViewModel via `hiltViewModel()`.

- [ ] **Step 6: Build and verify**

Run: `./gradlew assembleDebug`
Expected: Build succeeds.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "Add Exercise settings screen with HC status and BG write toggle"
```

---

## Task 8: Exercise bands on MainScreen graph

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt`

- [ ] **Step 1: Add exercise sessions to MainViewModel**

Add `ExerciseDao` and `ExerciseBGAnalyzer` to the constructor injection of `MainViewModel`.

Add exercise sessions Flow using ExerciseDao's own reactive Flow (not chained off BG readings, so it updates when new exercises are synced):

```kotlin
val exerciseSessions: StateFlow<List<StoredExerciseSession>> = exerciseDao.getAllSessions()
    .map { sessions ->
        val since = System.currentTimeMillis() - HOURS_PER_DAY * MS_PER_HOUR
        sessions.filter { it.startTime >= since }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

- [ ] **Step 2: Pass exercise sessions to MainScreen**

Add parameter to `MainScreen`:
```kotlin
exerciseSessions: List<StoredExerciseSession> = emptyList(),
onExerciseClick: () -> Unit = {}
```

Wire in `MainActivity.kt`.

- [ ] **Step 3: Render exercise bands in Canvas**

In the existing Canvas drawing code in `MainScreen.kt`, after drawing the BG line and before drawing dots, add exercise band rendering:

For each `StoredExerciseSession` in the graph window:
- Calculate x positions from session start/end times using the same time→x mapping as BG dots
- Draw a filled rect from x_start to x_end, full height, using `ExerciseDefault.copy(alpha = 0.15f)`
- Draw left and right border lines at 50% alpha
- Draw text label (emoji + category label) at top-left of band

- [ ] **Step 4: Add exercise header icon**

Add a FitnessCenter icon button to the MainScreen top bar, next to the stats icon:

```kotlin
IconButton(onClick = onExerciseClick) {
    Icon(Icons.Default.FitnessCenter, contentDescription = stringResource(R.string.settings_exercise))
}
```

- [ ] **Step 5: Build and verify visually**

Run: `./gradlew installDebug`
Verify on device: exercise bands appear if there are synced sessions.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Add exercise bands on MainScreen graph and header icon"
```

---

## Task 9: Exercise bands on notification graph

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/notification/GraphRenderer.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/notification/NotificationHelper.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/service/StrimmaService.kt`

- [ ] **Step 1: Add exercise sessions parameter to GraphRenderer**

Add `exerciseSessions: List<StoredExerciseSession> = emptyList()` parameter to the render methods in `GraphRenderer.kt`.

Draw exercise bands using Android `Canvas` and `Paint`:
```kotlin
val exercisePaint = Paint().apply {
    color = CANVAS_EXERCISE
    alpha = 38 // ~15%
}
```

Same logic as MainScreen but using Android Canvas API instead of Compose Canvas.

- [ ] **Step 2: Pass exercise data through NotificationHelper**

Thread exercise sessions through `NotificationHelper.updateNotification()` → `GraphRenderer.render()`.

- [ ] **Step 3: Load exercise data in StrimmaService.updateNotification()**

Query `exerciseDao` for sessions in the notification graph window and pass to `notificationHelper.updateNotification()`.

- [ ] **Step 4: Build and verify**

Run: `./gradlew installDebug`
Verify: notification graph shows exercise bands.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add exercise bands to notification graph"
```

---

## Task 10: ExerciseDetailSheet (bottom sheet)

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/ui/ExerciseDetailSheet.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt`

- [ ] **Step 1: Add string resources**

```xml
<string name="exercise_detail_before">Before</string>
<string name="exercise_detail_during">During</string>
<string name="exercise_detail_after">After</string>
<string name="exercise_detail_entry_bg">Entry</string>
<string name="exercise_detail_min_bg">Min</string>
<string name="exercise_detail_lowest_bg">Lowest</string>
<string name="exercise_detail_max_drop">Max drop</string>
<string name="exercise_detail_avg_hr">Avg HR</string>
<string name="exercise_detail_max_hr">Max HR</string>
<string name="exercise_detail_steps">Steps</string>
<string name="exercise_detail_calories">Calories</string>
<string name="exercise_detail_time_to_lowest">Time to lowest</string>
<string name="exercise_detail_time_to_stable">Time to stable</string>
<string name="exercise_detail_still_dropping">Still dropping</string>
<string name="exercise_detail_post_hypo">Post-exercise low</string>
<string name="exercise_detail_no_data">Not enough glucose data</string>
<string name="exercise_detail_no_value">—</string>
<string name="exercise_detail_per_10min">per 10 min</string>
<string name="exercise_detail_after_duration">%s after</string>
```

Add translations.

- [ ] **Step 2: Create ExerciseDetailSheet composable**

Create `ExerciseDetailSheet.kt` with `ModalBottomSheet`. Sections:
- Header: emoji + type + duration + time range
- Before / During / After sections with BG values in user's unit
- Null values show "—"
- Post-exercise hypo shown as coral tinted pill

- [ ] **Step 3: Add BG context computation to ViewModel**

Add method to `MainViewModel`:
```kotlin
suspend fun computeExerciseBGContext(session: StoredExerciseSession): ExerciseBGAnalyzer.ExerciseBGContext? {
    val preStart = session.startTime - 30 * 60_000L
    val postEnd = session.endTime + 4 * 60 * 60_000L
    val readings = dao.readingsInRange(preStart, postEnd)
    val hrSamples = exerciseDao.getHeartRateForSession(session.id)
    return exerciseBGAnalyzer.analyze(session, readings, hrSamples, bgLow.value.toDouble())
}
```

Add `ExerciseBGAnalyzer` to the constructor injection.

Add a `readingsInRange(start, end)` query to `ReadingDao` if it doesn't exist.

- [ ] **Step 4: Wire band tap → bottom sheet in MainScreen**

Add state for selected exercise session. On band tap, compute BG context and show the sheet.

- [ ] **Step 5: Build and test on device**

Run: `./gradlew installDebug`
Verify: tapping an exercise band opens the bottom sheet with correct data.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "Add ExerciseDetailSheet with BG arc analysis display"
```

---

## Task 11: ExerciseHistoryScreen

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/ui/ExerciseHistoryScreen.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt`

- [ ] **Step 1: Add string resources**

```xml
<string name="exercise_history_title">Exercise</string>
<string name="exercise_history_empty">No exercise sessions yet. Connect Health Connect in Settings to see your workouts here.</string>
```

Add translations.

- [ ] **Step 2: Create ExerciseHistoryScreen**

Card list layout:
- Each card: emoji + type + time + duration, mini BG sparkline (Compose Canvas, 80x28dp), key stats row (entry BG, lowest BG, avg HR)
- Sparkline covers 30min pre through 4h post in ExerciseDefault color
- Lowest BG colored coral if hypo
- Tap card → ExerciseDetailSheet
- Scrollable `LazyColumn`, newest first
- Empty state message when no sessions

The screen needs a ViewModel (or share `MainViewModel`) to load sessions and compute BG context for each card.

- [ ] **Step 3: Add nav route**

In `MainActivity.kt`, add:
```kotlin
composable("exercise") {
    ExerciseHistoryScreen(
        onBack = { navController.popBackStack() }
    )
}
```

Wire the MainScreen `onExerciseClick` callback:
```kotlin
onExerciseClick = {
    navController.navigate("exercise") {
        launchSingleTop = true
    }
}
```

- [ ] **Step 4: Build and test on device**

Run: `./gradlew installDebug`
Verify: exercise icon in header navigates to history, cards display correctly, tap opens detail sheet.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "Add ExerciseHistoryScreen with card list and BG sparklines"
```

---

## Task 12: Final integration test and cleanup

**Files:**
- All modified files

- [ ] **Step 1: Run full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass.

- [ ] **Step 2: Run lint and detekt**

Run: `./gradlew detekt lint`
Expected: No new errors. Fix any warnings introduced by the new code.

- [ ] **Step 3: Verify on device end-to-end**

1. Install debug build
2. Open Settings → Exercise → verify HC status shows correctly
3. Grant HC permissions
4. Wait for sync (or trigger manually by navigating away and back)
5. Open Exercise History → verify sessions appear with sparklines
6. Tap a card → verify bottom sheet shows correct BG context
7. Go to MainScreen → verify exercise bands appear on graph
8. Tap a band → verify bottom sheet opens
9. Check notification → verify bands appear on notification graph
10. Toggle "Write glucose to HC" → verify BG readings appear in Health Connect

- [ ] **Step 4: Run the full build**

Run: `./gradlew assembleDebug assembleRelease`
Expected: Both succeed.

- [ ] **Step 5: Commit any final fixes**

```bash
git add -A && git commit -m "Final integration fixes for Health Connect exercise feature"
```
