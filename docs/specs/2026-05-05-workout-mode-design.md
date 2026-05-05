# Workout Mode — Design Spec

**Date:** 2026-05-05
**Status:** Draft

## Summary

Add a "workout mode" that, while active, replaces both the alert thresholds (low / urgent low / high / urgent high) AND the display in-range band (`bgLow` / `bgHigh`) with a separate set tuned for exercise. Triggers from a manual MainScreen toggle or from a currently-active calendar event. Manual action always wins over calendar. Auto-off after a configurable safety timeout. While active, also affects predict-low / predict-high alerts, hero BG color, widget, web server, and suppresses stale-sensor alerts.

The feature touches multiple consumers (alerts, graphs, hero color, widget, web server, notification UI), so it lives in its own state owner — `WorkoutModeManager` — that exposes a single `EffectiveThresholds` flow consumed everywhere.

## Design Decisions

- **Scope:** A single named "session" state. While On, alerts + graph zones + hero color + predict alerts use workout thresholds; stale alerts are suppressed.
- **Triggers (v1):** Manual MainScreen toggle + active calendar event. Activity Recognition API and notification-listener inference deferred (YAGNI — revisit if real users hit "I forgot to flip it").
- **State:** Manual ON has a configurable safety timeout (default 3h, range 1–12h). Calendar ON has no timeout (event end is the natural boundary).
- **Conflict resolution:** Manual action always wins. If user manually flips OFF during an active calendar event, mode stays Off until that event ends, then normal calendar logic resumes for future events.
- **Predict alerts:** Use workout thresholds (wider band → fewer false-positive crossings). Don't disable.
- **Stale alerts:** Suppressed entirely while mode is On (sensor contact loss during heavy exercise / sweat is expected).
- **Settings location:** `Settings → Exercise → Workout mode` (groups with existing Calendar + per-category targets, not under Alerts).
- **Threshold defaults:** low=6.0, urgent_low=5.0, high=14.0, urgent_high=16.0 (mmol). Stored internally as mg/dL, like all other thresholds.
- **UI affordance:** Pill on MainScreen (mirrors Pause All pattern) + action button in foreground notification. No Quick Settings tile (deferred).
- **Color:** Reuse `TintInRange` for the active workout pill (workout mode is a beneficial state, semantically aligned with "in range"). Pause All keeps `Danger`. No new color tokens — no `sync-colors` drift.
- **Coexistence with Pause All:** Independent layers. Both pills shown when both active. For LOW/HIGH alerts: Pause All suppresses firing → workout thresholds invisible while paused, resume when pause expires. For STALE alerts: Pause All does NOT cover them (only iterates LOW + HIGH categories) → workout mode is the only stale suppressor.
- **Persistence:** Timestamp-based, survives process death and reboot. No volatile in-memory state.
- **Keys:** New `workout_alert_low / high / urgent_low / urgent_high` to distinguish from existing `workout_easy_low` etc (those stay as per-category target defaults — display only).

## Architecture

### 1. `data/workout/WorkoutMode.kt` (new)

Sealed class representing the runtime state. Timestamps are epoch milliseconds (`Long`), matching Strimma's existing convention (`WorkoutEvent.startTime: Long`, `System.currentTimeMillis()`, DataStore `longPreferencesKey`).

```kotlin
sealed class WorkoutMode {
    data object Off : WorkoutMode()
    data class On(
        val source: Source,
        val sinceMs: Long,
        val expiresAtMs: Long   // For MANUAL: sinceMs + maxHours. For CALENDAR: event.endTime.
    ) : WorkoutMode() {
        enum class Source { MANUAL, CALENDAR }
    }
}
```

Carrying `expiresAtMs` for both sources lets `setManualOff` read it directly from `state.value` to compute `manualOffOverrideUntil` without re-querying CalendarPoller.

### 2. `data/workout/EffectiveThresholds.kt` (new)

Single value type carrying both display thresholds (in-range band, hero color, widget, web server) and alert thresholds. They are independent settings in normal mode; workout mode collapses them to one pair (workout has only 4 settings, used for both display and alerts).

```kotlin
data class EffectiveThresholds(
    // Display: in-range band on graphs, hero color, widget, web server
    val displayLowMgdl: Float,
    val displayHighMgdl: Float,
    // Alerts: low/high firing
    val alertLowMgdl: Float,
    val alertHighMgdl: Float,
    val alertUrgentLowMgdl: Float,
    val alertUrgentHighMgdl: Float,
)
```

**Resolution rules:**

| Field                  | Mode = Off (normal)        | Mode = On (workout)         |
|------------------------|----------------------------|-----------------------------|
| displayLowMgdl         | settings.bgLow             | settings.workoutAlertLow    |
| displayHighMgdl        | settings.bgHigh            | settings.workoutAlertHigh   |
| alertLowMgdl           | settings.alertLow          | settings.workoutAlertLow    |
| alertHighMgdl          | settings.alertHigh         | settings.workoutAlertHigh   |
| alertUrgentLowMgdl     | settings.alertUrgentLow    | settings.workoutAlertUrgentLow  |
| alertUrgentHighMgdl    | settings.alertUrgentHigh   | settings.workoutAlertUrgentHigh |

In normal mode the user can configure display and alert independently (e.g., display band 4–9 for visual feedback but alerts at 4 and 10). In workout mode the user has 4 settings and they apply to both display and alerts.

### 3. `data/workout/WorkoutModeManager.kt` (new)

Hilt `@Singleton`. Single source of truth for workout state and effective thresholds.

**Inputs (combined into state and effectiveThresholds):**
- `manualWorkoutSinceMs: Flow<Long?>` from `SettingsRepository` (null = manually OFF)
- `manualOffOverrideUntilMs: Flow<Long?>` from `SettingsRepository` (timestamp until which calendar triggers are suppressed)
- `workoutModeMaxHours: Flow<Int>` from `SettingsRepository`
- `nextEvent: StateFlow<WorkoutEvent?>` from `CalendarPoller` — note this is the *next* upcoming event, NOT the currently-active one. `WorkoutModeManager` filters internally (see R5).
- All threshold settings from `SettingsRepository`: `bgLow`, `bgHigh`, `alertLow`, `alertHigh`, `alertUrgentLow`, `alertUrgentHigh`, plus the 4 new `workoutAlert*` keys
- Internal ticker: `flow { while (true) { emit(Unit); delay(30_000) } }` — drives time-based transitions when no input changes (calendar event boundary, manual safety timeout)

**Outputs (both `SharingStarted.Eagerly` so consumers can `.value`-read synchronously):**
- `val state: StateFlow<WorkoutMode>` — combined state, default `Off`
- `val effectiveThresholds: StateFlow<EffectiveThresholds>` — workout values when state is On, normal settings when Off (per resolution table above)

**State machine (rules in priority order):**

```
Inputs at time `now` (epoch ms):
  manualSinceMs: Long?            — when Manual ON started (null = not manual ON)
  overrideUntilMs: Long?          — manual OFF override until this time
  maxHours: Int                   — manual safety timeout
  nextEvent: WorkoutEvent?        — next upcoming event from CalendarPoller (may be future or active)

Derived:
  manualExpiresAtMs = manualSinceMs?.let { it + maxHours * MS_PER_HOUR }
  isCalendarActive = nextEvent != null
                  && now >= nextEvent.startTime
                  && now <= nextEvent.endTime

Output state:
  R1. manualSinceMs != null && now < manualExpiresAtMs
        → On(MANUAL, sinceMs=manualSinceMs, expiresAtMs=manualExpiresAtMs)

  R2. manualSinceMs != null && now >= manualExpiresAtMs
        → schedule cleanup (set manualWorkoutSinceMs = null in DataStore)
        → fall through to R3+

  R3. overrideUntilMs != null && now < overrideUntilMs
        → Off (manual OFF override active)

  R4. overrideUntilMs != null && now >= overrideUntilMs
        → schedule cleanup (set manualOffOverrideUntilMs = null in DataStore)
        → fall through to R5+

  R5. isCalendarActive
        → On(CALENDAR, sinceMs=nextEvent.startTime, expiresAtMs=nextEvent.endTime)

  R6. else → Off
```

**Public methods:**
- `suspend fun setManualOn()` → `manualWorkoutSinceMs = System.currentTimeMillis()`, `manualOffOverrideUntilMs = null` in DataStore
- `suspend fun setManualOff()` →
  - Read current `state.value`. If `state is On && state.source == CALENDAR`, set `manualOffOverrideUntilMs = state.expiresAtMs` (the event end carried in state). Otherwise set `manualWorkoutSinceMs = null`.
- `suspend fun toggle()` → setManualOff if `state.value is On`, else setManualOn
- Cleanup of expired DataStore values: lazy, on first state read after expiry. Avoids race conditions vs eager timer cleanup.

**Source transition (Manual → Calendar):** if Manual ON expires while a calendar event is active, R1 fails → R5 fires → state transitions from `On(MANUAL, ...)` to `On(CALENDAR, ...)` seamlessly. The pill in MainScreen and the notification subtext should re-render to reflect the new source label without flickering Off in between (verified by test).

### 4. `data/SettingsRepository.kt` additions

Config only — no runtime state derivation here (that's WorkoutModeManager's job).

```kotlin
// Workout thresholds (stored as mg/dL, like all thresholds; doubles as both
// in-range band AND alert thresholds during workout mode)
private val KEY_WORKOUT_ALERT_LOW = floatPreferencesKey("workout_alert_low")
private val KEY_WORKOUT_ALERT_HIGH = floatPreferencesKey("workout_alert_high")
private val KEY_WORKOUT_ALERT_URGENT_LOW = floatPreferencesKey("workout_alert_urgent_low")
private val KEY_WORKOUT_ALERT_URGENT_HIGH = floatPreferencesKey("workout_alert_urgent_high")

// Defaults converted from Per's stated mmol values (factor 18.0182)
private const val DEFAULT_WORKOUT_ALERT_LOW = 108f       // 6.0 mmol
private const val DEFAULT_WORKOUT_ALERT_URGENT_LOW = 90f // 5.0 mmol
private const val DEFAULT_WORKOUT_ALERT_HIGH = 252f      // 14.0 mmol
private const val DEFAULT_WORKOUT_ALERT_URGENT_HIGH = 288f // 16.0 mmol

// Manual safety timeout
private val KEY_WORKOUT_MODE_MAX_HOURS = intPreferencesKey("workout_mode_max_hours")
private const val DEFAULT_WORKOUT_MODE_MAX_HOURS = 3

// Runtime state (set by WorkoutModeManager, read for state machine)
// Both stored as epoch ms via longPreferencesKey. null/absent => not set.
private val KEY_MANUAL_WORKOUT_SINCE_MS = longPreferencesKey("manual_workout_since_ms")
private val KEY_MANUAL_OFF_OVERRIDE_UNTIL_MS = longPreferencesKey("manual_off_override_until_ms")
```

Flows + setters for each key, following existing pattern (use sentinel value `0L` to represent "not set" since `longPreferencesKey` doesn't natively support nullables, then map to `Long?` in the Flow).

### 5. `notification/AlertManager.kt` integration

Inject `WorkoutModeManager` into `AlertManager`. At each evaluation point, read from `workoutModeManager.effectiveThresholds.value` instead of `settings.alert*.first()`. Because `effectiveThresholds` is `SharingStarted.Eagerly`, `.value` is always populated — no suspension needed.

**Three call sites:**

1. **`checkReading` (line 296–313):** the read-and-pass-down spot.
   - Currently reads `settings.alertLow.first()` / `settings.alertHigh.first()` (line 305–306) and passes to `checkPredictive`.
   - Change: read from `effectiveThresholds.value.alertLowMgdl` / `alertHighMgdl` instead. Predict-alert path inherits workout values for free.

2. **`checkLowAlerts` (line 315–345):** reads `urgentLowThreshold` (317) and `lowThreshold` (319) directly from settings.
   - Change: replace both with `effectiveThresholds.value.alertUrgentLowMgdl` / `alertLowMgdl`. Keep enabled-flag reads as-is (`alertUrgentLowEnabled`, `alertLowEnabled` — those are user toggles, not affected by workout mode).

3. **`checkHighAlerts` (line 347–377):** symmetric — replace `urgentHighThreshold` (349) and `highThreshold` (351) with the workout-aware versions.

**Stale alert suppression — `checkStale` (line 427–443):**

Currently early-returns if `staleEnabled` is false. Add a second early-return: `if (workoutModeManager.state.value is WorkoutMode.On) return`.

Note: this is *additive* to Pause All. `pauseAllAlerts` (line 497–500) only iterates `AlertCategory.entries` (LOW + HIGH) and does NOT pause the stale alert. So workout mode is the only way to suppress stale alerts. If user has Pause All active but workout mode Off, stale alerts will still fire.

**Pause All interaction:**

PauseAll and Workout Mode operate at different layers. PauseAll suppresses alert *firing* per-category at a level threshold (line 322, 333, etc.). Workout Mode shifts threshold *values*. They compose cleanly:
- BG=99 mg/dL, Workout ON, no Pause → low alert fires (workout low=108, 99 < 108).
- BG=99 mg/dL, Workout ON, Pause LOW at URGENT level → no low alert (paused).
- BG=99 mg/dL, Workout OFF, no Pause → no low alert (standard low=72, 99 > 72).

No code change to AlertManager's pause-all logic.

### 6. `ui/MainViewModel.kt` integration

- Inject `WorkoutModeManager`
- Expose `val workoutMode: StateFlow<WorkoutMode>` (forwarded from manager)
- Expose `val effectiveThresholds: StateFlow<EffectiveThresholds>` (forwarded)
- Replace existing `val bgLow: StateFlow<Float> = settings.bgLow` (line 163) and `val bgHigh: StateFlow<Float> = settings.bgHigh` (line 166) with derived flows: `val bgLow = effectiveThresholds.map { it.displayLowMgdl }.stateIn(...)` and same for `bgHigh`. Keeps consumer call-sites (`bgLow.value.toDouble()` at lines 270, 280) working unchanged — they now silently use workout values when mode is On.
- `guidanceState` (line 258–273) keeps reading from `nextEvent` directly. The pre-activity guidance flow is independent of workout-mode trigger logic — they can both be active without conflict (PreActivityCard shows pre-workout guidance, the pill shows workout-mode state).
- Add `fun toggleWorkoutMode()` that calls `viewModelScope.launch { manager.toggle() }`

### 6b. Other consumers of `bgLow` / `bgHigh` — must become workout-aware

The display thresholds (`bgLow`/`bgHigh`) feed several surfaces. Each must read from `WorkoutModeManager.effectiveThresholds` instead of `settings.bgLow`/`bgHigh` directly, otherwise the user sees inconsistent in-range bands across surfaces during workout.

| File | Current pattern | Change |
|---|---|---|
| `ui/MainViewModel.kt:163,166` | `settings.bgLow.stateIn(...)` | derive from `effectiveThresholds` (covered above) |
| `service/StrimmaService.kt:115–116` | `settings.bgLow.stateIn(scope, Eagerly, ...)` | inject `WorkoutModeManager`, use `effectiveThresholds.map { it.displayLowMgdl }` |
| `widget/StrimmaWidget.kt:58–59` | `settings.bgLow.first()` | inject `WorkoutModeManager`, read `effectiveThresholds.value.displayLowMgdl` |
| `webserver/LocalWebServer.kt:137–138` | `settings.bgLow.first()` | same as widget |
| `ui/story/StoryViewModel.kt:67–68` | `settings.bgLow.first()` | same |
| `ui/ExerciseHistoryScreen.kt:104,107,196` | `settings.bgLow` for *historical* sessions | **Out of scope.** Historical screens already use *current* thresholds (pre-existing limitation). Don't widen the scope of this change. |

The widget and web server are external surfaces a user might glance at without opening the app — keeping them workout-aware matters for trust ("why does the widget say I'm high but the app says I'm in range?").

### 7. `ui/MainScreen.kt` integration

Add workout pill to the existing status row (where Pause All pill lives).

**OFF state:** outlined chip, tertiary text, low emphasis.
```
[ 🏃 Workout mode ]
```
Tap → calls `viewModel.toggleWorkoutMode()`. No confirmation sheet (single tap).

**ON state:** filled pill, `TintInRange` background, prominent.
```
[ 🏃 Workout · 0:42  ✕ ]
```
Elapsed time computed from `state.since`. Tap → `toggleWorkoutMode()` (turns OFF, no confirmation — OFF is the safer direction).

If both Pause All and Workout Mode are active, render both pills side-by-side.

### 8. `notification/NotificationHelper.kt` integration

- Inject `WorkoutModeManager`. Read `state.value` and `effectiveThresholds.value` when building notification.
- Title suffix: append ` · Workout` when `state is On`
- Expanded subtext: if `On(MANUAL)`, show `"Workout 0:42"` (elapsed = now − sinceMs); if `On(CALENDAR)`, show event title from `nextEvent.title`
- New action button: label flips between `"Start workout"` (state=Off) and `"End workout"` (state=On). Sits alongside existing snooze actions. Triggers `WorkoutModeManager` via a new `WorkoutModeReceiver` (mirrors `AlertSnoozeReceiver`).

**Re-render trigger:** `NotificationHelper` currently rebuilds only when `StrimmaService` pushes a new CGM reading (every 1–5 min). Workout-mode toggle must trigger an *immediate* re-render so the title/subtext/action label update without delay.

`StrimmaService` adds a coroutine that observes `workoutModeManager.state` and triggers `notificationHelper.refresh(latestReading)` on every emission (debounce not needed — user-triggered toggles are rare events). The same observer covers calendar-driven On/Off transitions and the safety-timeout auto-Off.

### 9. `notification/GraphRenderer.kt` (Canvas) integration

The notification graph bitmap renders an in-range band from `bgLow`/`bgHigh`. Currently `StrimmaService` (line 115–116) holds these StateFlows from settings directly and passes them down. After the consumer fan-out fix (section 6b), `StrimmaService` derives these from `WorkoutModeManager.effectiveThresholds`, so `GraphRenderer` automatically receives workout-aware values when mode is On. No change to `GraphRenderer` itself.

### 10. `ui/settings/WorkoutSettings.kt` (new)

New Composable + ViewModel pair, lives in the Exercise group of Settings.

Settings UI:
```
Settings → Exercise
  ├── Calendar (existing)
  ├── Per-category targets (existing)
  └── Workout mode (NEW)
        ├── Auto-off after [3] hours        [slider 1–12]
        ├── ─── Workout thresholds ───
        ├── Low                  [6.0 mmol]
        ├── Urgent low           [5.0 mmol]
        ├── High                 [14.0 mmol]
        ├── Urgent high          [16.0 mmol]
        ├── [Reset to defaults]
        └── ⓘ Active during exercise. Replaces both your alert
            thresholds and the in-range band on graphs. Suppresses
            stale-sensor alerts. Triggers from the MainScreen toggle
            or scheduled calendar events.
```

ViewModel exposes the 4 thresholds + max-hours + setters. Uses existing `GlucoseUnit` for display conversion (mmol/mg/dL).

**Validation note:** UI should soft-warn (not block) if user enters `urgentLow >= low` or `urgentHigh <= high`, since AlertManager will treat both as "fires" but the regular variant becomes redundant. Decide concrete UX during implementation; not blocking for spec.

### 11. `notification/WorkoutModeReceiver.kt` (new)

`BroadcastReceiver` that handles notification action taps. Mirrors `AlertSnoozeReceiver` exactly:

```kotlin
@AndroidEntryPoint
class WorkoutModeReceiver : BroadcastReceiver() {
    @Inject lateinit var workoutModeManager: WorkoutModeManager

    override fun onReceive(context: Context, intent: Intent) {
        // Use goAsync() because manager.toggle() is suspend
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { workoutModeManager.toggle() } finally { pending.finish() }
        }
    }
}
```

**AndroidManifest.xml registration** (`app/src/main/AndroidManifest.xml`):
```xml
<receiver android:name=".notification.WorkoutModeReceiver" android:exported="false" />
```

### 12. i18n strings (`app/src/main/res/values/strings.xml` + `values-sv/strings.xml`)

All new user-facing strings go through `strings.xml`. Swedish translations required (Strimma already ships sv-SE). Initial string keys:

```xml
<string name="workout_mode">Workout mode</string>
<string name="workout_mode_start">Start workout</string>
<string name="workout_mode_end">End workout</string>
<string name="workout_mode_active_for">Workout %1$s</string>          <!-- formatted as 0:42 -->
<string name="workout_mode_settings_title">Workout mode</string>
<string name="workout_mode_auto_off_after">Auto-off after %1$d hours</string>
<string name="workout_mode_thresholds_header">Workout alert thresholds</string>
<string name="workout_mode_threshold_low">Low</string>
<string name="workout_mode_threshold_urgent_low">Urgent low</string>
<string name="workout_mode_threshold_high">High</string>
<string name="workout_mode_threshold_urgent_high">Urgent high</string>
<string name="workout_mode_reset_defaults">Reset to defaults</string>
<string name="workout_mode_settings_info">Active during exercise. Replaces standard alert thresholds and the in-range band. Suppresses stale-sensor alerts. Triggers from the MainScreen toggle or scheduled calendar events.</string>
```

Swedish equivalents in `values-sv/strings.xml` (per existing i18n pattern).

## Data Flow

### Manual ON (user taps MainScreen pill)

```
MainScreen pill tap
  → MainViewModel.toggleWorkoutMode()
  → WorkoutModeManager.toggle() → state.value is Off, so setManualOn()
  → SettingsRepository: manualWorkoutSinceMs = nowMs, manualOffOverrideUntilMs = null
  → state combine() re-emits On(MANUAL, sinceMs=nowMs, expiresAtMs=nowMs+3h)
  → effectiveThresholds re-emits workout values (display + alerts)
  → AlertManager.checkReading reads effectiveThresholds.value on next CGM eval
  → MainViewModel.workoutMode emits → MainScreen pill flips to ON
  → StrimmaService observer fires → NotificationHelper.refresh(latestReading)
    → notification rebuilds with title suffix + new action label
```

### Calendar event auto-trigger

```
nextEvent: WorkoutEvent(start=18:00, end=19:00, ...) emitted by CalendarPoller
  while in lookahead window (e.g., emitted at 17:30)
At 18:00: ticker tick → state combine() recomputes
  → manualSinceMs == null, overrideUntilMs == null
  → isCalendarActive = (now >= 18:00 && now <= 19:00) = true
  → R5 fires → On(CALENDAR, sinceMs=18:00ms, expiresAtMs=19:00ms)
  → downstream same as above
```

### Manual ON expires (safety timeout)

```
14:00: setManualOn() → manualWorkoutSinceMs=14:00ms, derived expiresAtMs=17:00ms
17:00: ticker tick → state recomputed
  → manualSinceMs != null && now >= expiresAtMs → R2: cleanup
  → SettingsRepository.setManualWorkoutSinceMs(null)
  → fall-through: no overrideUntil, no calendar event → state = Off
  → notification refresh removes title suffix, action label flips to "Start workout"
  (No special "auto-ended" notification — silent transition)
```

### Manual OFF during active calendar event

```
Calendar event 18:00–19:00 active, user taps OFF at 18:30
  → MainViewModel.toggleWorkoutMode()
  → manager.toggle() → state.value is On(CALENDAR, _, expiresAtMs=19:00ms)
  → setManualOff() reads state, source==CALENDAR → manualOffOverrideUntilMs = 19:00ms
  → state combine() re-emits: R3 fires (overrideUntilMs > now) → state = Off
  → 19:00: ticker tick → R4: override cleanup → no calendar active → state stays Off
  → 20:00: next calendar event starts → R5 fires → state = On(CALENDAR), normal logic resumes
```

### Manual ON → Calendar takeover (seamless transition)

```
14:00: Manual ON → On(MANUAL, expiresAtMs=17:00ms). User goes for a run.
16:00: Calendar event 16:00–21:00 starts. State stays On(MANUAL) (R1 still fires).
17:00: Manual expires. R1 fails → R2 cleanup → R5 fires (calendar still active) →
       state transitions On(MANUAL) → On(CALENDAR, expiresAtMs=21:00ms)
       Pill label updates: source changes from "Workout 3:00" (manual elapsed) to
       event title (calendar). No flicker through Off — single state emission switches source.
21:00: Calendar event ends. R5 fails → state = Off.
```

### Process death / reboot

```
14:30 manual ON → manualWorkoutSinceMs=14:30ms in DataStore
15:00 process killed
15:15 process restarted
WorkoutModeManager init reads DataStore via combine() flow
  → manualSinceMs=14:30ms, maxHours=3, now=15:15ms
  → derived expiresAtMs=17:30ms, now < expiresAtMs
  → R1 fires → On(MANUAL, sinceMs=14:30ms, expiresAtMs=17:30ms)
```

## Error Handling

- **DataStore first-run / corruption:** all keys have defaults. `manualWorkoutSinceMs` absent → state = Off. Safe.
- **Calendar permission revoked:** `CalendarPoller.poll` (line 51–62) catches the exception and emits `null`. `WorkoutModeManager` sees no event → manual still works, calendar trigger silently disabled.
- **Notification permission missing:** affects rendering only. Pill on MainScreen still works, manager state intact.
- **Process death / reboot:** state reconstructed from DataStore timestamps on init. No volatile state lost.
- **Clock changes (user moves clock backwards):** manual ON might appear "not yet expired" longer. Acceptable — not a threat model.
- **Settings change while On:** `AlertManager` reads `effectiveThresholds.value` per CGM evaluation; UI consumers receive new values via the StateFlow. Picks up changes immediately.
- **WorkoutModeReceiver fires while service stopped:** receiver uses `goAsync()` and a coroutine; `WorkoutModeManager` is `@Singleton` and lives in `SingletonComponent`, so it's available even if the foreground service isn't running. State change persists to DataStore. When service restarts, observer picks up the change.

## Edge Cases

- **Overlapping calendar events:** `CalendarPoller.nextEvent` only emits ONE event at a time (the next one within lookahead). If two events overlap, the poller picks one (existing contract — we don't change it). `WorkoutModeManager` filters that single event for "currently active" and treats `expiresAtMs = nextEvent.endTime`.
- **Calendar event ends while BG in workout-low range:** state flips to Off; next reading uses standard thresholds. No retroactive alert (alerts only evaluate per CGM reading, not on threshold change).
- **maxHours changed mid-session (e.g., 3→1) while 2h elapsed:** combine() re-fires with new maxHours → derived expiresAtMs shifts → R1 fails → R2 cleanup → state = Off. Abrupt but correct.
- **Glucose unit toggle (mmol ↔ mg/dL):** thresholds stored as mg/dL internally (matches Strimma convention from CLAUDE.md). UI converts at display via existing `GlucoseUnit` helper. No data migration.
- **Pause All + Workout Mode both active:** independent layers. Both pills render. Workout shifts threshold *values*; Pause All suppresses alert *firing* per category at a level. They compose:
  - Low/high alerts: Pause All wins at firing time. Workout values invisible while paused, resume when pause expires.
  - **Stale alerts:** workout mode is the ONLY suppression — Pause All does not cover stale (`pauseAllAlerts` line 497–500 only iterates LOW + HIGH). So `Pause All ON, Workout OFF` → stale still fires.
- **Manual ON during active calendar event:** R1 wins over R5 → state = On(MANUAL). Source label shows manual elapsed time, not event title. When manual expires (R2), R5 fires if calendar event still active → seamless source transition (see "Manual ON → Calendar takeover" data flow).
- **PreActivityCard interaction:** When calendar auto-triggers workout mode, the existing PreActivityCard may still be rendering pre-workout guidance (it shows from `triggerMinutes` before event start through event end). Both render concurrently — the pill above, the card below. No conflict; they describe different things (pill: "we are in workout mode now", card: "here's your pre-workout BG context").

## Testing

Per Strimma's testing rules: integration over isolation. Real Room (in-memory), real `SettingsRepository` (Robolectric DataStore), real domain classes. The only fake is a `CalendarPoller` test double (hand-written, not a mock framework — per project rules).

Use `kotlinx.coroutines.test.runTest` + `TestDispatcher` to advance virtual time for ticker / safety-timeout tests.

```
WorkoutModeManagerTest (Robolectric, real DataStore, fake CalendarPoller)
  ├── initial state = Off
  ├── setManualOn → state = On(MANUAL, expiresAtMs = nowMs + maxHours*MS_PER_HOUR)
  ├── advance virtual time past expiresAtMs → state = Off, manualWorkoutSinceMs cleared
  ├── fake calendar emits event with start <= now <= end → state = On(CALENDAR)
  ├── fake calendar emits future event (start > now) → state = Off (filter rejects)
  ├── manual OFF during active calendar → state = Off, overrideUntil = event.endTime
  ├── advance time past event.endTime → overrideUntil cleared, state = Off
  ├── manual ON, then calendar event starts → state stays On(MANUAL) (R1 wins over R5)
  ├── manual ON expires while calendar still active → seamless On(MANUAL) → On(CALENDAR)
  ├── manager recreation (simulates process death) → state restored from DataStore timestamps
  ├── change maxHours mid-session (3→1) when 2h elapsed → state = Off on next combine
  └── effectiveThresholds: Off → uses settings.bgLow/bgHigh + alert*; On → uses workoutAlert*

AlertManagerWorkoutTest (real Room in-memory, real SettingsRepository,
                        real AlertManager, real WorkoutModeManager,
                        fake CalendarPoller)
  Standard defaults (Strimma SettingsRepository.kt:218-223):
    bgLow=72, bgHigh=180, alertLow=72, alertHigh=180, urgentLow=54, urgentHigh=234
  Workout defaults: low=108, urgent_low=90, high=252, urgent_high=288 (= 6/5/14/16 mmol)

  Low side (asymmetric: AlertManager uses `<` for low, `<=` for urgent low):
  ├── BG=99 (5.5 mmol), mode OFF → no alert (99 > 72)
  ├── BG=99 (5.5 mmol), mode ON  → low alert fires (99 < 108)
  ├── BG=90 (5.0 mmol), mode ON  → urgent low fires (90 <= 90)
  ├── BG=91 (5.05 mmol), mode ON → low fires, urgent low does NOT (91 < 108, 91 > 90)
  ├── BG=72 (4.0 mmol), mode ON  → urgent low fires (72 <= 90), takes precedence over low

  High side (asymmetric: AlertManager uses `>` for high, `>=` for urgent high):
  ├── BG=234 (13.0 mmol), mode OFF → high alert fires (234 > 180), urgent high also fires (234 >= 234)
  ├── BG=234 (13.0 mmol), mode ON  → no alert (234 < 252)
  ├── BG=288 (16.0 mmol), mode ON  → urgent high fires (288 >= 288)
  ├── BG=253 (14.06 mmol), mode ON → high fires, urgent high does NOT (253 > 252, 253 < 288)

  Stale alerts:
  ├── stale reading (>10 min old), mode OFF → stale alert fires
  ├── stale reading, mode ON → no stale alert (suppressed by workout mode)
  ├── stale reading, mode OFF, Pause All active → stale STILL fires
  │     (Pause All only covers LOW + HIGH categories per AlertManager.kt:497-500;
  │      workout mode is the only stale suppression)

  Predict alerts:
  ├── reading trajectory predicting BG crossing low within prediction window
  │   ├── mode OFF (standard low=72) → predict-low fires when crossing < 72
  │   └── mode ON (workout low=108) → predict-low fires when crossing < 108
  │     (predict uses lowThreshold/highThreshold passed from checkReading,
  │      which after the fix reads from effectiveThresholds — verified by integration)

  Reactivity:
  ├── thresholds changed via SettingsRepository setter mid-session
  │     → next CGM reading evaluation reflects new values immediately
  └── workout mode toggled via manager.setManualOn()
        → next CGM reading evaluation uses workout thresholds, no service restart

WorkoutPillUiTest (Compose UI test)
  ├── pill renders chip variant when state = Off
  ├── pill renders filled variant with elapsed time when state = On(MANUAL)
  ├── pill renders filled variant with event title when state = On(CALENDAR)
  ├── tap pill while OFF → manager.setManualOn() called
  └── tap pill while ON → manager.setManualOff() called

WorkoutNotificationActionTest (Robolectric)
  ├── action button label = "Start workout" when state = Off
  ├── action button label = "End workout" when state = On
  ├── tapping action while Off → state = On(MANUAL)
  ├── tapping action while On → state = Off (manual)
  └── tapping while On(CALENDAR) sets manualOffOverrideUntilMs = event.endTime

NotificationRefreshTest (Robolectric, real StrimmaService observer)
  ├── workout state Off → On → notification re-rendered immediately (not waiting for next CGM reading)
  ├── workout state On → Off (auto-timeout) → notification re-rendered immediately
  └── calendar event boundary → notification re-rendered

WorkoutSettingsTest (Compose + Robolectric)
  ├── 4 thresholds + maxHours load from SettingsRepository on init
  ├── threshold edits persist to DataStore (verify by re-reading flow)
  ├── reset to defaults restores all 4 values
  ├── unit toggle (mmol/mgdl) re-renders display without changing storage
  └── invalid threshold input (e.g., urgent_low > low) — UI behavior TBD during impl

ConsumerFanOutTest (per affected consumer)
  Verify each consumer reads workout-aware thresholds when mode is On:
  ├── StrimmaWidget renders in-range color for BG=12 mmol when workout ON
  ├── LocalWebServer JSON response shows workout bgLow/bgHigh when mode ON
  └── StoryViewModel uses workout thresholds for story rendering when mode ON
```

No mocks for `AlertManager` or `WorkoutModeManager`. CalendarPoller is faked because real CalendarPoller depends on Android `ContentResolver` (system boundary).

## Documentation Gate

Per project CLAUDE.md, user-visible features require doc updates before completion:

- **New:** `docs/guide/workout-mode.md` — user guide (when to use, configuration, what changes during workout)
- **Update:** `docs/guide/alerts.md` (if exists) — note workout mode interaction
- **Update:** `docs/internal/spec.md` — add Workout Mode section
- **New screenshots:** MainScreen with workout pill ON; Settings → Exercise → Workout mode

## Out of Scope (v1)

Explicitly deferred:

- **Activity Recognition API trigger** — heuristic, brittle for walking-vs-running. Revisit if "I forgot to flip it" becomes a real complaint after release.
- **Notification listener inference** ("recording workout" notifications from Strava etc.) — high coverage but per-app maintenance burden.
- **Quick Settings tile** — adds reach but TileService implementation cost. Notification action + MainScreen pill cover the primary access patterns.
- **Per-workout-type thresholds** — one workout-mode threshold set, not per ExerciseCategory. Per-category targets remain display-only.
- **Auto-extend timeout on detected motion** — would require Activity Recognition.
- **Tagging Health Connect glucose writes with workout context** — `HealthConnectManager.writeGlucoseReading` doesn't currently expose a workout-state field. Pre-existing limitation; not regressing.
- **ExerciseHistoryScreen historical thresholds** — already uses *current* `bgLow`/`bgHigh` for past sessions (pre-existing). Don't widen scope.
- **Statistics on workout-mode usage** — could show "Time in workout mode this week" in Stats; deferred.

## Open Questions

None blocking implementation. Two minor decisions left for impl time:
- **Threshold-validation UX** in WorkoutSettings (soft warn vs allow-anything if user enters `urgentLow >= low`). See section 10.
- **PreActivityCard hide-when-pill-active**: when both render for the same calendar event, do we hide the card to reduce visual clutter? Defer to implementation; default is "show both."
