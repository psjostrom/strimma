# Exercise Stats & Category Unification — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify exercise categories (kill WorkoutCategory, expand ExerciseCategory), add MetabolicProfile for physiology-based BG targets, and build per-category exercise stats with a Patterns tab.

**Architecture:** Two phases. Phase 1 replaces WorkoutCategory with an expanded ExerciseCategory + MetabolicProfile enum, migrating all consumers (settings, guidance, calendar, service, UI). Phase 2 adds CategoryStatsCalculator and a Patterns tab in ExerciseHistoryScreen. Every task keeps the code compiling.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Hilt, DataStore, Coroutines/Flow, JUnit 4

**Spec:** `docs/specs/exercise-stats-spec.md` (v2, research-informed)

---

## File Structure

### New Files
- `data/calendar/MetabolicProfile.kt` — enum (AEROBIC, HIGH_INTENSITY, RESISTANCE) with default targets
- `data/health/IntensityBand.kt` — enum (LIGHT, MODERATE, INTENSE) for HR-based classification
- `data/health/CategoryStatsCalculator.kt` — aggregates ExerciseBGContext across sessions
- `data/health/CategoryStats.kt` — data classes (CategoryStats, BGBand, BandStats)

### Modified Files
- `data/health/ExerciseCategory.kt` — expand from 5 to 12 categories, add fromTitle(), defaultMetabolicProfile
- `data/calendar/WorkoutEvent.kt` — category field: WorkoutCategory → ExerciseCategory, add metabolicProfile
- `data/calendar/GuidanceState.kt` — no structural change, just imports follow WorkoutEvent
- `data/calendar/PreActivityAssessor.kt` — no signature change (already takes raw target floats)
- `data/SettingsRepository.kt` — new target storage keyed by ExerciseCategory name, maxHR setting, migration
- `data/calendar/CalendarReader.kt` — use ExerciseCategory.fromTitle instead of WorkoutCategory.fromTitle
- `ui/MainViewModel.kt` — guidance target lookup uses ExerciseCategory
- `ui/ExerciseHistoryScreen.kt` — add Patterns tab, update PlannedWorkoutCard
- `ui/settings/ExerciseSettings.kt` — dynamic target list, max HR field
- `service/StrimmaService.kt` — update target lookup
- `res/values/strings.xml` — new category labels, patterns tab strings

### Deleted Files
- `data/calendar/WorkoutCategory.kt` — replaced by ExerciseCategory + MetabolicProfile

### Test Files
- `test/.../data/calendar/MetabolicProfileTest.kt` — new
- `test/.../data/health/ExerciseCategoryTest.kt` — new (fromTitle, fromHCType)
- `test/.../data/health/CategoryStatsCalculatorTest.kt` — new
- `test/.../data/calendar/PreActivityAssessorTest.kt` — update helper
- `test/.../ui/GuidanceStateTest.kt` — update helper

All paths relative to `app/src/main/java/com/psjostrom/strimma/` (source) or `app/src/test/java/com/psjostrom/strimma/` (test).

---

## Phase 1: Category Unification

### Task 1: MetabolicProfile Enum

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/data/calendar/MetabolicProfile.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/data/calendar/MetabolicProfileTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.psjostrom.strimma.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class MetabolicProfileTest {

    @Test
    fun `AEROBIC has correct default targets`() {
        assertEquals(126f, MetabolicProfile.AEROBIC.defaultTargetLowMgdl)
        assertEquals(180f, MetabolicProfile.AEROBIC.defaultTargetHighMgdl)
    }

    @Test
    fun `HIGH_INTENSITY has higher default targets`() {
        assertEquals(144f, MetabolicProfile.HIGH_INTENSITY.defaultTargetLowMgdl)
        assertEquals(216f, MetabolicProfile.HIGH_INTENSITY.defaultTargetHighMgdl)
    }

    @Test
    fun `RESISTANCE has same targets as AEROBIC`() {
        assertEquals(126f, MetabolicProfile.RESISTANCE.defaultTargetLowMgdl)
        assertEquals(180f, MetabolicProfile.RESISTANCE.defaultTargetHighMgdl)
    }

    @Test
    fun `fromKeywords detects high intensity`() {
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("Interval Run"))
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("Tempo session"))
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("HIIT workout"))
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("Sprint training"))
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("Fartlek"))
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("Intervall"))
    }

    @Test
    fun `fromKeywords returns null for non-intensity words`() {
        assertEquals(null, MetabolicProfile.fromKeywords("Easy Run"))
        assertEquals(null, MetabolicProfile.fromKeywords("Morning Walk"))
        assertEquals(null, MetabolicProfile.fromKeywords("Gym"))
        assertEquals(null, MetabolicProfile.fromKeywords(""))
    }

    @Test
    fun `fromKeywords is case insensitive`() {
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("INTERVAL"))
        assertEquals(MetabolicProfile.HIGH_INTENSITY, MetabolicProfile.fromKeywords("hiit"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.data.calendar.MetabolicProfileTest" 2>&1 | tail -5`
Expected: Compilation error — MetabolicProfile doesn't exist

- [ ] **Step 3: Write the implementation**

```kotlin
package com.psjostrom.strimma.data.calendar

enum class MetabolicProfile(
    val defaultTargetLowMgdl: Float,
    val defaultTargetHighMgdl: Float
) {
    AEROBIC(126f, 180f),           // 7-10 mmol/L — ADA/Riddell consensus
    HIGH_INTENSITY(144f, 216f),    // 8-12 mmol/L — lower hypo risk, may spike
    RESISTANCE(126f, 180f);        // 7-10 mmol/L — similar to aerobic, delayed effect

    companion object {
        private val HIGH_INTENSITY_KEYWORDS = listOf(
            "interval", "tempo", "threshold", "speed", "fartlek", "hiit", "sprint",
            "intervall" // Swedish
        )

        /** Detect intensity override from calendar event title. Returns null if no keywords match. */
        fun fromKeywords(title: String): MetabolicProfile? {
            val lower = title.lowercase()
            if (HIGH_INTENSITY_KEYWORDS.any { lower.contains(it) }) return HIGH_INTENSITY
            return null
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.data.calendar.MetabolicProfileTest" 2>&1 | tail -5`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```
feat: add MetabolicProfile enum with physiology-based BG targets
```

---

### Task 2: Expand ExerciseCategory

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/data/health/ExerciseCategory.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/data/health/ExerciseCategoryTest.kt`
- Modify: `app/src/main/res/values/strings.xml` (add new category labels)

- [ ] **Step 1: Write the test**

```kotlin
package com.psjostrom.strimma.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.psjostrom.strimma.data.calendar.MetabolicProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseCategoryTest {

    // --- fromHCType ---

    @Test
    fun `running maps to RUNNING`() {
        assertEquals(ExerciseCategory.RUNNING, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))
        assertEquals(ExerciseCategory.RUNNING, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL))
    }

    @Test
    fun `hiking maps to HIKING not WALKING`() {
        assertEquals(ExerciseCategory.HIKING, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_HIKING))
    }

    @Test
    fun `walking maps to WALKING`() {
        assertEquals(ExerciseCategory.WALKING, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_WALKING))
    }

    @Test
    fun `weightlifting and calisthenics map to STRENGTH`() {
        assertEquals(ExerciseCategory.STRENGTH, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING))
        assertEquals(ExerciseCategory.STRENGTH, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS))
    }

    @Test
    fun `yoga maps to YOGA`() {
        assertEquals(ExerciseCategory.YOGA, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_YOGA))
        assertEquals(ExerciseCategory.YOGA, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_PILATES))
    }

    @Test
    fun `unknown type maps to OTHER`() {
        assertEquals(ExerciseCategory.OTHER, ExerciseCategory.fromHCType(9999))
    }

    // --- fromTitle ---

    @Test
    fun `fromTitle matches running keywords`() {
        assertEquals(ExerciseCategory.RUNNING, ExerciseCategory.fromTitle("Easy Run"))
        assertEquals(ExerciseCategory.RUNNING, ExerciseCategory.fromTitle("Morning jog"))
        assertEquals(ExerciseCategory.RUNNING, ExerciseCategory.fromTitle("Löpning"))
    }

    @Test
    fun `fromTitle matches Swedish keywords`() {
        assertEquals(ExerciseCategory.WALKING, ExerciseCategory.fromTitle("Promenad med hunden"))
        assertEquals(ExerciseCategory.HIKING, ExerciseCategory.fromTitle("Vandring i fjällen"))
        assertEquals(ExerciseCategory.STRENGTH, ExerciseCategory.fromTitle("Styrketräning"))
        assertEquals(ExerciseCategory.CYCLING, ExerciseCategory.fromTitle("Cykeltur"))
        assertEquals(ExerciseCategory.SKIING, ExerciseCategory.fromTitle("Skidtur"))
        assertEquals(ExerciseCategory.CLIMBING, ExerciseCategory.fromTitle("Klättring"))
    }

    @Test
    fun `fromTitle returns OTHER for unrecognized titles`() {
        assertEquals(ExerciseCategory.OTHER, ExerciseCategory.fromTitle("Padel med Johan"))
        assertEquals(ExerciseCategory.OTHER, ExerciseCategory.fromTitle(""))
    }

    @Test
    fun `fromTitle is case insensitive`() {
        assertEquals(ExerciseCategory.RUNNING, ExerciseCategory.fromTitle("RUNNING"))
        assertEquals(ExerciseCategory.SWIMMING, ExerciseCategory.fromTitle("SWIM"))
    }

    // --- defaultMetabolicProfile ---

    @Test
    fun `STRENGTH defaults to RESISTANCE profile`() {
        assertEquals(MetabolicProfile.RESISTANCE, ExerciseCategory.STRENGTH.defaultMetabolicProfile)
    }

    @Test
    fun `CLIMBING defaults to RESISTANCE profile`() {
        assertEquals(MetabolicProfile.RESISTANCE, ExerciseCategory.CLIMBING.defaultMetabolicProfile)
    }

    @Test
    fun `MARTIAL_ARTS defaults to HIGH_INTENSITY profile`() {
        assertEquals(MetabolicProfile.HIGH_INTENSITY, ExerciseCategory.MARTIAL_ARTS.defaultMetabolicProfile)
    }

    @Test
    fun `RUNNING defaults to AEROBIC profile`() {
        assertEquals(MetabolicProfile.AEROBIC, ExerciseCategory.RUNNING.defaultMetabolicProfile)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.data.health.ExerciseCategoryTest" 2>&1 | tail -5`
Expected: Compilation error — fromTitle, defaultMetabolicProfile don't exist

- [ ] **Step 3: Add string resources for new categories**

Add to `app/src/main/res/values/strings.xml` after the existing exercise type strings:

```xml
<string name="exercise_type_hiking">Hiking</string>
<string name="exercise_type_strength">Strength</string>
<string name="exercise_type_yoga">Yoga</string>
<string name="exercise_type_rowing">Rowing</string>
<string name="exercise_type_skiing">Skiing</string>
<string name="exercise_type_climbing">Climbing</string>
<string name="exercise_type_martial_arts">Martial Arts</string>
```

- [ ] **Step 4: Write the implementation**

Replace the entire `ExerciseCategory.kt`:

```kotlin
package com.psjostrom.strimma.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.calendar.MetabolicProfile

enum class ExerciseCategory(
    val emoji: String,
    val labelRes: Int,
    val defaultMetabolicProfile: MetabolicProfile,
    val keywords: List<String>
) {
    RUNNING(
        "\uD83C\uDFC3", R.string.exercise_type_running, MetabolicProfile.AEROBIC,
        listOf("run", "jog", "sprint", "löpning")
    ),
    WALKING(
        "\uD83D\uDEB6", R.string.exercise_type_walking, MetabolicProfile.AEROBIC,
        listOf("walk", "promenad")
    ),
    HIKING(
        "\u26F0\uFE0F", R.string.exercise_type_hiking, MetabolicProfile.AEROBIC,
        listOf("hike", "hiking", "vandring")
    ),
    CYCLING(
        "\uD83D\uDEB4", R.string.exercise_type_cycling, MetabolicProfile.AEROBIC,
        listOf("bike", "cycle", "cykel")
    ),
    SWIMMING(
        "\uD83C\uDFCA", R.string.exercise_type_swimming, MetabolicProfile.AEROBIC,
        listOf("swim", "simning")
    ),
    STRENGTH(
        "\uD83C\uDFCB\uFE0F", R.string.exercise_type_strength, MetabolicProfile.RESISTANCE,
        listOf("gym", "strength", "weights", "lift", "styrka")
    ),
    YOGA(
        "\uD83E\uDDD8", R.string.exercise_type_yoga, MetabolicProfile.AEROBIC,
        listOf("yoga", "pilates")
    ),
    ROWING(
        "\uD83D\uDEA3", R.string.exercise_type_rowing, MetabolicProfile.AEROBIC,
        listOf("row", "erg", "rodd")
    ),
    SKIING(
        "\u26F7\uFE0F", R.string.exercise_type_skiing, MetabolicProfile.AEROBIC,
        listOf("ski", "snowboard", "skid")
    ),
    CLIMBING(
        "\uD83E\uDDD7", R.string.exercise_type_climbing, MetabolicProfile.RESISTANCE,
        listOf("climb", "boulder", "klättr")
    ),
    MARTIAL_ARTS(
        "\uD83E\uDD4A", R.string.exercise_type_martial_arts, MetabolicProfile.HIGH_INTENSITY,
        listOf("martial", "boxing", "mma", "kampsport")
    ),
    OTHER(
        "\uD83C\uDFCB\uFE0F", R.string.exercise_type_other, MetabolicProfile.AEROBIC,
        emptyList()
    );

    companion object {
        fun fromHCType(type: Int): ExerciseCategory = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> RUNNING
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> WALKING
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> HIKING
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> CYCLING
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> SWIMMING
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> STRENGTH
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> YOGA
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> ROWING
            ExerciseSessionRecord.EXERCISE_TYPE_SKIING_CROSS_COUNTRY,
            ExerciseSessionRecord.EXERCISE_TYPE_SKIING_DOWNHILL,
            ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> SKIING
            ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> CLIMBING
            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> MARTIAL_ARTS
            else -> OTHER
        }

        fun fromTitle(title: String): ExerciseCategory {
            val lower = title.lowercase()
            for (category in entries) {
                if (category.keywords.any { lower.contains(it) }) return category
            }
            return OTHER
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.data.health.ExerciseCategoryTest" 2>&1 | tail -5`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```
feat: expand ExerciseCategory to 12 types with fromTitle and MetabolicProfile
```

---

### Task 3: Update WorkoutEvent and CalendarReader

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/data/calendar/WorkoutEvent.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/data/calendar/CalendarReader.kt`

- [ ] **Step 1: Update WorkoutEvent**

Replace `WorkoutEvent.kt`:

```kotlin
package com.psjostrom.strimma.data.calendar

import com.psjostrom.strimma.data.health.ExerciseCategory

data class WorkoutEvent(
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val category: ExerciseCategory,
    val metabolicProfile: MetabolicProfile,
    val calendarId: Long
)
```

- [ ] **Step 2: Update CalendarReader**

In `CalendarReader.kt`, update the two places where `WorkoutEvent` is constructed.

In `getUpcomingWorkouts()` (around line 92), replace:
```kotlin
events.add(
    WorkoutEvent(title, startTime, endTime, WorkoutCategory.fromTitle(title), calendarId)
)
```
with:
```kotlin
val category = ExerciseCategory.fromTitle(title)
val profile = MetabolicProfile.fromKeywords(title) ?: category.defaultMetabolicProfile
events.add(
    WorkoutEvent(title, startTime, endTime, category, profile, calendarId)
)
```

In `getNextWorkout()` (around line 138), replace:
```kotlin
return@withContext WorkoutEvent(
    title, startTime, endTime,
    WorkoutCategory.fromTitle(title), calendarId
)
```
with:
```kotlin
val category = ExerciseCategory.fromTitle(title)
val profile = MetabolicProfile.fromKeywords(title) ?: category.defaultMetabolicProfile
return@withContext WorkoutEvent(
    title, startTime, endTime, category, profile, calendarId
)
```

Update imports: remove `WorkoutCategory`, add `ExerciseCategory`, `MetabolicProfile`.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -10`
Expected: Compilation errors in files still using old WorkoutCategory with WorkoutEvent (MainViewModel, StrimmaService, ExerciseHistoryScreen, tests). This is expected — we'll fix them in subsequent tasks.

- [ ] **Step 4: Commit**

```
refactor: switch WorkoutEvent from WorkoutCategory to ExerciseCategory + MetabolicProfile
```

---

### Task 4: Update SettingsRepository

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/data/SettingsRepository.kt`

- [ ] **Step 1: Add new preference keys and max HR setting**

In the companion object, add after the existing workout target keys (line ~161):

```kotlin
// Exercise target keys (keyed by ExerciseCategory.name)
private fun exerciseTargetLowKey(name: String) = floatPreferencesKey("exercise_target_low_$name")
private fun exerciseTargetHighKey(name: String) = floatPreferencesKey("exercise_target_high_$name")
private val KEY_MAX_HEART_RATE = intPreferencesKey("max_heart_rate")
```

- [ ] **Step 2: Add new getter/setter methods**

Add after the existing `setWorkoutTarget` method (line ~402):

```kotlin
val maxHeartRate: Flow<Int?> = dataStore.data.map { it[KEY_MAX_HEART_RATE] }
suspend fun setMaxHeartRate(hr: Int?) {
    dataStore.edit {
        if (hr != null) it[KEY_MAX_HEART_RATE] = hr
        else it.remove(KEY_MAX_HEART_RATE)
    }
}

fun exerciseTargetLow(category: com.psjostrom.strimma.data.health.ExerciseCategory): Flow<Float> =
    dataStore.data.map { prefs ->
        prefs[exerciseTargetLowKey(category.name)]
            ?: migrateLegacyTargetLow(prefs, category)
            ?: category.defaultMetabolicProfile.defaultTargetLowMgdl
    }

fun exerciseTargetHigh(category: com.psjostrom.strimma.data.health.ExerciseCategory): Flow<Float> =
    dataStore.data.map { prefs ->
        prefs[exerciseTargetHighKey(category.name)]
            ?: migrateLegacyTargetHigh(prefs, category)
            ?: category.defaultMetabolicProfile.defaultTargetHighMgdl
    }

suspend fun setExerciseTarget(
    category: com.psjostrom.strimma.data.health.ExerciseCategory,
    low: Float,
    high: Float
) {
    dataStore.edit {
        it[exerciseTargetLowKey(category.name)] = low
        it[exerciseTargetHighKey(category.name)] = high
    }
}

// Migration: old WorkoutCategory keys → ExerciseCategory keys
private fun migrateLegacyTargetLow(
    prefs: androidx.datastore.preferences.core.Preferences,
    category: com.psjostrom.strimma.data.health.ExerciseCategory
): Float? = when (category) {
    com.psjostrom.strimma.data.health.ExerciseCategory.RUNNING ->
        prefs[KEY_WORKOUT_EASY_LOW]  // EASY is most conservative
    com.psjostrom.strimma.data.health.ExerciseCategory.STRENGTH ->
        prefs[KEY_WORKOUT_STRENGTH_LOW]
    else -> null
}

private fun migrateLegacyTargetHigh(
    prefs: androidx.datastore.preferences.core.Preferences,
    category: com.psjostrom.strimma.data.health.ExerciseCategory
): Float? = when (category) {
    com.psjostrom.strimma.data.health.ExerciseCategory.RUNNING ->
        prefs[KEY_WORKOUT_EASY_HIGH]
    com.psjostrom.strimma.data.health.ExerciseCategory.STRENGTH ->
        prefs[KEY_WORKOUT_STRENGTH_HIGH]
    else -> null
}
```

- [ ] **Step 3: Commit**

```
feat: add ExerciseCategory-based target storage with legacy migration
```

---

### Task 5: Update MainViewModel and GuidanceState Tests

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt`
- Modify: `app/src/test/java/com/psjostrom/strimma/ui/GuidanceStateTest.kt`

- [ ] **Step 1: Update MainViewModel guidance target lookup**

In `MainViewModel.kt`, update the `guidanceState` combine block (line ~343-358). Change the target lookup from:
```kotlin
val targetLow = event?.let { settings.workoutTargetLow(it.category).first() } ?: 0f
val targetHigh = event?.let { settings.workoutTargetHigh(it.category).first() } ?: 0f
```
to:
```kotlin
val targetLow = event?.let { settings.exerciseTargetLow(it.category).first() } ?: 0f
val targetHigh = event?.let { settings.exerciseTargetHigh(it.category).first() } ?: 0f
```

Update `setWorkoutTarget` (line ~319) from:
```kotlin
fun setWorkoutTarget(category: WorkoutCategory, low: Float, high: Float) =
    viewModelScope.launch { settings.setWorkoutTarget(category, low, high) }
```
to:
```kotlin
fun setExerciseTarget(category: ExerciseCategory, low: Float, high: Float) =
    viewModelScope.launch { settings.setExerciseTarget(category, low, high) }
```

Update imports: remove `WorkoutCategory`, add `ExerciseCategory` (if not already imported).

- [ ] **Step 2: Update GuidanceStateTest**

In `GuidanceStateTest.kt`, update the `event()` helper (line ~22-26) from:
```kotlin
private fun event(
    title: String = "Easy Run",
    startTime: Long = twoHoursFromNow,
    category: WorkoutCategory = WorkoutCategory.EASY
) = WorkoutEvent(title, startTime, startTime + 3600_000L, category, 1L)
```
to:
```kotlin
private fun event(
    title: String = "Easy Run",
    startTime: Long = twoHoursFromNow,
    category: ExerciseCategory = ExerciseCategory.RUNNING,
    profile: MetabolicProfile = MetabolicProfile.AEROBIC
) = WorkoutEvent(title, startTime, startTime + 3600_000L, category, profile, 1L)
```

Update the `workout category affects readiness thresholds` test (line ~140-150) from:
```kotlin
val intervalEvent = event(title = "Tempo Run", category = WorkoutCategory.INTERVAL)
val result = MainViewModel.computeGuidance(
    intervalEvent, reading(sgv = 140), listOf(reading(sgv = 140)), 0.0,
    162f, 198f, bgLow, bgHigh, nowMs = now
)
```
to:
```kotlin
val intervalEvent = event(
    title = "Tempo Run",
    category = ExerciseCategory.RUNNING,
    profile = MetabolicProfile.HIGH_INTENSITY
)
val result = MainViewModel.computeGuidance(
    intervalEvent, reading(sgv = 140), listOf(reading(sgv = 140)), 0.0,
    144f, 216f, bgLow, bgHigh, nowMs = now
)
```

Update imports: remove `WorkoutCategory`, add `ExerciseCategory`, `MetabolicProfile`.

- [ ] **Step 3: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.ui.GuidanceStateTest" 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```
refactor: update MainViewModel and guidance tests to use ExerciseCategory
```

---

### Task 6: Update StrimmaService

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/service/StrimmaService.kt`

- [ ] **Step 1: Update target lookup in notification update**

In `StrimmaService.kt`, around line 421-422, change:
```kotlin
val targetLow = settings.workoutTargetLow(event.category).first()
val targetHigh = settings.workoutTargetHigh(event.category).first()
```
to:
```kotlin
val targetLow = settings.exerciseTargetLow(event.category).first()
val targetHigh = settings.exerciseTargetHigh(event.category).first()
```

No import changes needed — the `event.category` type changed from `WorkoutCategory` to `ExerciseCategory` in Task 3, and the service just passes it through to `settings`.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -10`
Expected: May still have errors in ExerciseSettings and ExerciseHistoryScreen — those are next.

- [ ] **Step 3: Commit**

```
refactor: update StrimmaService to use ExerciseCategory targets
```

---

### Task 7: Update ExerciseSettings UI

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/settings/ExerciseSettings.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add max HR string resource**

In `strings.xml`:
```xml
<string name="exercise_max_hr_label">Max heart rate</string>
<string name="exercise_max_hr_hint">Used for intensity-based exercise stats</string>
```

- [ ] **Step 2: Update ExerciseSettingsViewModel**

Replace the workout target methods (lines ~101-105):
```kotlin
fun setWorkoutTarget(category: WorkoutCategory, low: Float, high: Float) {
    viewModelScope.launch { settings.setWorkoutTarget(category, low, high) }
}
fun workoutTargetLow(category: WorkoutCategory) = settings.workoutTargetLow(category)
fun workoutTargetHigh(category: WorkoutCategory) = settings.workoutTargetHigh(category)
```
with:
```kotlin
fun setExerciseTarget(category: ExerciseCategory, low: Float, high: Float) {
    viewModelScope.launch { settings.setExerciseTarget(category, low, high) }
}
fun exerciseTargetLow(category: ExerciseCategory) = settings.exerciseTargetLow(category)
fun exerciseTargetHigh(category: ExerciseCategory) = settings.exerciseTargetHigh(category)

val maxHeartRate: StateFlow<Int?> = settings.maxHeartRate
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

fun setMaxHeartRate(hr: Int?) { viewModelScope.launch { settings.setMaxHeartRate(hr) } }
```

Add `ExerciseCategory` import, remove `WorkoutCategory` import.

- [ ] **Step 3: Replace hardcoded target rows with dynamic list**

In the composable, replace the hardcoded 4-row target section (lines ~307-321):
```kotlin
val settableCategories = listOf(
    WorkoutCategory.EASY,
    WorkoutCategory.INTERVAL,
    WorkoutCategory.LONG,
    WorkoutCategory.STRENGTH
)
for (category in settableCategories) {
    WorkoutTargetRow(...)
}
```
with:
```kotlin
// Show categories the user has actually used or has planned
val sessions by viewModel.healthConnectManager.let {
    // Use a fixed list of common categories for now — will be dynamic later
    remember { mutableStateOf(ExerciseCategory.entries.filter { it != ExerciseCategory.OTHER }) }
}
for (category in sessions) {
    ExerciseTargetRow(
        category = category,
        glucoseUnit = glucoseUnit,
        viewModel = viewModel,
        textColor = onBg
    )
}
```

- [ ] **Step 4: Replace WorkoutTargetRow with ExerciseTargetRow**

Rename and update the composable (lines ~377-423):
```kotlin
@Composable
private fun ExerciseTargetRow(
    category: ExerciseCategory,
    glucoseUnit: GlucoseUnit,
    viewModel: ExerciseSettingsViewModel,
    textColor: androidx.compose.ui.graphics.Color
) {
    val low by viewModel.exerciseTargetLow(category)
        .collectAsState(initial = category.defaultMetabolicProfile.defaultTargetLowMgdl)
    val high by viewModel.exerciseTargetHigh(category)
        .collectAsState(initial = category.defaultMetabolicProfile.defaultTargetHighMgdl)

    var lowText by remember(low, glucoseUnit) { mutableStateOf(glucoseUnit.formatThreshold(low)) }
    var highText by remember(high, glucoseUnit) { mutableStateOf(glucoseUnit.formatThreshold(high)) }

    Column {
        Text(
            "${category.emoji} ${category.name.lowercase().replaceFirstChar { it.uppercase() }}",
            color = textColor, fontSize = 14.sp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = lowText,
                onValueChange = { v ->
                    lowText = v
                    glucoseUnit.parseThreshold(v)?.let { parsed ->
                        if (parsed < high) viewModel.setExerciseTarget(category, parsed, high)
                    }
                },
                label = { Text("Low (${glucoseUnit.label})") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            OutlinedTextField(
                value = highText,
                onValueChange = { v ->
                    highText = v
                    glucoseUnit.parseThreshold(v)?.let { parsed ->
                        if (parsed > low) viewModel.setExerciseTarget(category, low, parsed)
                    }
                },
                label = { Text("High (${glucoseUnit.label})") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        }
    }
}
```

- [ ] **Step 5: Add max HR field to settings UI**

Add after the target rows section, before the closing brace of the Workout Settings section:

```kotlin
// Max HR setting
val maxHR by viewModel.maxHeartRate.collectAsState()
var maxHRText by remember(maxHR) { mutableStateOf(maxHR?.toString() ?: "") }

Column {
    Text(stringResource(R.string.exercise_max_hr_label), color = onBg, fontSize = 14.sp)
    Text(stringResource(R.string.exercise_max_hr_hint), color = outline, fontSize = 12.sp)
    OutlinedTextField(
        value = maxHRText,
        onValueChange = { v ->
            maxHRText = v
            val parsed = v.toIntOrNull()
            if (parsed != null && parsed in 120..220) {
                viewModel.setMaxHeartRate(parsed)
            } else if (v.isBlank()) {
                viewModel.setMaxHeartRate(null)
            }
        },
        label = { Text("BPM") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}
```

- [ ] **Step 6: Verify compilation**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -10`

- [ ] **Step 7: Commit**

```
refactor: update ExerciseSettings to use ExerciseCategory targets and add max HR
```

---

### Task 8: Update ExerciseHistoryScreen and PreActivityAssessorTest

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/ExerciseHistoryScreen.kt`
- Modify: `app/src/test/java/com/psjostrom/strimma/data/calendar/PreActivityAssessorTest.kt`

- [ ] **Step 1: Update PlannedWorkoutCard**

In `ExerciseHistoryScreen.kt`, update `PlannedWorkoutCard` (line ~449-451). Change:
```kotlin
val categoryName = event.category.name.lowercase().replaceFirstChar { it.uppercase() }
val targetLow = glucoseUnit.format(event.category.defaultTargetLowMgdl.toDouble())
val targetHigh = glucoseUnit.format(event.category.defaultTargetHighMgdl.toDouble())
```
to:
```kotlin
val categoryName = event.category.name.lowercase().replaceFirstChar { it.uppercase() }
val targetLow = glucoseUnit.format(event.metabolicProfile.defaultTargetLowMgdl.toDouble())
val targetHigh = glucoseUnit.format(event.metabolicProfile.defaultTargetHighMgdl.toDouble())
```

Remove `WorkoutCategory` import, ensure `ExerciseCategory` is imported.

- [ ] **Step 2: Update PreActivityAssessorTest**

The test helper calls `PreActivityAssessor.assess()` which takes raw floats — no WorkoutCategory dependency. But verify no imports reference WorkoutCategory. The test file should compile as-is since PreActivityAssessor itself doesn't reference WorkoutCategory (it takes `targetLowMgdl: Float`).

Check imports in `PreActivityAssessorTest.kt` and remove any `WorkoutCategory` import if present.

- [ ] **Step 3: Verify full test suite**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```
refactor: update ExerciseHistoryScreen and tests for ExerciseCategory
```

---

### Task 9: Delete WorkoutCategory

**Files:**
- Delete: `app/src/main/java/com/psjostrom/strimma/data/calendar/WorkoutCategory.kt`

- [ ] **Step 1: Verify no remaining references**

Run: `grep -r "WorkoutCategory" app/src/main/java/ app/src/test/java/ --include="*.kt" -l`
Expected: No files (or only WorkoutCategory.kt itself)

- [ ] **Step 2: Delete the file**

```bash
rm app/src/main/java/com/psjostrom/strimma/data/calendar/WorkoutCategory.kt
```

- [ ] **Step 3: Verify full build**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: All tests PASS, clean build

- [ ] **Step 4: Commit**

```
refactor: remove WorkoutCategory — ExerciseCategory is the single category system
```

---

## Phase 2: Exercise Stats

### Task 10: IntensityBand and CategoryStats Data Model

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/data/health/IntensityBand.kt`
- Create: `app/src/main/java/com/psjostrom/strimma/data/health/CategoryStats.kt`

- [ ] **Step 1: Create IntensityBand**

```kotlin
package com.psjostrom.strimma.data.health

enum class IntensityBand(val label: String, val maxHRFraction: ClosedFloatingPointRange<Double>) {
    LIGHT("Light", 0.0..0.65),
    MODERATE("Moderate", 0.65..0.80),
    INTENSE("Intense", 0.80..1.0);

    companion object {
        fun fromAvgHR(avgHR: Int, maxHR: Int): IntensityBand {
            val fraction = avgHR.toDouble() / maxHR
            return when {
                fraction >= 0.80 -> INTENSE
                fraction >= 0.65 -> MODERATE
                else -> LIGHT
            }
        }
    }
}
```

- [ ] **Step 2: Create CategoryStats data classes**

```kotlin
package com.psjostrom.strimma.data.health

import com.psjostrom.strimma.data.calendar.MetabolicProfile

enum class BGBand(val label: String) {
    LOW("Below low"),
    LOW_RANGE("Low range"),
    MID_RANGE("In range"),
    HIGH("Above range");

    companion object {
        private const val MID_THRESHOLD_MGDL = 126  // 7.0 mmol/L
        private const val HIGH_THRESHOLD_MGDL = 180 // 10.0 mmol/L

        fun fromBG(bgMgdl: Int, bgLowMgdl: Double): BGBand = when {
            bgMgdl < bgLowMgdl -> LOW
            bgMgdl < MID_THRESHOLD_MGDL -> LOW_RANGE
            bgMgdl <= HIGH_THRESHOLD_MGDL -> MID_RANGE
            else -> HIGH
        }
    }
}

data class BandStats(
    val sessionCount: Int,
    val avgMinBG: Double,
    val avgDropRate: Double,
    val hypoRate: Double,
    val avgPostNadir: Double?
)

data class CategoryStats(
    val category: ExerciseCategory,
    val metabolicProfile: MetabolicProfile?,
    val sessionCount: Int,
    val avgEntryBG: Double,
    val avgMinBG: Double,
    val avgDropRate: Double,
    val avgDurationMin: Int,
    val hypoCount: Int,
    val hypoRate: Double,
    val avgPostNadir: Double?,
    val avgPostHighest: Double?,
    val postHypoCount: Int,
    val statsByEntryBand: Map<BGBand, BandStats>
)
```

- [ ] **Step 3: Commit**

```
feat: add IntensityBand, BGBand, and CategoryStats data model
```

---

### Task 11: CategoryStatsCalculator

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/data/health/CategoryStatsCalculator.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/data/health/CategoryStatsCalculatorTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.psjostrom.strimma.data.health

import com.psjostrom.strimma.data.calendar.MetabolicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CategoryStatsCalculatorTest {

    private fun context(
        entryBG: Int? = 150,
        minBG: Int? = 120,
        maxDropRate: Double? = 0.8,
        dropPer10Min: List<Double> = listOf(8.0),
        lowestBG: Int? = 110,
        highestBG: Int? = 160,
        postExerciseHypo: Boolean = false,
        avgHR: Int? = null,
        bgCoverage: Double = 90.0
    ) = ExerciseBGContext(
        entryBG = entryBG,
        entryTrend = Trend.STABLE,
        entryStability = 0.5,
        minBG = minBG,
        maxDropRate = maxDropRate,
        dropPer10Min = dropPer10Min,
        lowestBG = lowestBG,
        lowestBGTime = Instant.now(),
        highestBG = highestBG,
        highestBGTime = Instant.now(),
        postExerciseHypo = postExerciseHypo,
        avgHR = avgHR,
        maxHR = avgHR?.let { it + 20 },
        totalSteps = null,
        activeCalories = null,
        bgCoveragePercent = bgCoverage
    )

    private fun session(
        type: Int = 1, // RUNNING
        durationMin: Int = 30
    ): StoredExerciseSession {
        val start = 1_700_000_000_000L
        val end = start + durationMin * 60_000L
        return StoredExerciseSession("id_${start}_$type", type, start, end, null, null, null)
    }

    @Test
    fun `groups sessions by category`() {
        val data = listOf(
            session(type = 1) to context(),   // RUNNING
            session(type = 1) to context(),   // RUNNING
            session(type = 1) to context(),   // RUNNING
            session(type = 2) to context(),   // CYCLING (biking)
            session(type = 2) to context(),   // CYCLING
            session(type = 2) to context(),   // CYCLING
        )
        val results = CategoryStatsCalculator.computeByCategory(data, 72.0)
        assertEquals(2, results.size)
        assertEquals(ExerciseCategory.RUNNING, results[0].category)
        assertEquals(3, results[0].sessionCount)
    }

    @Test
    fun `categories below threshold are excluded`() {
        val data = listOf(
            session(type = 1) to context(), // RUNNING — only 1 session
        )
        val results = CategoryStatsCalculator.computeByCategory(data, 72.0)
        assertTrue(results.isEmpty()) // threshold is 3
    }

    @Test
    fun `computes correct averages`() {
        val data = listOf(
            session(type = 1) to context(entryBG = 150, minBG = 120),
            session(type = 1) to context(entryBG = 180, minBG = 100),
            session(type = 1) to context(entryBG = 160, minBG = 130),
        )
        val stats = CategoryStatsCalculator.computeByCategory(data, 72.0).first()
        assertEquals(163.3, stats.avgEntryBG, 1.0) // (150+180+160)/3
        assertEquals(116.7, stats.avgMinBG, 1.0)   // (120+100+130)/3
    }

    @Test
    fun `computes hypo rate`() {
        val data = listOf(
            session(type = 1) to context(minBG = 120, postExerciseHypo = false),
            session(type = 1) to context(minBG = 60, postExerciseHypo = true),
            session(type = 1) to context(minBG = 130, postExerciseHypo = false),
        )
        val stats = CategoryStatsCalculator.computeByCategory(data, 72.0).first()
        assertEquals(1, stats.hypoCount)
        assertEquals(1.0 / 3.0, stats.hypoRate, 0.01)
    }

    @Test
    fun `groups by entry BG band`() {
        val data = listOf(
            session(type = 1) to context(entryBG = 60),  // LOW (< 72)
            session(type = 1) to context(entryBG = 100), // LOW_RANGE (72-126)
            session(type = 1) to context(entryBG = 110), // LOW_RANGE
            session(type = 1) to context(entryBG = 115), // LOW_RANGE — now 3, shows
            session(type = 1) to context(entryBG = 150), // MID_RANGE (126-180)
        )
        val stats = CategoryStatsCalculator.computeByCategory(data, 72.0).first()
        assertTrue(stats.statsByEntryBand.containsKey(BGBand.LOW_RANGE))
        assertEquals(3, stats.statsByEntryBand[BGBand.LOW_RANGE]!!.sessionCount)
        // LOW has only 1 session — below threshold, excluded
        assertTrue(!stats.statsByEntryBand.containsKey(BGBand.LOW))
    }

    @Test
    fun `sessions with null entryBG are excluded from stats`() {
        val data = listOf(
            session(type = 1) to context(entryBG = null),
            session(type = 1) to context(entryBG = null),
            session(type = 1) to context(entryBG = null),
        )
        val results = CategoryStatsCalculator.computeByCategory(data, 72.0)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `computeByProfile groups across activity types`() {
        val data = listOf(
            session(type = 1) to context(), // RUNNING → AEROBIC
            session(type = 1) to context(),
            session(type = 1) to context(),
            session(type = 2) to context(), // CYCLING → AEROBIC
            session(type = 2) to context(),
            session(type = 2) to context(),
        )
        val results = CategoryStatsCalculator.computeByProfile(data, 72.0, null)
        assertEquals(1, results.size) // Both are AEROBIC
        assertEquals(MetabolicProfile.AEROBIC, results[0].metabolicProfile)
        assertEquals(6, results[0].sessionCount)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.data.health.CategoryStatsCalculatorTest" 2>&1 | tail -5`
Expected: Compilation error — CategoryStatsCalculator doesn't exist

- [ ] **Step 3: Write the implementation**

```kotlin
package com.psjostrom.strimma.data.health

import com.psjostrom.strimma.data.calendar.MetabolicProfile

object CategoryStatsCalculator {

    private const val MIN_SESSIONS = 3
    private const val MS_PER_MINUTE = 60_000.0

    fun computeByCategory(
        data: List<Pair<StoredExerciseSession, ExerciseBGContext>>,
        bgLowMgdl: Double
    ): List<CategoryStats> {
        val withEntry = data.filter { it.second.entryBG != null }

        return withEntry
            .groupBy { ExerciseCategory.fromHCType(it.first.type) }
            .filter { it.value.size >= MIN_SESSIONS }
            .map { (category, sessions) ->
                buildStats(category, null, sessions, bgLowMgdl)
            }
            .sortedByDescending { it.sessionCount }
    }

    fun computeByProfile(
        data: List<Pair<StoredExerciseSession, ExerciseBGContext>>,
        bgLowMgdl: Double,
        maxHR: Int?
    ): List<CategoryStats> {
        val withEntry = data.filter { it.second.entryBG != null }

        return withEntry
            .groupBy { resolveProfile(it.first, it.second, maxHR) }
            .filter { it.value.size >= MIN_SESSIONS }
            .map { (profile, sessions) ->
                buildStats(ExerciseCategory.OTHER, profile, sessions, bgLowMgdl)
            }
            .sortedByDescending { it.sessionCount }
    }

    private fun resolveProfile(
        session: StoredExerciseSession,
        context: ExerciseBGContext,
        maxHR: Int?
    ): MetabolicProfile {
        val category = ExerciseCategory.fromHCType(session.type)
        // STRENGTH/CLIMBING always RESISTANCE regardless of HR
        if (category == ExerciseCategory.STRENGTH || category == ExerciseCategory.CLIMBING) {
            return MetabolicProfile.RESISTANCE
        }
        // HR-based override if available
        if (maxHR != null && context.avgHR != null && context.avgHR > 0) {
            val fraction = context.avgHR.toDouble() / maxHR
            if (fraction >= 0.80) return MetabolicProfile.HIGH_INTENSITY
        }
        return category.defaultMetabolicProfile
    }

    private fun buildStats(
        category: ExerciseCategory,
        profile: MetabolicProfile?,
        sessions: List<Pair<StoredExerciseSession, ExerciseBGContext>>,
        bgLowMgdl: Double
    ): CategoryStats {
        val contexts = sessions.map { it.second }
        val entryBGs = contexts.mapNotNull { it.entryBG }
        val minBGs = contexts.mapNotNull { it.minBG }
        val dropRates = contexts.flatMap { it.dropPer10Min }
        val durations = sessions.map {
            ((it.first.endTime - it.first.startTime) / MS_PER_MINUTE).toInt()
        }
        val postNadirs = contexts.mapNotNull { it.lowestBG }
        val postHighests = contexts.mapNotNull { it.highestBG }

        val hypoCount = contexts.count { ctx ->
            (ctx.minBG != null && ctx.minBG < bgLowMgdl) || ctx.postExerciseHypo
        }
        val postHypoCount = contexts.count { it.postExerciseHypo }

        val bandGroups = sessions
            .filter { it.second.entryBG != null }
            .groupBy { BGBand.fromBG(it.second.entryBG!!, bgLowMgdl) }

        val statsByBand = bandGroups
            .filter { it.value.size >= MIN_SESSIONS }
            .mapValues { (_, bandSessions) ->
                val bc = bandSessions.map { it.second }
                BandStats(
                    sessionCount = bc.size,
                    avgMinBG = bc.mapNotNull { it.minBG }.average(),
                    avgDropRate = bc.flatMap { it.dropPer10Min }.let {
                        if (it.isEmpty()) 0.0 else it.average()
                    },
                    hypoRate = bc.count { c ->
                        (c.minBG != null && c.minBG < bgLowMgdl) || c.postExerciseHypo
                    }.toDouble() / bc.size,
                    avgPostNadir = bc.mapNotNull { it.lowestBG }
                        .let { if (it.isEmpty()) null else it.average() }
                )
            }

        return CategoryStats(
            category = category,
            metabolicProfile = profile,
            sessionCount = sessions.size,
            avgEntryBG = if (entryBGs.isEmpty()) 0.0 else entryBGs.average(),
            avgMinBG = if (minBGs.isEmpty()) 0.0 else minBGs.average(),
            avgDropRate = if (dropRates.isEmpty()) 0.0 else dropRates.average(),
            avgDurationMin = if (durations.isEmpty()) 0 else durations.average().toInt(),
            hypoCount = hypoCount,
            hypoRate = if (sessions.isEmpty()) 0.0 else hypoCount.toDouble() / sessions.size,
            avgPostNadir = if (postNadirs.isEmpty()) null else postNadirs.average(),
            avgPostHighest = if (postHighests.isEmpty()) null else postHighests.average(),
            postHypoCount = postHypoCount,
            statsByEntryBand = statsByBand
        )
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew testDebugUnitTest --tests "com.psjostrom.strimma.data.health.CategoryStatsCalculatorTest" 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```
feat: add CategoryStatsCalculator for per-category BG pattern aggregation
```

---

### Task 12: Patterns Tab UI

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/ExerciseHistoryScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add string resources**

```xml
<string name="exercise_tab_patterns">Patterns</string>
<string name="exercise_patterns_empty">Keep exercising with Strimma running. After a few sessions of the same type, you\'ll see your personal BG patterns here.</string>
<string name="exercise_patterns_by_activity">By Activity</string>
<string name="exercise_patterns_by_profile">By Profile</string>
<string name="exercise_patterns_avg_drop">Avg drop</string>
<string name="exercise_patterns_typical_low">Typical low</string>
<string name="exercise_patterns_hypo_risk">Hypo risk</string>
<string name="exercise_patterns_per_10min">per 10 min</string>
<string name="exercise_patterns_sessions">sessions</string>
<string name="exercise_patterns_by_starting_bg">By starting BG:</string>
```

- [ ] **Step 2: Add Patterns tab to ExerciseHistoryScreen**

Update the tabs list (line ~164) to add a third tab:
```kotlin
val tabs = listOf(
    stringResource(R.string.exercise_tab_planned),
    stringResource(R.string.exercise_tab_completed),
    stringResource(R.string.exercise_tab_patterns)
)
```

Add to the `when` block (line ~246):
```kotlin
2 -> PatternsTab(viewModel = viewModel, glucoseUnit = glucoseUnit)
```

- [ ] **Step 3: Add ViewModel methods for patterns data**

In `ExerciseHistoryViewModel`, add:
```kotlin
val maxHeartRate: StateFlow<Int?> = settings.maxHeartRate
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

suspend fun computeAllBGContexts(): List<Pair<StoredExerciseSession, ExerciseBGContext>> {
    val allSessions = exerciseDao.getAllSessionsList()
    return allSessions.mapNotNull { session ->
        computeBGContext(session)?.let { ctx -> session to ctx }
    }
}
```

Add to `ExerciseDao`:
```kotlin
@Query("SELECT * FROM exercise_sessions ORDER BY startTime DESC")
suspend fun getAllSessionsList(): List<StoredExerciseSession>
```

- [ ] **Step 4: Write PatternsTab composable**

Add at the bottom of `ExerciseHistoryScreen.kt`:

```kotlin
@Composable
private fun PatternsTab(
    viewModel: ExerciseHistoryViewModel,
    glucoseUnit: GlucoseUnit
) {
    val bgLow by viewModel.bgLow.collectAsState()
    val maxHR by viewModel.maxHeartRate.collectAsState()

    var categoryStats by remember { mutableStateOf<List<CategoryStats>>(emptyList()) }
    var profileStats by remember { mutableStateOf<List<CategoryStats>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var viewMode by remember { mutableIntStateOf(0) } // 0 = By Activity, 1 = By Profile

    LaunchedEffect(Unit) {
        val data = viewModel.computeAllBGContexts()
        categoryStats = CategoryStatsCalculator.computeByCategory(data, bgLow.toDouble())
        profileStats = CategoryStatsCalculator.computeByProfile(data, bgLow.toDouble(), maxHR)
        loaded = true
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val stats = if (viewMode == 0) categoryStats else profileStats

    Column(modifier = Modifier.fillMaxSize()) {
        // View mode toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = viewMode == 0,
                    onClick = { viewMode = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text(stringResource(R.string.exercise_patterns_by_activity), fontSize = 13.sp) }
                SegmentedButton(
                    selected = viewMode == 1,
                    onClick = { viewMode = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text(stringResource(R.string.exercise_patterns_by_profile), fontSize = 13.sp) }
            }
        }

        if (stats.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.exercise_patterns_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp, lineHeight = 20.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(stats, key = { "${it.category}_${it.metabolicProfile}" }) { stat ->
                    PatternCard(stat = stat, glucoseUnit = glucoseUnit, isProfileView = viewMode == 1)
                }
            }
        }
    }
}

@Composable
private fun PatternCard(
    stat: CategoryStats,
    glucoseUnit: GlucoseUnit,
    isProfileView: Boolean
) {
    val title = if (isProfileView) {
        stat.metabolicProfile?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Unknown"
    } else {
        "${stat.category.emoji} ${stat.category.name.lowercase().replaceFirstChar { it.uppercase() }}"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${stat.sessionCount} ${stringResource(R.string.exercise_patterns_sessions)}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            val dropText = "${glucoseUnit.format(stat.avgDropRate)} ${stringResource(R.string.exercise_patterns_per_10min)}"
            Text(
                "${stringResource(R.string.exercise_patterns_avg_drop)}: $dropText",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${stringResource(R.string.exercise_patterns_typical_low)}: ${glucoseUnit.format(stat.avgMinBG)}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            val hypoColor = if (stat.hypoRate > 0.25) BelowLow else MaterialTheme.colorScheme.onSurface
            Text(
                "${stringResource(R.string.exercise_patterns_hypo_risk)}: ${"%.0f".format(stat.hypoRate * 100)}%  (${stat.hypoCount} of ${stat.sessionCount})",
                fontSize = 13.sp,
                color = hypoColor
            )

            // Entry BG band breakdown
            if (stat.statsByEntryBand.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.exercise_patterns_by_starting_bg),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                for ((band, bandStats) in stat.statsByEntryBand.entries.sortedBy { it.key.ordinal }) {
                    val bandColor = when (band) {
                        BGBand.LOW -> BelowLow
                        BGBand.LOW_RANGE -> BelowLow.copy(alpha = 0.7f)
                        BGBand.MID_RANGE -> InRange
                        BGBand.HIGH -> AboveHigh
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            band.label,
                            fontSize = 12.sp,
                            color = bandColor,
                            modifier = Modifier.width(80.dp)
                        )
                        Text(
                            "${bandStats.sessionCount}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "low: ${glucoseUnit.format(bandStats.avgMinBG)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val bHypoColor = if (bandStats.hypoRate > 0.25) BelowLow
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        Text(
                            "${"%.0f".format(bandStats.hypoRate * 100)}%",
                            fontSize = 12.sp,
                            color = bHypoColor
                        )
                    }
                }
            }
        }
    }
}
```

Add necessary imports at the top of `ExerciseHistoryScreen.kt`:
```kotlin
import com.psjostrom.strimma.data.health.CategoryStats
import com.psjostrom.strimma.data.health.CategoryStatsCalculator
import com.psjostrom.strimma.data.health.BGBand
import com.psjostrom.strimma.ui.theme.AboveHigh
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.CircularProgressIndicator
```

- [ ] **Step 5: Verify build**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -10`
Expected: Clean build

- [ ] **Step 6: Commit**

```
feat: add Patterns tab with per-category and per-profile BG stats
```

---

### Task 13: Final Build Verification

- [ ] **Step 1: Run full test suite**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -30`
Expected: All tests PASS

- [ ] **Step 2: Run lint**

Run: `./gradlew detekt 2>&1 | tail -20`
Expected: No new violations (suppress if needed for assessment complexity)

- [ ] **Step 3: Build debug APK**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit any final fixes**

If any lint or build fixes were needed, commit them:
```
chore: fix lint warnings from exercise stats implementation
```
