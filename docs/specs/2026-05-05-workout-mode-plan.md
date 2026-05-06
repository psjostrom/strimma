# Workout Mode — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a workout mode that, while active, replaces both alert thresholds and the in-range display band with a separate set tuned for exercise. Triggered manually or by an active calendar event.

**Architecture:** A new Hilt singleton `WorkoutModeManager` owns the state machine (manual + calendar inputs combined into `Off` / `On(MANUAL|CALENDAR)`) and exposes `effectiveThresholds: StateFlow<EffectiveThresholds>`. All threshold consumers (AlertManager, MainViewModel, StrimmaService, widget, web server, story) read from this single flow instead of `settings.bgLow/bgHigh/alert*` directly.

**Tech Stack:** Kotlin, Coroutines/Flow, Hilt, DataStore, Room, Jetpack Compose, Material 3, Robolectric for tests

**Spec:** `docs/specs/2026-05-05-workout-mode-design.md`

---

## File Map

All source paths relative to `app/src/main/java/com/psjostrom/strimma/`.
All test paths relative to `app/src/test/java/com/psjostrom/strimma/`.

| File | Action | Responsibility |
|------|--------|----------------|
| `data/workout/WorkoutMode.kt` | Create | Sealed class for runtime state (Off / On(source, sinceMs, expiresAtMs)) |
| `data/workout/EffectiveThresholds.kt` | Create | Value type carrying display + alert thresholds (6 fields) |
| `data/workout/WorkoutModeManager.kt` | Create | Hilt singleton; state machine; threshold derivation |
| `data/SettingsRepository.kt` | Modify | Add 4 workout threshold keys + maxHours + manualSinceMs + overrideUntilMs flows/setters |
| `notification/AlertManager.kt` | Modify | Inject manager; use `effectiveThresholds.value` in checkReading/Low/High; suppress stale when On |
| `notification/NotificationHelper.kt` | Modify | Inject manager; render title suffix + subtext + action button |
| `notification/WorkoutModeReceiver.kt` | Create | Handles notification action tap; calls `manager.toggle()` |
| `service/StrimmaService.kt` | Modify | Derive bgLow/bgHigh from effectiveThresholds; observe state, refresh notification on change |
| `ui/MainViewModel.kt` | Modify | Inject manager; expose state + effectiveThresholds; replace bgLow/bgHigh sources |
| `ui/MainScreen.kt` | Modify | Add WorkoutModePill to status row alongside Pause All |
| `ui/components/WorkoutModePill.kt` | Create | Composable pill with OFF chip / ON filled variant + elapsed time |
| `ui/settings/WorkoutSettings.kt` | Create | Composable for Settings → Exercise → Workout mode |
| `ui/settings/WorkoutSettingsViewModel.kt` | Create | Exposes thresholds + maxHours + setters |
| `ui/settings/SettingsScreen.kt` | Modify | Wire WorkoutSettings into the Exercise group |
| `widget/StrimmaWidget.kt` | Modify | Use Hilt EntryPointAccessors to read effectiveThresholds.value instead of settings.bgLow/bgHigh |
| `widget/WidgetEntryPoint.kt` | Create | Hilt @EntryPoint exposing WorkoutModeManager to non-Hilt-managed widget |
| `webserver/LocalWebServer.kt` | Modify | Inject WorkoutModeManager; read effectiveThresholds.value instead of settings.bgLow/bgHigh |
| `ui/story/StoryViewModel.kt` | Modify | Same as widget |
| `app/src/main/AndroidManifest.xml` | Modify | Register `WorkoutModeReceiver` |
| `app/src/main/res/values/strings.xml` | Modify | Add 12 i18n keys for workout mode UI |
| `app/src/main/res/values-sv/strings.xml` | Modify | Swedish translations for the same 12 keys |
| `data/workout/WorkoutModeManagerTest.kt` (test/) | Create | State machine + effectiveThresholds tests |
| `notification/AlertManagerWorkoutTest.kt` (test/) | Create | AlertManager + WorkoutModeManager integration tests |
| `notification/WorkoutNotificationActionTest.kt` (test/) | Create | Manager toggle contract test |
| `di/AppModule.kt` | Modify | Add @Provides for the application-scope CoroutineScope |
| `docs/guide/workout-mode.md` | Create | User guide |
| `docs/internal/spec.md` | Modify | Add Workout Mode section |

---

## Phase 1: Foundation (data layer, types, settings)

### Task 1: Create the WorkoutMode sealed class

**Files:**
- Create: `data/workout/WorkoutMode.kt`
- Create: `data/workout/` (new package directory)

- [ ] **Step 1: Create the file**

```kotlin
package com.psjostrom.strimma.data.workout

/**
 * Runtime state of workout mode. Timestamps are epoch milliseconds (Long),
 * matching Strimma's existing convention.
 */
sealed class WorkoutMode {
    data object Off : WorkoutMode()

    data class On(
        val source: Source,
        val sinceMs: Long,
        /** For MANUAL: sinceMs + maxHours*MS_PER_HOUR. For CALENDAR: event.endTime. */
        val expiresAtMs: Long
    ) : WorkoutMode() {
        enum class Source { MANUAL, CALENDAR }
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/data/workout/WorkoutMode.kt
git commit -m "feat(workout): add WorkoutMode sealed class for runtime state"
```

---

### Task 2: Create the EffectiveThresholds data class

**Files:**
- Create: `data/workout/EffectiveThresholds.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.psjostrom.strimma.data.workout

/**
 * Combined threshold set used by every BG-display and alert consumer.
 *
 * In normal mode (workout Off), display fields come from settings.bgLow/bgHigh
 * while alert fields come from settings.alertLow/High/UrgentLow/UrgentHigh —
 * they are independent settings.
 *
 * In workout mode (On), all 6 fields are derived from the 4 workoutAlert*
 * settings: workoutAlertLow drives both displayLow AND alertLow, etc.
 */
data class EffectiveThresholds(
    // Display: graph in-range band, hero color, widget, web server, story view
    val displayLowMgdl: Float,
    val displayHighMgdl: Float,
    // Alerts: low/high firing
    val alertLowMgdl: Float,
    val alertHighMgdl: Float,
    val alertUrgentLowMgdl: Float,
    val alertUrgentHighMgdl: Float,
)
```

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/data/workout/EffectiveThresholds.kt
git commit -m "feat(workout): add EffectiveThresholds value type"
```

---

### Task 3: Add workout settings to SettingsRepository

**Files:**
- Modify: `data/SettingsRepository.kt`

- [ ] **Step 1: Add DataStore keys + defaults**

Add inside the `companion object` block (locate the `KEY_WORKOUT_*` block near line 178–190 and append after the existing entries):

```kotlin
// --- Workout mode (added for workout-mode feature) ---
// Workout thresholds (stored as mg/dL; doubles as both display and alert)
private val KEY_WORKOUT_ALERT_LOW = floatPreferencesKey("workout_alert_low")
private val KEY_WORKOUT_ALERT_HIGH = floatPreferencesKey("workout_alert_high")
private val KEY_WORKOUT_ALERT_URGENT_LOW = floatPreferencesKey("workout_alert_urgent_low")
private val KEY_WORKOUT_ALERT_URGENT_HIGH = floatPreferencesKey("workout_alert_urgent_high")

// Defaults: 6.0 / 5.0 / 14.0 / 16.0 mmol → mg/dL via factor 18.0182
const val DEFAULT_WORKOUT_ALERT_LOW = 108f
const val DEFAULT_WORKOUT_ALERT_URGENT_LOW = 90f
const val DEFAULT_WORKOUT_ALERT_HIGH = 252f
const val DEFAULT_WORKOUT_ALERT_URGENT_HIGH = 288f

// Manual safety timeout (1–12 hours)
private val KEY_WORKOUT_MODE_MAX_HOURS = intPreferencesKey("workout_mode_max_hours")
const val DEFAULT_WORKOUT_MODE_MAX_HOURS = 3

// Runtime state (set by WorkoutModeManager). Sentinel 0L = absent.
private val KEY_MANUAL_WORKOUT_SINCE_MS = longPreferencesKey("manual_workout_since_ms")
private val KEY_MANUAL_OFF_OVERRIDE_UNTIL_MS = longPreferencesKey("manual_off_override_until_ms")
```

- [ ] **Step 2: Add Flow properties + setters**

Add near the other threshold flows (around line 240, after `alertUrgentHigh` flow):

```kotlin
// --- Workout thresholds ---
val workoutAlertLow: Flow<Float> = dataStore.data.map { it[KEY_WORKOUT_ALERT_LOW] ?: DEFAULT_WORKOUT_ALERT_LOW }
val workoutAlertHigh: Flow<Float> = dataStore.data.map { it[KEY_WORKOUT_ALERT_HIGH] ?: DEFAULT_WORKOUT_ALERT_HIGH }
val workoutAlertUrgentLow: Flow<Float> = dataStore.data.map { it[KEY_WORKOUT_ALERT_URGENT_LOW] ?: DEFAULT_WORKOUT_ALERT_URGENT_LOW }
val workoutAlertUrgentHigh: Flow<Float> = dataStore.data.map { it[KEY_WORKOUT_ALERT_URGENT_HIGH] ?: DEFAULT_WORKOUT_ALERT_URGENT_HIGH }

suspend fun setWorkoutAlertLow(mgdl: Float) { dataStore.edit { it[KEY_WORKOUT_ALERT_LOW] = mgdl } }
suspend fun setWorkoutAlertHigh(mgdl: Float) { dataStore.edit { it[KEY_WORKOUT_ALERT_HIGH] = mgdl } }
suspend fun setWorkoutAlertUrgentLow(mgdl: Float) { dataStore.edit { it[KEY_WORKOUT_ALERT_URGENT_LOW] = mgdl } }
suspend fun setWorkoutAlertUrgentHigh(mgdl: Float) { dataStore.edit { it[KEY_WORKOUT_ALERT_URGENT_HIGH] = mgdl } }

// --- Workout safety timeout ---
val workoutModeMaxHours: Flow<Int> = dataStore.data.map { it[KEY_WORKOUT_MODE_MAX_HOURS] ?: DEFAULT_WORKOUT_MODE_MAX_HOURS }
suspend fun setWorkoutModeMaxHours(hours: Int) { dataStore.edit { it[KEY_WORKOUT_MODE_MAX_HOURS] = hours } }

// --- Workout runtime state. Sentinel 0L = absent (mapped to null). ---
val manualWorkoutSinceMs: Flow<Long?> = dataStore.data.map {
    it[KEY_MANUAL_WORKOUT_SINCE_MS]?.takeIf { v -> v != 0L }
}
suspend fun setManualWorkoutSinceMs(ms: Long?) {
    dataStore.edit { it[KEY_MANUAL_WORKOUT_SINCE_MS] = ms ?: 0L }
}

val manualOffOverrideUntilMs: Flow<Long?> = dataStore.data.map {
    it[KEY_MANUAL_OFF_OVERRIDE_UNTIL_MS]?.takeIf { v -> v != 0L }
}
suspend fun setManualOffOverrideUntilMs(ms: Long?) {
    dataStore.edit { it[KEY_MANUAL_OFF_OVERRIDE_UNTIL_MS] = ms ?: 0L }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/data/SettingsRepository.kt
git commit -m "feat(workout): add workout-mode settings (thresholds, max-hours, runtime state)"
```

---

### Task 4: Write WorkoutModeManager state-machine tests (RED)

**Files:**
- Create: `app/src/test/java/com/psjostrom/strimma/data/workout/WorkoutModeManagerTest.kt`

- [ ] **Step 1: Create the test file (will fail to compile — manager doesn't exist yet)**

```kotlin
package com.psjostrom.strimma.data.workout

import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.calendar.CalendarPoller
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import com.psjostrom.strimma.data.health.ExerciseCategory
import com.psjostrom.strimma.data.calendar.MetabolicProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkoutModeManagerTest {

    private val MS_PER_HOUR = 3600_000L
    private val baseNowMs = 1_700_000_000_000L  // arbitrary fixed epoch ms

    /**
     * Builds a manager with isolated DataStore + injectable clock + fake calendar.
     * Returns (manager, settings, fakeCalendarFlow, clock).
     */
    private fun setup(testScope: TestScope): TestRig {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dataStore = createTestDataStore(testScope)
        val widgetSettings = com.psjostrom.strimma.widget.WidgetSettingsRepository(context)
        val settings = SettingsRepository(context, widgetSettings, dataStore)
        val clock = MutableClock(baseNowMs)
        val nextEventFlow = MutableStateFlow<WorkoutEvent?>(null)
        val fakePoller = FakeCalendarPoller(nextEventFlow)
        val manager = WorkoutModeManager(settings, fakePoller, clock, testScope)
        return TestRig(manager, settings, nextEventFlow, clock)
    }

    private data class TestRig(
        val manager: WorkoutModeManager,
        val settings: SettingsRepository,
        val nextEventFlow: MutableStateFlow<WorkoutEvent?>,
        val clock: MutableClock
    )

    private fun event(startMs: Long, endMs: Long): WorkoutEvent = WorkoutEvent(
        title = "Test event",
        startTime = startMs,
        endTime = endMs,
        category = ExerciseCategory.RUNNING,
        metabolicProfile = MetabolicProfile.AEROBIC,
        calendarId = 1L
    )

    @Test
    fun `initial state is Off`() = runTest {
        val rig = setup(this)
        assertEquals(WorkoutMode.Off, rig.manager.state.first())
    }

    @Test
    fun `setManualOn produces On(MANUAL) with expiresAtMs = now plus maxHours`() = runTest {
        val rig = setup(this)
        rig.manager.setManualOn()
        val state = rig.manager.state.first()
        assertTrue(state is WorkoutMode.On)
        val on = state as WorkoutMode.On
        assertEquals(WorkoutMode.On.Source.MANUAL, on.source)
        assertEquals(baseNowMs, on.sinceMs)
        assertEquals(baseNowMs + 3 * MS_PER_HOUR, on.expiresAtMs)
    }

    @Test
    fun `manual ON expires after maxHours and clears manualSinceMs`() = runTest {
        val rig = setup(this)
        rig.manager.setManualOn()
        rig.clock.nowMs = baseNowMs + 3 * MS_PER_HOUR + 1
        // ticker fires every 30s; advance enough to trigger one tick
        advanceTimeBy(35_000L)
        assertEquals(WorkoutMode.Off, rig.manager.state.first())
        assertEquals(null, rig.settings.manualWorkoutSinceMs.first())
    }

    @Test
    fun `calendar event currently active triggers On(CALENDAR)`() = runTest {
        val rig = setup(this)
        val ev = event(startMs = baseNowMs - 1000, endMs = baseNowMs + 60 * MS_PER_HOUR)
        rig.nextEventFlow.value = ev
        advanceTimeBy(35_000L)
        val state = rig.manager.state.first()
        assertTrue(state is WorkoutMode.On)
        val on = state as WorkoutMode.On
        assertEquals(WorkoutMode.On.Source.CALENDAR, on.source)
        assertEquals(ev.startTime, on.sinceMs)
        assertEquals(ev.endTime, on.expiresAtMs)
    }

    @Test
    fun `future calendar event does not trigger`() = runTest {
        val rig = setup(this)
        rig.nextEventFlow.value = event(startMs = baseNowMs + MS_PER_HOUR, endMs = baseNowMs + 2 * MS_PER_HOUR)
        advanceTimeBy(35_000L)
        assertEquals(WorkoutMode.Off, rig.manager.state.first())
    }

    @Test
    fun `manual OFF during active calendar sets overrideUntilMs to event end`() = runTest {
        val rig = setup(this)
        val ev = event(startMs = baseNowMs - 1000, endMs = baseNowMs + MS_PER_HOUR)
        rig.nextEventFlow.value = ev
        advanceTimeBy(35_000L)
        // Now state is On(CALENDAR)
        rig.manager.setManualOff()
        assertEquals(WorkoutMode.Off, rig.manager.state.first())
        assertEquals(ev.endTime, rig.settings.manualOffOverrideUntilMs.first())
    }

    @Test
    fun `manual ON wins over active calendar event`() = runTest {
        val rig = setup(this)
        val ev = event(startMs = baseNowMs - 1000, endMs = baseNowMs + MS_PER_HOUR)
        rig.nextEventFlow.value = ev
        rig.manager.setManualOn()
        val state = rig.manager.state.first()
        assertEquals(WorkoutMode.On.Source.MANUAL, (state as WorkoutMode.On).source)
    }

    @Test
    fun `manual ON expiring while calendar still active transitions seamlessly to CALENDAR`() = runTest {
        val rig = setup(this)
        val ev = event(startMs = baseNowMs - 1000, endMs = baseNowMs + 4 * MS_PER_HOUR)
        rig.nextEventFlow.value = ev
        rig.manager.setManualOn()  // expires at baseNowMs + 3h
        rig.clock.nowMs = baseNowMs + 3 * MS_PER_HOUR + 1
        advanceTimeBy(35_000L)
        val state = rig.manager.state.first()
        assertTrue(state is WorkoutMode.On)
        assertEquals(WorkoutMode.On.Source.CALENDAR, (state as WorkoutMode.On).source)
    }

    @Test
    fun `effectiveThresholds in Off mode uses settings bg+alert values`() = runTest {
        val rig = setup(this)
        val t = rig.manager.effectiveThresholds.first()
        // Defaults from SettingsRepository.kt:218-223
        assertEquals(72f, t.displayLowMgdl)
        assertEquals(180f, t.displayHighMgdl)
        assertEquals(72f, t.alertLowMgdl)
        assertEquals(180f, t.alertHighMgdl)
        assertEquals(54f, t.alertUrgentLowMgdl)
        assertEquals(234f, t.alertUrgentHighMgdl)
    }

    @Test
    fun `effectiveThresholds in On mode uses workout values for both display and alerts`() = runTest {
        val rig = setup(this)
        rig.manager.setManualOn()
        val t = rig.manager.effectiveThresholds.first()
        // Defaults from SettingsRepository workout block
        assertEquals(108f, t.displayLowMgdl)
        assertEquals(252f, t.displayHighMgdl)
        assertEquals(108f, t.alertLowMgdl)
        assertEquals(252f, t.alertHighMgdl)
        assertEquals(90f, t.alertUrgentLowMgdl)
        assertEquals(288f, t.alertUrgentHighMgdl)
    }
}

/** Mutable clock for tests so we can advance time without virtual-time tricks. */
class MutableClock(var nowMs: Long) : Clock {
    override fun nowMs(): Long = nowMs
}

/** Hand-written test double mirroring CalendarPoller's nextEvent flow. */
class FakeCalendarPoller(
    val nextEventFlow: MutableStateFlow<WorkoutEvent?>
) : CalendarPollerSource {
    override val nextEvent get() = nextEventFlow
}
```

- [ ] **Step 2: Run tests to confirm RED (compilation errors expected)**

Run: `./gradlew testDebugUnitTest --tests 'com.psjostrom.strimma.data.workout.WorkoutModeManagerTest' 2>&1 | tail -20`
Expected: Compilation errors — `WorkoutModeManager`, `Clock`, `CalendarPollerSource` unresolved.

- [ ] **Step 3: Commit the failing test**

```bash
git add app/src/test/java/com/psjostrom/strimma/data/workout/WorkoutModeManagerTest.kt
git commit -m "test(workout): add WorkoutModeManager tests (RED)"
```

---

### Task 5: Add Clock + CalendarPollerSource abstractions

**Why:** Tests need to inject a mutable clock and a fake calendar source. Production code uses real clock + real CalendarPoller. Abstractions live in `data/workout/` because they're internal seams for this feature.

**Files:**
- Create: `data/workout/Clock.kt`
- Create: `data/workout/CalendarPollerSource.kt`
- Modify: `data/calendar/CalendarPoller.kt` (add interface implementation)

- [ ] **Step 1: Create Clock interface + system implementation**

```kotlin
// data/workout/Clock.kt
package com.psjostrom.strimma.data.workout

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface Clock {
    fun nowMs(): Long
}

@Singleton
class SystemClock @Inject constructor() : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock
}
```

- [ ] **Step 2: Create CalendarPollerSource interface**

```kotlin
// data/workout/CalendarPollerSource.kt
package com.psjostrom.strimma.data.workout

import com.psjostrom.strimma.data.calendar.WorkoutEvent
import kotlinx.coroutines.flow.StateFlow

/**
 * Seam over CalendarPoller for testability. Production binds to CalendarPoller;
 * tests provide a hand-written fake.
 */
interface CalendarPollerSource {
    val nextEvent: StateFlow<WorkoutEvent?>
}
```

- [ ] **Step 3: Make CalendarPoller implement the interface**

Modify `data/calendar/CalendarPoller.kt`. Change the class signature and add the import:

```kotlin
import com.psjostrom.strimma.data.workout.CalendarPollerSource
// ...
@Singleton
class CalendarPoller @Inject constructor(
    private val calendarReader: CalendarReader,
    private val settings: SettingsRepository
) : CalendarPollerSource {
    // existing implementation unchanged — nextEvent already matches interface
```

- [ ] **Step 4: Add Hilt binding for CalendarPollerSource**

Add to `ClockModule.kt` (same file is fine):

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class CalendarPollerSourceModule {
    @Binds
    @Singleton
    abstract fun bindCalendarPollerSource(impl: com.psjostrom.strimma.data.calendar.CalendarPoller): CalendarPollerSource
}
```

- [ ] **Step 5: Build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/data/workout/Clock.kt \
        app/src/main/java/com/psjostrom/strimma/data/workout/CalendarPollerSource.kt \
        app/src/main/java/com/psjostrom/strimma/data/calendar/CalendarPoller.kt
git commit -m "feat(workout): add Clock + CalendarPollerSource testability seams"
```

---

### Task 6: Implement WorkoutModeManager (GREEN)

**Files:**
- Create: `data/workout/WorkoutModeManager.kt`

- [ ] **Step 1: Create the manager**

```kotlin
package com.psjostrom.strimma.data.workout

import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val MS_PER_HOUR = 3_600_000L
private const val TICK_INTERVAL_MS = 30_000L

/**
 * Single source of truth for workout-mode state and the threshold set every
 * downstream consumer should use.
 *
 * State machine (rules in priority order, see spec for full table):
 *   R1. manualSinceMs != null && now < manualExpiresAtMs       → On(MANUAL)
 *   R3. overrideUntilMs != null && now < overrideUntilMs       → Off
 *   R5. nextEvent != null && start <= now <= end               → On(CALENDAR)
 *   else                                                        → Off
 *
 * R2 (manual expired) and R4 (override expired) are handled by a separate
 * cleanup launch driven by the same ticker, so the state-derivation flow
 * stays a pure function of its inputs.
 */
@Singleton
class WorkoutModeManager @Inject constructor(
    private val settings: SettingsRepository,
    private val calendarSource: CalendarPollerSource,
    private val clock: Clock,
    private val scope: CoroutineScope
) {
    private val ticker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_INTERVAL_MS)
        }
    }

    val state: StateFlow<WorkoutMode> = combine(
        settings.manualWorkoutSinceMs,
        settings.manualOffOverrideUntilMs,
        settings.workoutModeMaxHours,
        calendarSource.nextEvent,
        ticker
    ) { manualSince, overrideUntil, maxHours, nextEvent, _ ->
        computeState(manualSince, overrideUntil, maxHours, nextEvent, clock.nowMs())
    }.stateIn(scope, SharingStarted.Eagerly, WorkoutMode.Off)

    /** Pure state derivation — no side effects, fully testable in isolation. */
    internal fun computeState(
        manualSince: Long?,
        overrideUntil: Long?,
        maxHours: Int,
        nextEvent: WorkoutEvent?,
        now: Long
    ): WorkoutMode {
        val manualExpiresAt = manualSince?.let { it + maxHours * MS_PER_HOUR }
        val isCalendarActive = nextEvent != null
            && now >= nextEvent.startTime
            && now <= nextEvent.endTime

        // R1: manual ON, not yet expired
        if (manualSince != null && manualExpiresAt != null && now < manualExpiresAt) {
            return WorkoutMode.On(
                source = WorkoutMode.On.Source.MANUAL,
                sinceMs = manualSince,
                expiresAtMs = manualExpiresAt
            )
        }
        // R3: manual OFF override active
        if (overrideUntil != null && now < overrideUntil) {
            return WorkoutMode.Off
        }
        // R5: calendar event currently active
        if (isCalendarActive && nextEvent != null) {
            return WorkoutMode.On(
                source = WorkoutMode.On.Source.CALENDAR,
                sinceMs = nextEvent.startTime,
                expiresAtMs = nextEvent.endTime
            )
        }
        return WorkoutMode.Off
    }

    /** Combined snapshot of all threshold settings. Builds via vararg combine. */
    private val thresholdSnapshot: Flow<ThresholdSnapshot> = combine(
        settings.bgLow,                    // [0]
        settings.bgHigh,                   // [1]
        settings.alertLow,                 // [2]
        settings.alertHigh,                // [3]
        settings.alertUrgentLow,           // [4]
        settings.alertUrgentHigh,          // [5]
        settings.workoutAlertLow,          // [6]
        settings.workoutAlertHigh,         // [7]
        settings.workoutAlertUrgentLow,    // [8]
        settings.workoutAlertUrgentHigh    // [9]
    ) { values: Array<Float> ->
        ThresholdSnapshot(
            bgLow = values[0],
            bgHigh = values[1],
            alertLow = values[2],
            alertHigh = values[3],
            alertUrgentLow = values[4],
            alertUrgentHigh = values[5],
            workoutLow = values[6],
            workoutHigh = values[7],
            workoutUrgentLow = values[8],
            workoutUrgentHigh = values[9]
        )
    }

    val effectiveThresholds: StateFlow<EffectiveThresholds> = combine(
        state,
        thresholdSnapshot
    ) { mode, snap ->
        val on = mode is WorkoutMode.On
        EffectiveThresholds(
            displayLowMgdl = if (on) snap.workoutLow else snap.bgLow,
            displayHighMgdl = if (on) snap.workoutHigh else snap.bgHigh,
            alertLowMgdl = if (on) snap.workoutLow else snap.alertLow,
            alertHighMgdl = if (on) snap.workoutHigh else snap.alertHigh,
            alertUrgentLowMgdl = if (on) snap.workoutUrgentLow else snap.alertUrgentLow,
            alertUrgentHighMgdl = if (on) snap.workoutUrgentHigh else snap.alertUrgentHigh,
        )
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        EffectiveThresholds(72f, 180f, 72f, 180f, 54f, 234f)
    )

    init {
        // Side-effect: clean up expired DataStore values on each tick.
        // Idempotent — writes null only when value was non-null and expired.
        scope.launch {
            ticker.collect {
                cleanupExpired()
            }
        }
    }

    private suspend fun cleanupExpired() {
        val now = clock.nowMs()
        val manualSince = settings.manualWorkoutSinceMs.first()
        val maxHours = settings.workoutModeMaxHours.first()
        if (manualSince != null && now >= manualSince + maxHours * MS_PER_HOUR) {
            settings.setManualWorkoutSinceMs(null)
        }
        val overrideUntil = settings.manualOffOverrideUntilMs.first()
        if (overrideUntil != null && now >= overrideUntil) {
            settings.setManualOffOverrideUntilMs(null)
        }
    }

    suspend fun setManualOn() {
        settings.setManualWorkoutSinceMs(clock.nowMs())
        settings.setManualOffOverrideUntilMs(null)
    }

    suspend fun setManualOff() {
        val current = state.value
        if (current is WorkoutMode.On && current.source == WorkoutMode.On.Source.CALENDAR) {
            settings.setManualOffOverrideUntilMs(current.expiresAtMs)
        } else {
            settings.setManualWorkoutSinceMs(null)
        }
    }

    suspend fun toggle() {
        if (state.value is WorkoutMode.On) setManualOff() else setManualOn()
    }

    private data class ThresholdSnapshot(
        val bgLow: Float, val bgHigh: Float,
        val alertLow: Float, val alertHigh: Float,
        val alertUrgentLow: Float, val alertUrgentHigh: Float,
        val workoutLow: Float, val workoutHigh: Float,
        val workoutUrgentLow: Float, val workoutUrgentHigh: Float,
    )
}
```

- [ ] **Step 2: Add the application-scope CoroutineScope provider to `di/AppModule.kt`**

Strimma's existing `AppModule` does not provide a `CoroutineScope` (verified). Add the following inside the `object AppModule { }` block:

```kotlin
    @Provides
    @Singleton
    fun provideAppCoroutineScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
        )
```

This scope lives for the application lifetime and is used by `WorkoutModeManager` for its internal collectors. It's the same pattern `StrimmaService` uses for its own internal scope.

- [ ] **Step 3: Run the WorkoutModeManager tests**

Run: `./gradlew testDebugUnitTest --tests 'com.psjostrom.strimma.data.workout.WorkoutModeManagerTest' 2>&1 | tail -30`
Expected: All 10 tests PASS.

- [ ] **Step 4: Build full app**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/data/workout/WorkoutModeManager.kt
git commit -m "feat(workout): implement WorkoutModeManager with state machine + effectiveThresholds"
```

---

## Phase 2: AlertManager integration

### Task 7: Write AlertManager + WorkoutModeManager integration test (RED)

**Files:**
- Create: `app/src/test/java/com/psjostrom/strimma/notification/AlertManagerWorkoutTest.kt`

- [ ] **Step 1: Create the test (will fail — AlertManager doesn't yet inject manager)**

```kotlin
package com.psjostrom.strimma.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.GlucoseReading
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import com.psjostrom.strimma.data.workout.CalendarPollerSource
import com.psjostrom.strimma.data.workout.MutableClock  // exposed from test rig
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class AlertManagerWorkoutTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var manager: WorkoutModeManager
    private lateinit var alertManager: AlertManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var nextEventFlow: MutableStateFlow<WorkoutEvent?>

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        val ds = createTestDataStore(this)
        val widgetSettings = com.psjostrom.strimma.widget.WidgetSettingsRepository(context)
        settings = SettingsRepository(context, widgetSettings, ds)
        nextEventFlow = MutableStateFlow(null)
        val poller = object : CalendarPollerSource {
            override val nextEvent = nextEventFlow
        }
        val clock = MutableClock(1_700_000_000_000L)
        manager = WorkoutModeManager(settings, poller, clock, this)
        alertManager = AlertManager(context, settings, manager)
        notificationManager = context.getSystemService(NotificationManager::class.java)
        alertManager.createChannels()
    }

    private fun reading(mgdl: Int, tsMs: Long = 1_700_000_000_000L): GlucoseReading =
        GlucoseReading(date = tsMs, sgv = mgdl, delta = 0.0, direction = "Flat", pushed = 0)

    @Test
    fun `BG 99 mode OFF does not fire low alert`() = runTest {
        alertManager.checkReading(reading(99), emptyList(), predictionMinutes = 0)
        assertNull(Shadows.shadowOf(notificationManager).getNotification(AlertManager.ALERT_LOW_ID))
    }

    @Test
    fun `BG 99 mode ON fires low alert (workout low=108)`() = runTest {
        manager.setManualOn()
        alertManager.checkReading(reading(99), emptyList(), predictionMinutes = 0)
        assertNotNull(Shadows.shadowOf(notificationManager).getNotification(AlertManager.ALERT_LOW_ID))
    }

    @Test
    fun `BG 90 mode ON fires urgent low alert (workout urgent_low=90)`() = runTest {
        manager.setManualOn()
        alertManager.checkReading(reading(90), emptyList(), predictionMinutes = 0)
        assertNotNull(Shadows.shadowOf(notificationManager).getNotification(AlertManager.ALERT_URGENT_LOW_ID))
    }

    @Test
    fun `BG 234 mode ON does not fire high alert (workout high=252)`() = runTest {
        manager.setManualOn()
        alertManager.checkReading(reading(234), emptyList(), predictionMinutes = 0)
        assertNull(Shadows.shadowOf(notificationManager).getNotification(AlertManager.ALERT_HIGH_ID))
        assertNull(Shadows.shadowOf(notificationManager).getNotification(AlertManager.ALERT_URGENT_HIGH_ID))
    }

    @Test
    fun `BG 288 mode ON fires urgent high alert`() = runTest {
        manager.setManualOn()
        alertManager.checkReading(reading(288), emptyList(), predictionMinutes = 0)
        assertNotNull(Shadows.shadowOf(notificationManager).getNotification(AlertManager.ALERT_URGENT_HIGH_ID))
    }

    @Test
    fun `stale reading mode OFF fires stale alert`() = runTest {
        val staleTs = 1_700_000_000_000L - 11 * 60_000L  // 11 min old
        alertManager.checkStale(staleTs)
        assertNotNull(Shadows.shadowOf(notificationManager).getNotification(AlertManager.ALERT_STALE_ID))
    }

    @Test
    fun `stale reading mode ON suppresses stale alert`() = runTest {
        manager.setManualOn()
        val staleTs = 1_700_000_000_000L - 11 * 60_000L
        alertManager.checkStale(staleTs)
        assertNull(Shadows.shadowOf(notificationManager).getNotification(AlertManager.ALERT_STALE_ID))
    }
}
```

- [ ] **Step 2: Run to confirm RED**

Run: `./gradlew testDebugUnitTest --tests 'com.psjostrom.strimma.notification.AlertManagerWorkoutTest' 2>&1 | tail -10`
Expected: Compilation error — `AlertManager` constructor takes 2 args, not 3.

- [ ] **Step 3: Commit RED test**

```bash
git add app/src/test/java/com/psjostrom/strimma/notification/AlertManagerWorkoutTest.kt
git commit -m "test(workout): add AlertManager+WorkoutMode integration tests (RED)"
```

---

### Task 8: Wire WorkoutModeManager into AlertManager

**Files:**
- Modify: `notification/AlertManager.kt`
- Modify: `app/src/test/java/com/psjostrom/strimma/notification/AlertManagerTest.kt` (constructor update)
- Modify: `app/src/test/java/com/psjostrom/strimma/notification/AlertManagerPauseTest.kt` (constructor update)

- [ ] **Step 1: Add the constructor dependency in `AlertManager.kt`**

Change the class header (around line 49):

```kotlin
@Suppress("TooManyFunctions")
@Singleton
class AlertManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val workoutModeManager: com.psjostrom.strimma.data.workout.WorkoutModeManager
) {
```

- [ ] **Step 2: Replace threshold reads in `checkReading` (line ~305-306)**

Replace:
```kotlin
        val lowThreshold = settings.alertLow.first()
        val highThreshold = settings.alertHigh.first()
```

With:
```kotlin
        val effective = workoutModeManager.effectiveThresholds.value
        val lowThreshold = effective.alertLowMgdl
        val highThreshold = effective.alertHighMgdl
```

- [ ] **Step 3: Replace threshold reads in `checkLowAlerts` (line ~317, 319)**

Replace:
```kotlin
        val urgentLowThreshold = settings.alertUrgentLow.first()
        val lowEnabled = settings.alertLowEnabled.first()
        val lowThreshold = settings.alertLow.first()
```

With:
```kotlin
        val effective = workoutModeManager.effectiveThresholds.value
        val urgentLowThreshold = effective.alertUrgentLowMgdl
        val lowEnabled = settings.alertLowEnabled.first()
        val lowThreshold = effective.alertLowMgdl
```

- [ ] **Step 4: Replace threshold reads in `checkHighAlerts` (line ~349, 351)**

Replace:
```kotlin
        val urgentHighThreshold = settings.alertUrgentHigh.first()
        val highEnabled = settings.alertHighEnabled.first()
        val highThreshold = settings.alertHigh.first()
```

With:
```kotlin
        val effective = workoutModeManager.effectiveThresholds.value
        val urgentHighThreshold = effective.alertUrgentHighMgdl
        val highEnabled = settings.alertHighEnabled.first()
        val highThreshold = effective.alertHighMgdl
```

- [ ] **Step 5: Add stale suppression in `checkStale` (line ~427-443)**

Inside `checkStale`, after the `if (!staleEnabled) return` line, add:

```kotlin
        if (workoutModeManager.state.value is com.psjostrom.strimma.data.workout.WorkoutMode.On) return
```

- [ ] **Step 6: Update existing AlertManager tests to pass the new constructor argument**

In `AlertManagerTest.kt` and `AlertManagerPauseTest.kt`, update the `setUp()` method where `alertManager = AlertManager(context, settings)` is called. Build a `WorkoutModeManager` the same way `AlertManagerWorkoutTest` does and pass it as the third argument.

For each file, locate the line `alertManager = AlertManager(context, settings)` and replace with:

```kotlin
        nextEventFlow = MutableStateFlow(null)
        val poller = object : com.psjostrom.strimma.data.workout.CalendarPollerSource {
            override val nextEvent = nextEventFlow
        }
        val clock = com.psjostrom.strimma.data.workout.MutableClock(1_700_000_000_000L)
        val manager = com.psjostrom.strimma.data.workout.WorkoutModeManager(settings, poller, clock, this)
        alertManager = AlertManager(context, settings, manager)
```

(Add `private lateinit var nextEventFlow: MutableStateFlow<WorkoutEvent?>` to the class fields and the corresponding imports.)

- [ ] **Step 7: Run all AlertManager tests**

Run: `./gradlew testDebugUnitTest --tests 'com.psjostrom.strimma.notification.AlertManager*Test' 2>&1 | tail -30`
Expected: All AlertManager tests PASS, including the new workout test.

- [ ] **Step 8: Build full app**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (Hilt should generate the new factory automatically.)

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/notification/AlertManager.kt \
        app/src/test/java/com/psjostrom/strimma/notification/AlertManagerTest.kt \
        app/src/test/java/com/psjostrom/strimma/notification/AlertManagerPauseTest.kt
git commit -m "feat(workout): use workout-aware thresholds in AlertManager + suppress stale when On"
```

---

## Phase 3: Service + Notification + UI ViewModel

### Task 9: Wire WorkoutModeManager into StrimmaService

**Files:**
- Modify: `service/StrimmaService.kt`

- [ ] **Step 1: Inject WorkoutModeManager**

In the `@Inject constructor`, add the new dependency. Locate the constructor (search for `class StrimmaService` and the `@Inject`). Add:

```kotlin
    @Inject lateinit var workoutModeManager: com.psjostrom.strimma.data.workout.WorkoutModeManager
```

(Match the existing field-injection pattern in this file. If StrimmaService uses constructor injection, append to the parameter list.)

- [ ] **Step 2: Replace bgLow/bgHigh sources at line ~115-116**

Find:
```kotlin
        bgLow = settings.bgLow.stateIn(scope, SharingStarted.Eagerly, DEFAULT_BG_LOW.toFloat())
        bgHigh = settings.bgHigh.stateIn(scope, SharingStarted.Eagerly, DEFAULT_BG_HIGH.toFloat())
```

Replace with:
```kotlin
        bgLow = workoutModeManager.effectiveThresholds
            .map { it.displayLowMgdl }
            .stateIn(scope, SharingStarted.Eagerly, DEFAULT_BG_LOW.toFloat())
        bgHigh = workoutModeManager.effectiveThresholds
            .map { it.displayHighMgdl }
            .stateIn(scope, SharingStarted.Eagerly, DEFAULT_BG_HIGH.toFloat())
```

(Add `import kotlinx.coroutines.flow.map` if missing.)

- [ ] **Step 3: Add observer to refresh notification on state change**

Locate where StrimmaService starts long-running collectors (likely an `init` block, an `onCreate`, or after the existing CalendarPoller observer at line ~426). Add a new launch alongside:

```kotlin
        scope.launch {
            workoutModeManager.state.collect {
                // Re-render notification immediately on workout-mode transitions
                latestReading.value?.let { latest ->
                    notificationHelper.refresh(latest)
                }
            }
        }
```

If there is no `notificationHelper.refresh(reading)` method yet, this step requires Task 10 first. Mark this step as blocked-on-Task-10 and proceed.

- [ ] **Step 4: Build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (`refresh` may not exist yet — defer the observer launch to Task 10).

- [ ] **Step 5: Commit (partial — notification refresh added in Task 10)**

```bash
git add app/src/main/java/com/psjostrom/strimma/service/StrimmaService.kt
git commit -m "feat(workout): derive bgLow/bgHigh in StrimmaService from effectiveThresholds"
```

---

### Task 10: Update NotificationHelper for workout-mode rendering

**Files:**
- Modify: `notification/NotificationHelper.kt`

- [ ] **Step 1: Inject WorkoutModeManager + add `refresh()` method**

Add to the constructor parameters:
```kotlin
    private val workoutModeManager: com.psjostrom.strimma.data.workout.WorkoutModeManager,
```

Add a public method that other classes (StrimmaService) can call to force re-render with the most recent reading:

```kotlin
    /** Force-rebuild the foreground notification with the given reading. */
    fun refresh(reading: GlucoseReading) {
        // The existing entry point on this class is `updateNotification(...)` (line ~187).
        // Call it with the same parameters StrimmaService already passes per CGM reading.
        updateNotification(reading /* + same params StrimmaService passes — verify call site */)
    }
```

(Open the file and check the existing public method that StrimmaService calls per reading — alias `refresh` to it.)

- [ ] **Step 2: Append "· Workout" suffix to the notification title when state = On**

Find the section that constructs the notification title (likely `setContentTitle(...)`). Wrap the title with workout-aware logic:

```kotlin
        val baseTitle = /* existing title computation */
        val state = workoutModeManager.state.value
        val title = if (state is com.psjostrom.strimma.data.workout.WorkoutMode.On) {
            "$baseTitle · ${context.getString(R.string.workout_mode)}"
        } else {
            baseTitle
        }
        builder.setContentTitle(title)
```

- [ ] **Step 3: Add the workout-mode action button**

Where existing action buttons are added (e.g., snooze action), add:

```kotlin
        val state = workoutModeManager.state.value
        val toggleLabel = if (state is com.psjostrom.strimma.data.workout.WorkoutMode.On) {
            context.getString(R.string.workout_mode_end)
        } else {
            context.getString(R.string.workout_mode_start)
        }
        val toggleIntent = PendingIntent.getBroadcast(
            context, /* unique reqCode */ 9001,
            Intent(context, WorkoutModeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(0, toggleLabel, toggleIntent)
```

(Use a `reqCode` that doesn't collide with existing actions — verify by `grep -n "PendingIntent.getBroadcast" notification/NotificationHelper.kt`.)

- [ ] **Step 4: Add subtext line for elapsed/title (expanded notification)**

Where the expanded notification body is built, add:

```kotlin
        when (val s = workoutModeManager.state.value) {
            is com.psjostrom.strimma.data.workout.WorkoutMode.On -> {
                val sub = when (s.source) {
                    com.psjostrom.strimma.data.workout.WorkoutMode.On.Source.MANUAL -> {
                        val elapsedMs = System.currentTimeMillis() - s.sinceMs
                        val mins = (elapsedMs / 60_000L).toInt()
                        val h = mins / 60
                        val m = mins % 60
                        context.getString(R.string.workout_mode_active_for, "$h:%02d".format(m))
                    }
                    com.psjostrom.strimma.data.workout.WorkoutMode.On.Source.CALENDAR -> {
                        // Use existing CalendarPoller's nextEvent.title if available;
                        // fall back to generic label.
                        context.getString(R.string.workout_mode)
                    }
                }
                builder.setSubText(sub)
            }
            else -> { /* leave subText as-is */ }
        }
```

- [ ] **Step 5: Build (will fail — strings + WorkoutModeReceiver not yet present)**

Run: `./gradlew assembleDebug 2>&1 | tail -10`
Expected: Failures referencing `R.string.workout_mode*` and `WorkoutModeReceiver`. Proceed to Task 11.

- [ ] **Step 6: Don't commit yet — bundle with Tasks 11 + 12 for atomic build-passing commit**

---

### Task 11: Add i18n strings (English + Swedish)

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-sv/strings.xml`

- [ ] **Step 1: Add 12 keys to `values/strings.xml`**

Append to the file (before `</resources>`):

```xml
    <!-- Workout mode -->
    <string name="workout_mode">Workout mode</string>
    <string name="workout_mode_start">Start workout</string>
    <string name="workout_mode_end">End workout</string>
    <string name="workout_mode_active_for">Workout %1$s</string>
    <string name="workout_mode_settings_title">Workout mode</string>
    <string name="workout_mode_auto_off_after">Auto-off after %1$d hours</string>
    <string name="workout_mode_thresholds_header">Workout thresholds</string>
    <string name="workout_mode_threshold_low">Low</string>
    <string name="workout_mode_threshold_urgent_low">Urgent low</string>
    <string name="workout_mode_threshold_high">High</string>
    <string name="workout_mode_threshold_urgent_high">Urgent high</string>
    <string name="workout_mode_reset_defaults">Reset to defaults</string>
    <string name="workout_mode_settings_info">Active during exercise. Replaces both your alert thresholds and the in-range band on graphs. Suppresses stale-sensor alerts. Triggers from the MainScreen toggle or scheduled calendar events.</string>
```

- [ ] **Step 2: Add Swedish translations to `values-sv/strings.xml`**

```xml
    <!-- Workout mode -->
    <string name="workout_mode">Träningsläge</string>
    <string name="workout_mode_start">Starta träning</string>
    <string name="workout_mode_end">Avsluta träning</string>
    <string name="workout_mode_active_for">Träning %1$s</string>
    <string name="workout_mode_settings_title">Träningsläge</string>
    <string name="workout_mode_auto_off_after">Auto-av efter %1$d timmar</string>
    <string name="workout_mode_thresholds_header">Träningsgränser</string>
    <string name="workout_mode_threshold_low">Låg</string>
    <string name="workout_mode_threshold_urgent_low">Akut låg</string>
    <string name="workout_mode_threshold_high">Hög</string>
    <string name="workout_mode_threshold_urgent_high">Akut hög</string>
    <string name="workout_mode_reset_defaults">Återställ standard</string>
    <string name="workout_mode_settings_info">Aktivt under träning. Ersätter både dina larmgränser och målintervallet i graferna. Tystar sensor-stale-larm. Aktiveras från MainScreen-knappen eller schemalagda kalenderhändelser.</string>
```

- [ ] **Step 3: Don't commit yet — bundle with Tasks 10 + 12**

---

### Task 12: Create WorkoutModeReceiver + Manifest registration

**Files:**
- Create: `notification/WorkoutModeReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create the receiver**

```kotlin
package com.psjostrom.strimma.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WorkoutModeReceiver : BroadcastReceiver() {

    @Inject lateinit var workoutModeManager: WorkoutModeManager

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                workoutModeManager.toggle()
            } finally {
                pending.finish()
            }
        }
    }
}
```

- [ ] **Step 2: Register in AndroidManifest.xml**

Inside `<application>` block (alongside the existing `AlertSnoozeReceiver` registration):

```xml
        <receiver
            android:name=".notification.WorkoutModeReceiver"
            android:exported="false" />
```

- [ ] **Step 3: Build to verify Tasks 10 + 11 + 12 compile together**

Run: `./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit Tasks 10 + 11 + 12 together**

```bash
git add app/src/main/java/com/psjostrom/strimma/notification/NotificationHelper.kt \
        app/src/main/java/com/psjostrom/strimma/notification/WorkoutModeReceiver.kt \
        app/src/main/AndroidManifest.xml \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-sv/strings.xml
git commit -m "feat(workout): notification rendering, action button, receiver, i18n"
```

---

### Task 13: Wire up the StrimmaService notification observer (Task 9 step 3 follow-up)

**Files:**
- Modify: `service/StrimmaService.kt`

- [ ] **Step 1: Add the observer launch (now `notificationHelper.refresh()` exists)**

Locate the `scope.launch { calendarPoller.nextEvent.collect { ... } }` block (around line 426) and add a sibling:

```kotlin
        scope.launch {
            workoutModeManager.state.collect {
                latestReading.value?.let { reading ->
                    notificationHelper.refresh(reading)
                }
            }
        }
```

(Verify `latestReading` is the actual field name by `grep -n "latestReading" service/StrimmaService.kt`. If different — e.g., `_latestReading` — use that.)

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/service/StrimmaService.kt
git commit -m "feat(workout): refresh notification immediately on workout-mode state change"
```

---

### Task 14: Wire WorkoutModeManager into MainViewModel

**Files:**
- Modify: `ui/MainViewModel.kt`

- [ ] **Step 1: Inject WorkoutModeManager**

Add to the `@Inject constructor` parameter list:
```kotlin
    private val workoutModeManager: com.psjostrom.strimma.data.workout.WorkoutModeManager,
```

- [ ] **Step 2: Replace bgLow/bgHigh sources (line ~163, ~166)**

Find:
```kotlin
    val bgLow: StateFlow<Float> = settings.bgLow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_BG_LOW.toFloat())
    val bgHigh: StateFlow<Float> = settings.bgHigh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_BG_HIGH.toFloat())
```

Replace with:
```kotlin
    val bgLow: StateFlow<Float> = workoutModeManager.effectiveThresholds
        .map { it.displayLowMgdl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_BG_LOW.toFloat())
    val bgHigh: StateFlow<Float> = workoutModeManager.effectiveThresholds
        .map { it.displayHighMgdl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_BG_HIGH.toFloat())
```

- [ ] **Step 3: Expose workout mode state + toggle method**

Add public properties + method:
```kotlin
    val workoutMode: StateFlow<com.psjostrom.strimma.data.workout.WorkoutMode> =
        workoutModeManager.state

    fun toggleWorkoutMode() {
        viewModelScope.launch {
            workoutModeManager.toggle()
        }
    }
```

- [ ] **Step 4: Build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt
git commit -m "feat(workout): expose workout state + toggle in MainViewModel; derive bg* from effectiveThresholds"
```

---

### Task 15: Create the WorkoutModePill composable

**Files:**
- Create: `ui/components/WorkoutModePill.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.psjostrom.strimma.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.workout.WorkoutMode
import com.psjostrom.strimma.ui.theme.TintInRange
import kotlinx.coroutines.delay

/**
 * Status pill for workout mode. Tap to toggle.
 *
 * - Off: outlined chip, low emphasis
 * - On:  filled pill with elapsed time (manual) or generic label (calendar)
 */
@Composable
fun WorkoutModePill(
    mode: WorkoutMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(100)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .let {
                if (mode is WorkoutMode.On) {
                    it.background(TintInRange, shape)
                } else {
                    it.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text("🏃")  // 🏃
        when (mode) {
            is WorkoutMode.Off -> Text(
                stringResource(R.string.workout_mode),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is WorkoutMode.On -> {
                val elapsed = elapsedSince(mode.sinceMs)
                Text(
                    stringResource(R.string.workout_mode_active_for, elapsed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun elapsedSince(sinceMs: Long): String {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sinceMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(60_000L)  // refresh every minute
        }
    }
    val totalMin = ((nowMs - sinceMs) / 60_000L).coerceAtLeast(0).toInt()
    val h = totalMin / 60
    val m = totalMin % 60
    return "%d:%02d".format(h, m)
}
```

- [ ] **Step 2: Verify `TintInRange` exists in `ui/theme/Color.kt`**

Run: `grep -n "TintInRange" app/src/main/java/com/psjostrom/strimma/ui/theme/Color.kt`
Expected: At least one match (referenced in spec section "Design Decisions").

If not found, use `MaterialTheme.colorScheme.primaryContainer` as a fallback in step 1's Modifier.

- [ ] **Step 3: Build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/components/WorkoutModePill.kt
git commit -m "feat(workout): WorkoutModePill composable (chip when Off, filled with elapsed when On)"
```

---

### Task 16: Add WorkoutModePill to MainScreen status row

**Files:**
- Modify: `ui/MainScreen.kt`

- [ ] **Step 1: Find the status row that contains the Pause All pill**

Run: `grep -n "PauseAll\|pauseAll" app/src/main/java/com/psjostrom/strimma/ui/MainScreen.kt`
Note the line where the Pause All pill is rendered.

- [ ] **Step 2: Add the WorkoutModePill alongside it**

In the same Row/Box that holds the Pause All pill, before or after it, add:

```kotlin
        val workoutMode by viewModel.workoutMode.collectAsState()
        WorkoutModePill(
            mode = workoutMode,
            onClick = { viewModel.toggleWorkoutMode() }
        )
```

(Imports: `import com.psjostrom.strimma.ui.components.WorkoutModePill`, `import androidx.compose.runtime.collectAsState`, `import androidx.compose.runtime.getValue`.)

- [ ] **Step 3: Build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Install on device + manual smoke test**

Run: `./gradlew installRelease`
Expected: APK installed on connected device.

Manual check:
- Open Strimma → MainScreen shows workout-mode chip alongside the Pause All chip
- Tap the workout chip → it flips to filled pill, label changes to "Workout 0:00"
- Notification updates within ~1 second to show "· Workout" suffix and "End workout" action
- Tap the action in the notification → pill flips back to OFF state in the app

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/MainScreen.kt
git commit -m "feat(workout): add WorkoutModePill to MainScreen status row"
```

---

## Phase 4: Other consumers of bgLow/bgHigh

### Task 17: Make StrimmaWidget workout-aware (via Hilt EntryPoint)

**Why a different pattern:** `StrimmaWidget` is a `GlanceAppWidget`, not a Hilt-managed class. It manually constructs `SettingsRepository` inside `provideGlance` (line 49-53). To get the singleton `WorkoutModeManager`, we use Hilt's `EntryPointAccessors` — the standard pattern for pulling singletons into non-Hilt-managed Android components.

**Files:**
- Create: `widget/WidgetEntryPoint.kt`
- Modify: `widget/StrimmaWidget.kt`

- [ ] **Step 1: Create the entry point interface**

```kotlin
// widget/WidgetEntryPoint.kt
package com.psjostrom.strimma.widget

import com.psjostrom.strimma.data.workout.WorkoutModeManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun workoutModeManager(): WorkoutModeManager
}
```

- [ ] **Step 2: Use it in `provideGlance` to read effective thresholds**

In `widget/StrimmaWidget.kt`, replace lines 58-59 (`val bgLow = settings.bgLow.first()` / `val bgHigh = settings.bgHigh.first()`) with:

```kotlin
        val workoutModeManager = dagger.hilt.android.EntryPointAccessors
            .fromApplication(context, WidgetEntryPoint::class.java)
            .workoutModeManager()
        val effective = workoutModeManager.effectiveThresholds.value
        val bgLow = effective.displayLowMgdl
        val bgHigh = effective.displayHighMgdl
```

(The other reads — `settings.glucoseUnit.first()` etc. — stay as they are; only the bg thresholds need to be workout-aware.)

- [ ] **Step 3: Build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual smoke test**

Run: `./gradlew installRelease`
Expected: APK installed.

Add the Strimma widget to your home screen if not already there. Then:
- Toggle workout mode ON in the app
- Force-refresh the widget (long-press → refresh, or wait for next periodic update)
- Verify: BG color uses workout in-range band (e.g., 12 mmol shows green/in-range, not amber)
- Toggle OFF → widget eventually reflects standard band

(No unit test added — the widget runs in a Glance composable that requires Glance test infrastructure to drive. The `WorkoutModeManagerTest` already verifies that `effectiveThresholds.value.displayLow/HighMgdl` returns workout values when mode is On; the widget integration is verified by the manual smoke test.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/widget/WidgetEntryPoint.kt \
        app/src/main/java/com/psjostrom/strimma/widget/StrimmaWidget.kt
git commit -m "feat(workout): widget reads display thresholds via Hilt EntryPoint"
```

---

### Task 18: Make LocalWebServer workout-aware

**Files:**
- Modify: `webserver/LocalWebServer.kt`

`LocalWebServer` is a Hilt-managed `@Singleton` (line 31), so we can add `WorkoutModeManager` as a constructor parameter.

- [ ] **Step 1: Add the constructor dependency**

Change:
```kotlin
@Singleton
class LocalWebServer @Inject constructor(
    private val dao: ReadingDao,
    private val treatmentDao: TreatmentDao,
    private val settings: SettingsRepository
) {
```

To:
```kotlin
@Singleton
class LocalWebServer @Inject constructor(
    private val dao: ReadingDao,
    private val treatmentDao: TreatmentDao,
    private val settings: SettingsRepository,
    private val workoutModeManager: com.psjostrom.strimma.data.workout.WorkoutModeManager
) {
```

- [ ] **Step 2: Replace settings.bgLow/bgHigh reads at line 137-138**

Find:
```kotlin
        val bgLow = settings.bgLow.first()
        val bgHigh = settings.bgHigh.first()
```

Replace with:
```kotlin
        val effective = workoutModeManager.effectiveThresholds.value
        val bgLow = effective.displayLowMgdl
        val bgHigh = effective.displayHighMgdl
```

- [ ] **Step 3: Build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual smoke test**

Curl the web server endpoint while toggling workout mode (port from `LocalWebServer.PORT = 17580`, exact path depends on what the server exposes — verify via `grep -n "routing" app/src/main/java/com/psjostrom/strimma/webserver/LocalWebServer.kt`):

- Mode OFF: response should reflect standard `bgLow`/`bgHigh`
- Mode ON: response should reflect workout `bgLow`/`bgHigh` (108/252 mg/dL by default)

(No unit test added — the `WorkoutModeManagerTest` already verifies `effectiveThresholds` correctness; consumer wiring here is a one-line dependency injection that is verified by the manual smoke test + the build passing.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/webserver/LocalWebServer.kt
git commit -m "feat(workout): LocalWebServer reads display thresholds via WorkoutModeManager"
```

---

### Task 19: Make StoryViewModel workout-aware

**Files:**
- Modify: `ui/story/StoryViewModel.kt`

- [ ] **Step 1: Replace settings.bgLow/bgHigh reads at line 67-68**

Find:
```kotlin
            val bgLow = settings.bgLow.first()
            val bgHigh = settings.bgHigh.first()
```

Replace with:
```kotlin
            val effective = workoutModeManager.effectiveThresholds.value
            val bgLow = effective.displayLowMgdl
            val bgHigh = effective.displayHighMgdl
```

Inject `WorkoutModeManager` via the existing `@HiltViewModel` constructor.

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/story/StoryViewModel.kt
git commit -m "feat(workout): StoryViewModel reads display thresholds from WorkoutModeManager"
```

---

## Phase 5: Settings UI

### Task 20: Create WorkoutSettingsViewModel

**Files:**
- Create: `ui/settings/WorkoutSettingsViewModel.kt`

- [ ] **Step 1: Create the ViewModel**

```kotlin
package com.psjostrom.strimma.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_WORKOUT_ALERT_HIGH
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_WORKOUT_ALERT_LOW
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_WORKOUT_ALERT_URGENT_HIGH
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_WORKOUT_ALERT_URGENT_LOW
import com.psjostrom.strimma.data.SettingsRepository.Companion.DEFAULT_WORKOUT_MODE_MAX_HOURS
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkoutSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository
) : ViewModel() {

    val workoutLow: StateFlow<Float> = settings.workoutAlertLow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_WORKOUT_ALERT_LOW)
    val workoutUrgentLow: StateFlow<Float> = settings.workoutAlertUrgentLow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_WORKOUT_ALERT_URGENT_LOW)
    val workoutHigh: StateFlow<Float> = settings.workoutAlertHigh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_WORKOUT_ALERT_HIGH)
    val workoutUrgentHigh: StateFlow<Float> = settings.workoutAlertUrgentHigh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_WORKOUT_ALERT_URGENT_HIGH)
    val maxHours: StateFlow<Int> = settings.workoutModeMaxHours
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_WORKOUT_MODE_MAX_HOURS)

    fun setLow(mgdl: Float) = viewModelScope.launch { settings.setWorkoutAlertLow(mgdl) }
    fun setUrgentLow(mgdl: Float) = viewModelScope.launch { settings.setWorkoutAlertUrgentLow(mgdl) }
    fun setHigh(mgdl: Float) = viewModelScope.launch { settings.setWorkoutAlertHigh(mgdl) }
    fun setUrgentHigh(mgdl: Float) = viewModelScope.launch { settings.setWorkoutAlertUrgentHigh(mgdl) }
    fun setMaxHours(hours: Int) = viewModelScope.launch { settings.setWorkoutModeMaxHours(hours) }

    fun resetToDefaults() = viewModelScope.launch {
        settings.setWorkoutAlertLow(DEFAULT_WORKOUT_ALERT_LOW)
        settings.setWorkoutAlertUrgentLow(DEFAULT_WORKOUT_ALERT_URGENT_LOW)
        settings.setWorkoutAlertHigh(DEFAULT_WORKOUT_ALERT_HIGH)
        settings.setWorkoutAlertUrgentHigh(DEFAULT_WORKOUT_ALERT_URGENT_HIGH)
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/settings/WorkoutSettingsViewModel.kt
git commit -m "feat(workout): WorkoutSettingsViewModel exposing thresholds + maxHours + reset"
```

---

### Task 21: Create WorkoutSettings Composable

**Files:**
- Create: `ui/settings/WorkoutSettings.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.psjostrom.strimma.R
import com.psjostrom.strimma.data.GlucoseUnit

/**
 * Settings → Exercise → Workout mode subsection.
 * Caller is responsible for providing the GlucoseUnit (read from the parent settings VM).
 */
@Composable
fun WorkoutSettings(
    glucoseUnit: GlucoseUnit,
    viewModel: WorkoutSettingsViewModel = hiltViewModel()
) {
    val low by viewModel.workoutLow.collectAsState()
    val urgentLow by viewModel.workoutUrgentLow.collectAsState()
    val high by viewModel.workoutHigh.collectAsState()
    val urgentHigh by viewModel.workoutUrgentHigh.collectAsState()
    val maxHours by viewModel.maxHours.collectAsState()

    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.workout_mode_settings_title),
            style = MaterialTheme.typography.titleMedium
        )

        // Auto-off slider
        Text(stringResource(R.string.workout_mode_auto_off_after, maxHours))
        Slider(
            value = maxHours.toFloat(),
            onValueChange = { viewModel.setMaxHours(it.toInt().coerceIn(1, 12)) },
            valueRange = 1f..12f,
            steps = 10
        )

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.workout_mode_thresholds_header),
            style = MaterialTheme.typography.labelLarge
        )

        ThresholdRow(
            label = stringResource(R.string.workout_mode_threshold_low),
            mgdl = low,
            unit = glucoseUnit,
            onChange = viewModel::setLow
        )
        ThresholdRow(
            label = stringResource(R.string.workout_mode_threshold_urgent_low),
            mgdl = urgentLow,
            unit = glucoseUnit,
            onChange = viewModel::setUrgentLow
        )
        ThresholdRow(
            label = stringResource(R.string.workout_mode_threshold_high),
            mgdl = high,
            unit = glucoseUnit,
            onChange = viewModel::setHigh
        )
        ThresholdRow(
            label = stringResource(R.string.workout_mode_threshold_urgent_high),
            mgdl = urgentHigh,
            unit = glucoseUnit,
            onChange = viewModel::setUrgentHigh
        )

        Button(onClick = { viewModel.resetToDefaults() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.workout_mode_reset_defaults))
        }

        Text(
            stringResource(R.string.workout_mode_settings_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ThresholdRow(
    label: String,
    mgdl: Float,
    unit: GlucoseUnit,
    onChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        TextField(
            value = unit.formatNumber(mgdl.toDouble()),
            onValueChange = { typed ->
                unit.parseToMgdl(typed)?.let { onChange(it.toFloat()) }
            },
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}
```

- [ ] **Step 2: Verify GlucoseUnit has `formatNumber` and `parseToMgdl` (or equivalents)**

Run: `grep -n "fun formatNumber\|fun parseToMgdl\|fun format\|fun parse" app/src/main/java/com/psjostrom/strimma/data/GlucoseUnit.kt`

If method names differ, substitute the actual function calls in step 1's `ThresholdRow`.

- [ ] **Step 3: Build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/settings/WorkoutSettings.kt
git commit -m "feat(workout): WorkoutSettings composable with sliders + threshold inputs + reset"
```

---

### Task 22: Wire WorkoutSettings into SettingsScreen Exercise group

**Files:**
- Modify: `ui/settings/SettingsScreen.kt` (or wherever the Exercise section lives — find it)

- [ ] **Step 1: Locate the Exercise group**

Run: `grep -n "Exercise\|exercise" app/src/main/java/com/psjostrom/strimma/ui/settings/SettingsScreen.kt | head -20`
Identify the Composable that renders the Exercise settings group.

- [ ] **Step 2: Add the WorkoutSettings call after per-category targets**

Inside the Exercise group, after the existing exercise-related composables (Calendar, Per-category targets), add:

```kotlin
        WorkoutSettings(glucoseUnit = glucoseUnit)
```

(Pass the existing `glucoseUnit` value from the parent — this is already available in the Settings screen for other mmol/mgdl displays.)

Add the import: `import com.psjostrom.strimma.ui.settings.WorkoutSettings`.

- [ ] **Step 3: Build + install**

Run: `./gradlew installRelease`
Expected: APK installed.

Manual smoke test:
- Settings → Exercise → scroll to "Workout mode"
- See: auto-off slider (default 3h), 4 threshold inputs (default 6.0 / 5.0 / 14.0 / 16.0 mmol), Reset to defaults button, info text
- Edit a threshold → reopen Settings → value persists
- Reset to defaults → all 4 values back to 6/5/14/16

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/settings/SettingsScreen.kt
git commit -m "feat(workout): wire WorkoutSettings into Settings → Exercise group"
```

---

## Phase 6: Notification action integration test

### Task 23: Test the notification receiver toggles state

**Files:**
- Create: `app/src/test/java/com/psjostrom/strimma/notification/WorkoutNotificationActionTest.kt`

- [ ] **Step 1: Write test**

```kotlin
package com.psjostrom.strimma.notification

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.data.SettingsRepository
import com.psjostrom.strimma.data.calendar.WorkoutEvent
import com.psjostrom.strimma.data.workout.CalendarPollerSource
import com.psjostrom.strimma.data.workout.MutableClock
import com.psjostrom.strimma.data.workout.WorkoutMode
import com.psjostrom.strimma.data.workout.WorkoutModeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkoutNotificationActionTest {

    @Test
    fun `tapping notification action while Off transitions state to On`() = runTest {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val ds = createTestDataStore(this)
        val settings = SettingsRepository(ctx, ds)
        val nextEvent = MutableStateFlow<WorkoutEvent?>(null)
        val poller = object : CalendarPollerSource { override val nextEvent = nextEvent }
        val clock = MutableClock(1_700_000_000_000L)
        val manager = WorkoutModeManager(settings, poller, clock, this)

        // Off initially
        assertTrue(manager.state.first() is WorkoutMode.Off)

        // Simulate the receiver doing what the broadcast would do.
        // (Broadcasting through Robolectric requires Hilt setup; we test the
        // manager.toggle() contract here. Real receiver wiring is exercised
        // by manual smoke test in Task 16.)
        manager.toggle()
        assertTrue(manager.state.first() is WorkoutMode.On)

        manager.toggle()
        assertTrue(manager.state.first() is WorkoutMode.Off)
    }
}
```

- [ ] **Step 2: Run + build**

Run: `./gradlew testDebugUnitTest --tests 'com.psjostrom.strimma.notification.WorkoutNotificationActionTest' 2>&1 | tail -10`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/psjostrom/strimma/notification/WorkoutNotificationActionTest.kt
git commit -m "test(workout): notification action toggles state via WorkoutModeManager"
```

---

## Phase 7: Documentation

### Task 24: Write user-facing guide

**Files:**
- Create: `docs/guide/workout-mode.md`

- [ ] **Step 1: Create the guide**

```markdown
# Workout Mode

Workout Mode raises Strimma's alert thresholds and the in-range band on graphs while you're exercising. It's designed for the way blood glucose moves during a run, ride, or workout: sharper drops at the low end, transient highs from adrenaline at the top.

## Defaults

| | Standard | Workout |
|---|---|---|
| Low | 4.0 mmol | 6.0 mmol |
| Urgent low | 3.0 mmol | 5.0 mmol |
| High | 10.0 mmol | 14.0 mmol |
| Urgent high | 13.0 mmol | 16.0 mmol |

You can change all four workout thresholds in **Settings → Exercise → Workout mode**.

## What changes while Workout Mode is ON

- **Alerts** use the workout thresholds instead of the standard ones.
- **In-range band on graphs** (and the BG hero color, widget, web server) uses the workout thresholds, so 12 mmol shows as in-range green during exercise instead of amber high.
- **Predict-low / predict-high** alerts use the workout thresholds — fewer false alarms during the rapid swings of exercise.
- **Stale-sensor alerts are suppressed** — sensor contact loss during heavy sweat or movement is expected and shouldn't trigger an alarm.

## How to turn it on

There are two ways:

**Manual toggle.** Tap the "Workout mode" pill on the main screen, or use the "Start workout" action in the foreground notification. The pill flips to a filled state with elapsed time. Tap again to turn off.

**Calendar event.** If you've configured a workout calendar in Strimma (Settings → Exercise → Calendar), workout mode auto-activates when an event in that calendar is currently happening. The pill shows the event title.

Manual action always wins. If a calendar event is active and you turn off manually, mode stays off until that event ends.

## Auto-off safety timeout

When you turn workout mode on manually, it auto-turns-off after a configurable number of hours (default 3, range 1-12). This prevents a forgotten toggle from keeping wider thresholds active during your commute home. Calendar-driven workout mode has no timeout — it ends when the calendar event ends.

If you regularly do longer workouts (marathons, ultras, all-day rides), bump the safety timeout in settings before heading out.

## Coexistence with Pause All

Pause All and Workout Mode are independent.

- For low / high alerts: Pause All wins (alerts off means workout thresholds are moot until pause expires).
- For stale alerts: Workout Mode is the only suppressor — Pause All does NOT cover stale alerts.
```

- [ ] **Step 2: Commit**

```bash
git add docs/guide/workout-mode.md
git commit -m "docs(workout): user guide for workout mode"
```

---

### Task 25: Update internal spec.md with Workout Mode section

**Files:**
- Modify: `docs/internal/spec.md`

- [ ] **Step 1: Find the right place to insert**

Run: `grep -n "^## " docs/internal/spec.md | head -20`
Find a logical location (likely after Alerts section).

- [ ] **Step 2: Add the section**

Insert (adjust heading depth to match neighbors):

```markdown
## Workout Mode

A runtime state that, while On, replaces both the in-range band (`bgLow` / `bgHigh`) and the alert thresholds (`alertLow` / `alertHigh` / `alertUrgentLow` / `alertUrgentHigh`) with a separate set of 4 workout-mode thresholds. While On, also suppresses stale-sensor alerts.

**State machine:** Owned by `WorkoutModeManager` (Hilt singleton, package `data/workout/`). Combines DataStore-backed manual state, the existing `CalendarPoller.nextEvent` flow filtered to "currently active," and a 30s ticker. Manual action always wins over calendar.

**Triggers:** Manual toggle (MainScreen pill + notification action) or active calendar event. Activity Recognition and notification-listener inference are out of scope for v1.

**Settings:** `Settings → Exercise → Workout mode`. Configurable thresholds (default 6/5/14/16 mmol) and safety timeout (default 3h, range 1-12h).

**Spec:** See `docs/specs/2026-05-05-workout-mode-design.md` for full design details.
```

- [ ] **Step 3: Commit**

```bash
git add docs/internal/spec.md
git commit -m "docs(workout): add Workout Mode section to internal spec"
```

---

## Phase 8: Final verification

### Task 26: Run full test suite + manual smoke test

- [ ] **Step 1: Run all tests**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, all tests pass (including pre-existing AlertManagerTest, AlertManagerPauseTest, NotificationHelperTest).

- [ ] **Step 2: Run Detekt static analysis**

Run: `./gradlew detekt 2>&1 | tail -20`
Expected: no new violations.

- [ ] **Step 3: Install on device**

Run: `./gradlew installRelease`
Expected: APK installed.

- [ ] **Step 4: Manual smoke test checklist**

In the app:
- [ ] MainScreen shows the Workout pill chip (low-emphasis) when mode = Off
- [ ] Tap the chip → flips to filled pill with "Workout 0:00"
- [ ] Notification within 1s shows "· Workout" suffix and "End workout" action button
- [ ] Tap notification action → app pill flips back to Off, notification suffix gone
- [ ] Settings → Exercise → Workout mode shows defaults (3h, 6/5/14/16 mmol)
- [ ] Edit "Low" to 6.5 → reopen settings → 6.5 persists
- [ ] Reset to defaults → all 4 values back to 6/5/14/16
- [ ] With BG ~5.5 mmol and workout mode ON, low alert fires (this requires real CGM data; verify via DebugLog if needed)
- [ ] With workout mode ON for >3h, mode auto-flips to Off (or set maxHours=1 and wait an hour to test faster)

- [ ] **Step 5: Final commit (if any uncommitted touches)**

```bash
git status
# If clean, no commit needed
```

---

## Self-Review Checklist

This section is to be run by the planner BEFORE handoff to executing skill. Walk through:

**Spec coverage:**
- [x] WorkoutMode sealed class → Task 1
- [x] EffectiveThresholds → Task 2
- [x] SettingsRepository keys + flows → Task 3
- [x] WorkoutModeManager state machine + thresholds → Tasks 4-6
- [x] AlertManager integration (low/high/urgent + stale + predict via checkReading) → Tasks 7-8
- [x] StrimmaService bgLow/bgHigh derivation + notification observer → Tasks 9, 13
- [x] NotificationHelper rendering (title, subtext, action) → Task 10
- [x] WorkoutModeReceiver + Manifest → Task 12
- [x] i18n strings (en + sv) → Task 11
- [x] MainViewModel integration → Task 14
- [x] WorkoutModePill composable → Task 15
- [x] MainScreen wiring → Task 16
- [x] Widget consumer → Task 17
- [x] LocalWebServer consumer → Task 18
- [x] StoryViewModel consumer → Task 19
- [x] WorkoutSettings Composable + ViewModel → Tasks 20-21
- [x] Settings wiring → Task 22
- [x] User docs → Task 24
- [x] Internal spec docs → Task 25
- [x] Test coverage: WorkoutModeManagerTest (state machine + thresholds), AlertManagerWorkoutTest (full integration), WorkoutNotificationActionTest (manager toggle contract). Widget + LocalWebServer + StoryViewModel are verified by manual smoke test (their integration is one-line dependency wiring; the manager test already verifies threshold correctness).

**Placeholder scan:** No "TBD"/"TODO" remains in actual implementation steps. Two flagged design questions in spec are explicitly deferred to implementation discretion (threshold-validation UX in Task 21, PreActivityCard hide-when-pill-active not in any task) — both noted as "decide during implementation."

**Type consistency:**
- `WorkoutMode.On.sinceMs / expiresAtMs`: Long throughout
- `EffectiveThresholds.{display,alert}{Low,High,Urgent*}Mgdl`: Float throughout
- `WorkoutModeManager.toggle / setManualOn / setManualOff`: suspend throughout
- `state: StateFlow<WorkoutMode>` and `effectiveThresholds: StateFlow<EffectiveThresholds>` consistent across consumers

**Out of scope (per spec):** Per-workout-type thresholds, Activity Recognition, notification-inference triggers, Quick Settings tile, HC writes with workout context, ExerciseHistoryScreen historical thresholds — all confirmed not in any task.
