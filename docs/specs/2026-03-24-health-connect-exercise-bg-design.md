# Health Connect + Exercise-BG Context — Design Spec

**Date:** 2026-03-24
**Status:** Approved

## Summary

Integrate Android Health Connect to read exercise sessions (with HR, steps, calories) and write glucose readings. Compute a complete BG arc analysis for each exercise session — pre/during/post — and surface it on the main graph as colored bands, in a detail bottom sheet, and in a dedicated Exercise History screen.

## Design Decisions

- **Scope:** Health Connect data layer + Exercise-BG analysis + all UI surfaces, shipped as one feature
- **HC availability:** Graceful discovery — Exercise section visible in Settings even if HC isn't installed. Status row guides user through install/permissions.
- **HC data types read:** ExerciseSession, HeartRate, Steps, ActiveCaloriesBurned
- **HC data types written:** BloodGlucose (opt-in toggle, off by default)
- **Polling strategy:** Changes token API (`getChangesToken()` / `getChanges()`), polled every 15 minutes by ExerciseSyncer
- **BG write:** Each new glucose reading written to HC immediately from StrimmaService, gated by toggle + permissions
- **Post-activity window:** 4 hours (catches delayed hypos with rapid insulin)
- **Pre-activity window:** 30 minutes (entry BG trend)
- **Graph markers:** Full-height colored bands
- **Detail view:** Material 3 ModalBottomSheet
- **Exercise history:** Standalone screen with card list + mini BG sparkline
- **Navigation:** Exercise icon button on MainScreen header + nav route `"exercise"`
- **Settings:** New "Exercise" section in SettingsScreen, after Treatments
- **No clinical jargon in UI:** "Lowest" not "nadir", plain labels throughout
- **No exercise history list filtering/search** — simple newest-first scroll for v1

## Exercise Colors

Distinct from BG status colors (cyan/amber/coral). Shifted from Springa's palette to avoid overlap:

| Category | Color | Hex |
|----------|-------|-----|
| Default (all types in v1) | Lavender | `#8B8BBA` |

Reserved for future use when category classification is implemented (requires workout structure data that HC doesn't provide):

| Category | Color | Hex |
|----------|-------|-----|
| Easy | Green-teal | `#2DD4A0` |
| Long | Purple | `#A78BFA` |
| Interval | Warm pink | `#F472B6` |

v1 defines only the DEFAULT color in `Color.kt` and `GraphColors.kt`. Reserved colors are documented here but not added to code until category classification is implemented.

Exercise type mapping from HC exercise type constants to emoji/label via `ExerciseCategory` enum. HC types provide the *activity kind* (running, cycling, walking) for the label/emoji, but not the *workout intent* (easy, long, interval).

## Architecture

### 1. `HealthConnectManager` (data/health/)

Hilt `@Singleton`. Wraps `HealthConnectClient`.

- `isAvailable(): HealthConnectStatus` — returns `AVAILABLE`, `NOT_INSTALLED`, `NOT_SUPPORTED`
- `hasPermissions(): Boolean` — checks all 5 permissions
- `val permissions: Set<String>` — the HC permission set, exposed for UI to build the permission launcher
- `fun createPermissionContract(): ActivityResultContract<Set<String>, Set<String>>` — returns HC SDK's `PermissionController.createRequestPermissionResultContract()`. The actual launcher is registered in the composable via `rememberLauncherForActivityResult()`.
- `getExerciseSessions(since: Instant): List<StoredExerciseSession>` — fetches from HC, maps to Room entities, includes associated HR, steps, calories
- `writeGlucoseReading(reading: GlucoseReading)` — writes single `BloodGlucoseRecord`
- `getChangesToken(): String` / `getChanges(token: String): ChangesResponse` — delta sync

### 2. `ExerciseSyncer` (data/health/)

Background poller injected into `StrimmaService`. Same pattern as `TreatmentSyncer`.

- Polls HC changes every 15 minutes
- On new/updated sessions: fetches full data, upserts into Room (within a transaction: delete old HR samples for session, then insert new ones)
- Persists changes token in DataStore
- Gated by HC availability + permissions — no-ops silently if not ready

**Error handling:**
- `SecurityException` (permissions revoked at runtime): log, downgrade internal status to permissions-needed, skip cycle
- `RemoteException` (HC app crashed or uninstalled): log, skip cycle, retry next interval
- `ChangesResponse.changesTokenExpired`: discard token, fetch fresh token, perform full sync from 30 days ago
- HC becomes available mid-session (user installs HC while Strimma is running): next poll cycle detects availability and starts syncing — no restart needed

### 3. `ExerciseBGAnalyzer` (data/health/)

Pure computation, no Android dependencies. Testable.

**Input:** `StoredExerciseSession` + `List<GlucoseReading>` (covering 30min before through 4h after)

**Output:** `ExerciseBGContext?` — returns null if BG coverage is below 50% during the exercise window (not enough data for meaningful analysis).

```kotlin
data class ExerciseBGContext(
    // Pre-activity (30 min before start)
    val entryBG: Int?,             // mg/dL at exercise start (null if no readings in pre-window)
    val entryTrend: Trend?,        // RISING, FALLING, STABLE (null if insufficient pre-window data)
    val entryStability: Double?,   // R² of linear regression on 30-min window

    // During exercise
    val minBG: Int?,               // lowest BG during session (null if no readings during exercise)
    val maxDropRate: Double?,      // mg/dL per 10 min, worst bucket
    val dropPer10Min: List<Double>,// mg/dL change per 10-min bucket (empty if insufficient data)

    // Post-activity (4h after end)
    val lowestBG: Int?,            // lowest BG in 4h post window
    val lowestBGTime: Instant?,    // when it occurred
    val timeToStable: Duration?,   // time from end until BG flattens (null if still dropping or insufficient data)
    val postExerciseHypo: Boolean, // lowestBG < user's low threshold (false if lowestBG is null)

    // Aggregated from HC
    val avgHR: Int?,
    val maxHR: Int?,
    val totalSteps: Int?,
    val activeCalories: Double?,

    // Coverage
    val bgCoveragePercent: Double  // % of exercise window with BG data
)

enum class Trend { RISING, FALLING, STABLE }
```

**Key thresholds:**
- Entry trend: linear regression slope on 30-min pre window. RISING > +1 mg/dL/min, FALLING < -1 mg/dL/min, otherwise STABLE
- Stability: R² of that regression
- Drop rate buckets: 10-minute intervals during exercise
- "Stable" detection for timeToStable: rate of change < 0.5 mg/dL/min sustained for 15 minutes
- Hypo flag: lowestBG < user's configured low threshold
- Minimum coverage: 50% of exercise duration must have BG data, otherwise return null

Computed on demand by ViewModel — no background precomputation.

### 4. Room Entities

```kotlin
@Entity(tableName = "exercise_sessions")
data class StoredExerciseSession(
    @PrimaryKey val id: String,        // HC UUID
    val type: Int,                     // HC ExerciseSessionRecord.EXERCISE_TYPE_*
    val startTime: Long,               // epoch millis
    val endTime: Long,                 // epoch millis
    val title: String?,                // user-provided title from HC
    val totalSteps: Int?,
    val activeCalories: Double?
)

@Entity(
    tableName = "heart_rate_samples",
    foreignKeys = [ForeignKey(
        entity = StoredExerciseSession::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class HeartRateSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val time: Long,                    // epoch millis
    val bpm: Int
)
```

### 5. ExerciseDao

- `getSessionsInRange(start: Long, end: Long): List<StoredExerciseSession>`
- `getSessionById(id: String): StoredExerciseSession?`
- `getHeartRateForSession(sessionId: String): List<HeartRateSample>`
- `getAllSessions(): Flow<List<StoredExerciseSession>>` — for history screen
- `upsertSession(session: StoredExerciseSession)`
- `deleteHeartRateForSession(sessionId: String)` — called before re-inserting HR samples on sync
- `insertHeartRateSamples(samples: List<HeartRateSample>)`
- `deleteSessionsOlderThan(cutoff: Long)` — prune, same retention as readings (30 days)

**Upsert flow (in transaction):** `deleteHeartRateForSession(id)` → `upsertSession(session)` → `insertHeartRateSamples(samples)`. This ensures HR data stays consistent when a session is re-synced.

### 6. ExerciseCategory

```kotlin
enum class ExerciseCategory(val emoji: String, val labelRes: Int) {
    RUNNING("🏃", R.string.exercise_type_running),
    WALKING("🚶", R.string.exercise_type_walking),
    CYCLING("🚴", R.string.exercise_type_cycling),
    SWIMMING("🏊", R.string.exercise_type_swimming),
    OTHER("🏋️", R.string.exercise_type_other);

    companion object {
        fun fromHCType(type: Int): ExerciseCategory = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> RUNNING

            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> WALKING

            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> CYCLING

            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> SWIMMING

            else -> OTHER
        }
    }
}
```

All categories use DEFAULT color (`#8B8BBA`) in v1. The enum provides activity-appropriate emoji and label. Color per-category is reserved for future use.

## UI

### MainScreen — Exercise bands on graph

- Full-height semi-transparent band (~15% alpha) for each exercise session in the graph window
- Left/right borders at 50% alpha
- Exercise type emoji + label at top-left (9sp, semi-bold)
- Color: DEFAULT lavender (`#8B8BBA`) for all types in v1
- Tap band → opens ModalBottomSheet with ExerciseBGContext (in-place on MainScreen, no navigation)
- Rendered in both Compose Canvas (MainScreen) and Android Canvas (GraphRenderer for notifications)

### ExerciseDetailSheet

Material 3 `ModalBottomSheet`. Three sections:

**Header:** emoji + type + duration + time range
"🏃 Run · 45 min · 07:15–08:00"

**Before (30 min pre-exercise):**
- Entry BG (user's unit), or "—" if unavailable
- Trend arrow (↗ rising / → stable / ↘ falling), or hidden if unavailable
- Stability indicator

**During:**
- Min BG, or "—" if unavailable
- Max drop rate (mg/dL or mmol per 10 min)
- Avg HR, max HR
- Steps, active calories

**After (4h post-exercise):**
- Lowest BG — coral-colored if below low threshold, or "—" if unavailable
- Time to lowest (e.g., "1h 20m after")
- Time to stable (or "still dropping" if null)
- Post-exercise hypo flag as coral tinted pill

All BG values in user's configured unit. Sections with no data show "—" gracefully.

### ExerciseHistoryScreen

- Nav route: `"exercise"`, icon button on MainScreen header
- Card list, newest first
- Each card: exercise type emoji + name + time + duration, mini BG sparkline (30min pre through 4h post), key stats row (entry BG, lowest BG, avg HR)
- Sparkline colored DEFAULT lavender
- Lowest BG colored coral if hypo
- Tap card → ExerciseDetailSheet for that session
- Empty state: "No exercise sessions yet. Connect Health Connect in Settings to see your workouts here."

### Settings — Exercise section

New section in SettingsScreen, after Treatments. Route: `"settings/exercise"`.

- **Health Connect status** — "Connected" / "Permissions needed" / "Not installed" / "Not supported" with colored dot and action button
  - "Not installed" → tap opens Play Store listing for Health Connect
  - "Permissions needed" → tap launches permission request via `rememberLauncherForActivityResult(manager.createPermissionContract())`
- **Write glucose to Health Connect** — toggle, off by default. "Share glucose readings with other health apps"
- **Last sync** — timestamp or "Never"

## Permissions & Manifest

**AndroidManifest.xml additions:**

```xml
<!-- Health Connect permissions -->
<uses-permission android:name="android.permission.health.READ_EXERCISE" />
<uses-permission android:name="android.permission.health.READ_HEART_RATE" />
<uses-permission android:name="android.permission.health.READ_STEPS" />
<uses-permission android:name="android.permission.health.READ_ACTIVE_CALORIES_BURNED" />
<uses-permission android:name="android.permission.health.WRITE_BLOOD_GLUCOSE" />

<!-- HC availability check -->
<queries>
    <package android:name="com.google.android.apps.healthdata" />
</queries>

<!-- HC permission rationale activity (required by HC SDK) -->
<activity
    android:name=".ui.HealthConnectPermissionRationale"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
    </intent-filter>
</activity>
```

Permission request lifecycle: `HealthConnectManager` exposes the contract and permission set. The UI composable (ExerciseSettings) registers the launcher via `rememberLauncherForActivityResult()` and invokes it when the user taps the status row.

## Dependencies

```kotlin
implementation("androidx.health.connect:connect-client:<latest-stable>")
```

Use the latest stable (or latest alpha if no stable exists) from Maven Central at implementation time.

## Database Migration

Room schema version bump from 3 to 4. Use `@AutoMigration(from = 3, to = 4)` — this is a purely additive change (two new tables, no column changes to existing tables), so Room's auto-migration handles it without a manual `Migration` object.

Add to `StrimmaDatabase`:
```kotlin
@Database(
    version = 4,
    entities = [GlucoseReading::class, Treatment::class, StoredExerciseSession::class, HeartRateSample::class],
    autoMigrations = [AutoMigration(from = 3, to = 4)]
)
```

## Error Handling

| Scenario | Behavior |
|----------|----------|
| HC not installed | Exercise section shows "Not installed" with Play Store link. All HC features disabled, no crashes. |
| HC installed but permissions denied | Shows "Permissions needed". Exercise history empty. BG write disabled. No repeated prompts — user taps status row to grant. |
| Permissions revoked at runtime | `SecurityException` caught by ExerciseSyncer/BG writer. Status downgrades to "Permissions needed". Next settings visit shows updated state. |
| HC app crashes (`RemoteException`) | Logged, cycle skipped, retry next 15-min interval. |
| Changes token expired | Discard token, fetch fresh, full sync from 30 days ago. |
| No BG data during exercise | `ExerciseBGAnalyzer` returns null if coverage < 50%. Detail sheet shows "Not enough glucose data" message. |
| Exercise session with no HR/steps/calories | Nullable fields show "—" in UI. Analysis still runs on available BG data. |

## Testing

- **ExerciseBGAnalyzerTest** — normal session, session with hypo, no BG data during exercise (returns null), partial coverage at boundary (49% vs 51%), very short session (< 10 min), very long session (> 3h), overnight exercise crossing midnight, pre-exercise rising vs falling vs stable trends, post-exercise window with no readings
- **ExerciseSyncerTest** — mock HC client, changes token flow, expired token recovery, Room persistence, SecurityException handling, RemoteException handling
- **HealthConnectManagerTest** — availability states, permission checking, write flow
- **ExerciseDaoTest** — sessions in range, HR samples for session, upsert idempotency (HR samples replaced not duplicated), cascade delete, prune

## Files

**New:**
- `data/health/HealthConnectManager.kt`
- `data/health/ExerciseSyncer.kt`
- `data/health/ExerciseBGAnalyzer.kt`
- `data/health/StoredExerciseSession.kt` (Room entity)
- `data/health/HeartRateSample.kt` (Room entity)
- `data/health/ExerciseDao.kt`
- `data/health/ExerciseCategory.kt`
- `ui/ExerciseHistoryScreen.kt`
- `ui/ExerciseDetailSheet.kt`
- `ui/settings/ExerciseSettings.kt`
- `ui/HealthConnectPermissionRationale.kt`
- Tests for all above

**Modified:**
- `app/build.gradle.kts` — HC dependency
- `AndroidManifest.xml` — permissions, queries, rationale activity
- `data/StrimmaDatabase.kt` — new entities, auto-migration, version bump
- `di/AppModule.kt` — HealthConnectManager, ExerciseDao providers
- `service/StrimmaService.kt` — ExerciseSyncer + BG write injection
- `ui/MainActivity.kt` — new nav routes
- `ui/MainScreen.kt` — exercise bands on graph, header icon, band tap → bottom sheet
- `ui/MainViewModel.kt` — exercise data flows, BG context computation
- `ui/SettingsScreen.kt` — Exercise section row
- `ui/theme/Color.kt` — exercise DEFAULT color
- `graph/GraphColors.kt` — canvas constant for exercise band
- `notification/GraphRenderer.kt` — exercise bands on notification graph
- `res/values/strings.xml` + translations (en, sv, es, fr, de)
