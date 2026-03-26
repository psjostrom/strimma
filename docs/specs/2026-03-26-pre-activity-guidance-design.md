# Pre-Activity Guidance — Design Spec

**Date:** 2026-03-26
**Status:** Approved
**Branch:** feat/pre-activity-guidance

---

## Summary

Read upcoming workouts from the device calendar (Android CalendarProvider). When a workout is within a configurable lookahead window, show a readiness assessment and carb guidance card on the main screen. At a configurable trigger time before the workout, add a one-liner to the persistent notification that updates with each BG reading.

---

## 1. Data Source: CalendarProvider

No OAuth, no Google Cloud project. Read directly from the system calendar database via `ContentResolver` + `CalendarContract`.

- **Permission:** `READ_CALENDAR` (runtime request)
- **Calendar selection:** User picks a specific calendar by name in Settings (e.g. "Training"). No calendar selected = feature disabled.
- **Polling:** Every 15 minutes via `StrimmaService` coroutine lifecycle. Query CalendarProvider for events in the lookahead window from the selected calendar.
- **Caching:** Cache the next upcoming event in memory (title, start time, category). Re-evaluate guidance on each new BG reading using the cached event.
- **Alarm scheduling:** When an event is found within the lookahead window, schedule `AlarmManager.setExactAndAllowWhileIdle()` for the notification trigger point (e.g. 2h before start).
- **Cleanup:** When the event's start time passes (+ 15 min grace), clear the guidance state and cancel any pending alarm.

### Why CalendarProvider over Google Calendar API

- Local-first: matches Strimma's architecture
- Zero cloud setup: no Google Cloud project, no OAuth consent screen, no token refresh
- Works offline
- Supports any calendar provider (Google, Samsung, Outlook)
- Workout calendars from Intervals.icu, Garmin Connect, TrainingPeaks sync to the device calendar automatically

---

## 2. Workout Categories

Parsed from event title via case-insensitive keyword matching:

| Category | Keywords | Default Target BG (mmol/L) |
|----------|----------|----------------------------|
| Interval | interval, tempo, fartlek, threshold, speed | 9-11 |
| Long | long, LSR, 90min, 2h, marathon | 8-10 |
| Strength | gym, strength, weights, core, lift | 7-9 |
| Easy | easy, recovery, walk | 7-9 |
| Fallback | (no keyword match) | 7-10 |

**Match priority:** First match wins, in the order listed above. "Interval" checked before "Easy" so "easy intervals" matches Interval.

Keywords are hardcoded. Category matching is best-effort convenience. Target BG ranges are configurable per category in Settings.

The event title is displayed on the guidance card for context (e.g. "Easy Run in 2h 15min").

---

## 3. Readiness Assessment

Adapted from Springa's `assessPreRunReadiness()`. Uses only local data available in Strimma.

### Assessment Dimensions

Each dimension produces a level (`ready`, `caution`, or `wait`) and a reason string.

**BG Level** (against the category's configurable target range):

| Condition | Level | Reason |
|-----------|-------|--------|
| BG < 81 mg/dL (4.5 mmol/L) | `wait` | "BG too low to start" |
| BG < target low | `caution` | "BG below target for [category]" |
| BG in target range | `ready` | - |
| BG > 252 mg/dL (14.0 mmol/L) | `caution` | "BG high -- expect steeper drop" |
| BG > target high (but <= 14.0) | `ready` | - |

**Trend Slope** (velocity from PredictionComputer.fitWeightedVelocity(), in mg/dL per minute):

| Condition | Level | Reason |
|-----------|-------|--------|
| slope < -0.9 mg/dL/min (-0.05 mmol/L/min) | `wait` | "BG dropping fast" |
| slope -0.9 to -0.54 mg/dL/min (-0.05 to -0.03 mmol/L/min) | `caution` | "BG trending down" |
| slope >= -0.54 mg/dL/min (>= -0.03 mmol/L/min) | `ready` | - |

Note: PredictionComputer already computes a weighted velocity from the last 12 min of readings via `fitWeightedVelocity()` (currently `internal` visibility — will be made public). PreActivityAssessor reuses this rather than recomputing. If velocity is unavailable (< 2 readings), treat the trend dimension as `ready` (no data = no warning).

**Compound Rule** (highest priority, supersedes individual BG + trend assessments):

| Condition | Level | Reason |
|-----------|-------|--------|
| BG < 144 mg/dL (8.0 mmol/L) AND slope < -0.54 mg/dL/min | `wait` | "BG below 8 and falling -- high hypo risk" |

**30-Minute Forecast** (from existing PredictionComputer):

| Condition | Level | Reason |
|-----------|-------|--------|
| Projected BG at 30 min < 99 mg/dL (5.5 mmol/L) | `caution` | "Forecast: BG below 5.5 in 30 min" |

**IOB** (from existing IOBComputer):

| Condition | Level | Reason |
|-----------|-------|--------|
| IOB >= 0.5u | informational | "IOB: [X]u -- added [Y]g carbs to recommendation" |

IOB does not change the readiness level directly -- it adds carbs to the recommendation.

### Overall Readiness

Worst of all assessment levels: if any dimension is `wait`, overall is `wait`. If any is `caution` and none is `wait`, overall is `caution`. Otherwise `ready`.

---

## 4. Carb Recommendation

Carbs stack from multiple factors, rounded to nearest 5g:

| Condition | Base Carbs |
|-----------|------------|
| BG < 81 mg/dL / 4.5 mmol/L (hypo zone) | 20g |
| BG < 144 mg/dL / 8.0 mmol/L AND slope < -0.54 mg/dL/min (compound rule) | 20g |
| BG < category target low (but >= 4.5, no compound) | 15g |
| BG in range, stable/rising | 0g |

**IOB addition** (stacks on top of base):

- If IOB >= 0.5u: add `round((IOB * 12) / 5) * 5` grams
- Rationale: ~12g carbs per unit insulin during exercise (from Springa's validated model)

**Total pre-activity carbs = base + IOB carbs**

Example: BG 6.5 mmol/L, falling, IOB 0.8u, category Easy (target 7-9):
- Base: 20g (compound rule: BG < 8 AND falling)
- IOB: round((0.8 * 12) / 5) * 5 = 10g
- Total: 30g

### Timing Suggestion

If total carbs > 0 and workout is > 45 min away: "~30 min before start"
If total carbs > 0 and workout is 15-45 min away: "now"
If total carbs > 0 and workout is < 15 min away: "immediately"

---

## 5. UI: Main Screen Guidance Card

Appears above the graph when a workout is within the lookahead window. Disappears when the event passes.

### Layout

```
+-------------------------------------+
| [icon] Easy Run in 2h 15min         |
|                                     |
| [badge] READY                       |
|                                     |
| Target: 7-9 mmol/L                  |
| Current: 7.2 [arrow]  ·  IOB 0.3u  |
|                                     |
| > Consider 15g carbs ~30 min before |
| > Check trend again closer to start |
+-------------------------------------+
```

### Styling

- Surface card: 12dp radius, `outlineVariant` border (matching existing graph/stats cards)
- Tinted background based on readiness:
  - `ready`: `TintInRange` (#152535)
  - `caution`: `TintWarning` (#35280E)
  - `wait`: `TintDanger` (#351525)
- Readiness badge: uppercase, SemiBold, colored text (InRange/AboveHigh/BelowLow status colors)
- Suggestions prefixed with ">" in secondary text color
- IOB shown only if > 0

### Content Rules

- Always show: event title, time until start, readiness badge, target range, current BG + trend
- Show carb recommendation only if total > 0
- Show IOB only if IOB > 0
- Show all assessment reasons (no cap)
- "Check trend again closer to start" shown when workout is > 1h away

---

## 6. UI: Notification One-Liner

Added to the existing notification content text when the notification trigger alarm fires. Compact single line:

```
[icon] Run in 1h 45min -- 7.2 [arrow] -- consider 15g carbs
```

If no carbs needed:
```
[icon] Run in 1h 45min -- 7.2 [arrow] -- ready
```

Updates on each BG reading (piggybacking on the existing notification update cycle). Disappears when the event passes.

No changes to notification layout or graph bitmap -- the one-liner is added to the existing text content.

---

## 7. Settings

New settings in the existing Exercise section (`ui/settings/ExerciseSettings.kt`):

| Setting | Type | Default | Storage |
|---------|------|---------|---------|
| Workout calendar | Picker (list from CalendarProvider) | (none) | DataStore |
| Lookahead window | Slider (1-6h, step 1h) | 3 hours | DataStore |
| Notification trigger | Slider (30min-4h, step 30min) | 2 hours | DataStore |
| Easy: target range | Min/Max number fields | 7-9 mmol/L | DataStore |
| Interval: target range | Min/Max number fields | 9-11 mmol/L | DataStore |
| Long: target range | Min/Max number fields | 8-10 mmol/L | DataStore |
| Strength: target range | Min/Max number fields | 7-9 mmol/L | DataStore |

Calendar picker queries `CalendarContract.Calendars` for display names + IDs. Shows "(none)" when disabled.

Target ranges stored in mg/dL internally (consistent with all other thresholds). Display-time conversion via `GlucoseUnit`.

---

## 8. Architecture

### New Files

| File | Purpose |
|------|---------|
| `data/calendar/CalendarReader.kt` | CalendarProvider queries, event parsing, permission checks |
| `data/calendar/WorkoutEvent.kt` | Data class: title, startTime, endTime, category, calendarId |
| `data/calendar/WorkoutCategory.kt` | Enum (EASY, INTERVAL, LONG, STRENGTH) with keyword lists and default targets |
| `data/calendar/PreActivityAssessor.kt` | Pure function: (BG, trend, IOB, timeToWorkout, category, targetRange) -> GuidanceState |
| `data/calendar/GuidanceState.kt` | Sealed class: NoWorkout / WorkoutApproaching(event, readiness, reasons, carbRecommendation) |
| `receiver/WorkoutAlarmReceiver.kt` | BroadcastReceiver for AlarmManager notification trigger |
| `ui/PreActivityCard.kt` | Compose card component for main screen |

### Modified Files

| File | Change |
|------|--------|
| `service/StrimmaService.kt` | 15-min calendar poll coroutine, AlarmManager scheduling/cancellation |
| `notification/NotificationHelper.kt` | Append workout one-liner to notification text when guidance is active |
| `ui/MainScreen.kt` | Render `PreActivityCard` above graph when guidance state is active |
| `ui/MainViewModel.kt` | Expose `guidanceState: StateFlow<GuidanceState>`, recompute on each reading |
| `ui/settings/ExerciseSettings.kt` | Calendar picker, target range editors, lookahead/trigger sliders |
| `data/SettingsRepository.kt` | New DataStore keys: calendarId, calendarName, lookahead, trigger, per-category target ranges |
| `AndroidManifest.xml` | `READ_CALENDAR` + `SCHEDULE_EXACT_ALARM` permissions, `WorkoutAlarmReceiver` registration |
| `di/AppModule.kt` | Provide CalendarReader |

### Data Flow

```
CalendarProvider
  |
  v (every 15 min)
CalendarReader.getNextWorkout(calendarId, lookaheadMs)
  |
  v
WorkoutEvent (cached in StrimmaService)
  |
  +---> AlarmManager (schedule notification trigger)
  |
  v (on each new BG reading)
PreActivityAssessor.assess(
    currentBg, trendSlope, iob,
    timeToWorkout, category, targetRange
)
  |
  v
GuidanceState.WorkoutApproaching(...)
  |
  +---> MainViewModel -> PreActivityCard (main screen)
  +---> NotificationHelper (one-liner, after alarm fires)
```

---

## 9. Permissions

Two permissions required:

**`READ_CALENDAR`** (dangerous — runtime request):
- Request when user first taps the calendar picker in Settings
- If denied, show rationale dialog explaining why Strimma needs calendar access
- If permanently denied, guide to app settings
- No calendar selected = no permission request = feature fully disabled

**`SCHEDULE_EXACT_ALARM`** (normal on API 33+):
- Required for `AlarmManager.setExactAndAllowWhileIdle()`
- Declared in manifest, no runtime request needed on API 33 (minSdk)
- On API 34+ the user may need to grant "Alarms & reminders" in system settings — check `canScheduleExactAlarms()` and guide if denied

---

## 10. Testing

### PreActivityAssessor (Pure Function)

Exhaustively testable -- no Android dependencies:

- BG level assessment for each threshold boundary (80, 81, target low, target high, 252, 253 mg/dL)
- Trend slope assessment at each boundary (-1.0, -0.9, -0.6, -0.54, -0.5 mg/dL/min)
- Null velocity (< 2 readings) -> trend dimension is `ready`
- Compound rule: BG < 8 AND falling triggers wait, BG >= 8 AND falling does not
- IOB carb stacking: 0u, 0.4u (below threshold), 0.5u, 1.0u, 2.0u
- Overall readiness: worst-of-all logic
- Carb rounding to 5g
- Timing suggestion based on time-to-workout
- Each category with its own target range

### CalendarReader

- Robolectric with shadow CalendarProvider
- Query returns events in window, filters by calendar ID
- Category parsing: keyword matching priority, fallback case
- Empty calendar, no permission, no matching events

### WorkoutCategory

- Keyword matching: "Easy Run" -> Easy, "Tempo Intervals" -> Interval, "Long Run" -> Long
- Priority: "Easy Intervals" -> Interval (Interval checked first)
- No match -> Fallback

---

## 11. Not in Scope

- During-run fuel rate recommendations (needs BG model from historical data -- Springa's domain)
- TSB/fatigue adjustment (needs Intervals.icu fitness data)
- Post-run analysis (already handled by Health Connect + ExerciseBGAnalyzer)
- Writing to calendar
- Multiple calendar support (one calendar is enough for v1)
- Custom keyword configuration (hardcoded keywords, configurable target ranges)
