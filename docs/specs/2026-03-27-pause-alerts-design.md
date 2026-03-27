# Pause Alerts by Category

Pause low or high alert groups for a configurable duration. Primary use case: workouts where highs are expected and acceptable, but lows still need to fire.

## Categories

| Category | Alerts included |
|----------|----------------|
| Low | Urgent Low, Low, Low Soon |
| High | Urgent High, High, High Soon |

Stale and Push Fail are not pauseable — they're operational, not BG-range alerts.

## Duration Options

1h (default), 1.5h, 2h. Presented as segmented buttons / chips.

## Data Model

Stored in existing `snoozePrefs` SharedPreferences (same file as per-alert snooze). Keys: `"pause_low"`, `"pause_high"`. Value: expiry timestamp (Long, epoch ms). Zero or absent = not paused.

New enum `AlertCategory` in AlertManager:
```kotlin
enum class AlertCategory(val prefsKey: String) {
    LOW("pause_low"),
    HIGH("pause_high")
}
```

New methods on AlertManager:
- `pauseCategory(category: AlertCategory, durationMs: Long)` — sets expiry
- `cancelPause(category: AlertCategory)` — removes key
- `isPaused(category: AlertCategory): Boolean` — checks expiry, auto-clears if expired
- `pauseExpiryMs(category: AlertCategory): Long?` — returns expiry or null (for UI countdown)

## AlertManager Behavior

`checkReading()`: before evaluating low alerts, check `isPaused(LOW)`. If paused, skip all three low alerts AND cancel any active low notifications. Same for high.

Per-alert snooze (30-min, from notification action button) remains independent. A category pause and a per-alert snooze can coexist — the category pause takes precedence (alert won't fire regardless).

When a pause expires, alerts resume on the next reading — no catch-up firing.

## UI — Main Screen

### Top Bar Icon (always visible)
Bell-slash icon (`Icons.Outlined.NotificationsOff` or similar) in the top bar, between the existing exercise icon and stats icon. Always visible regardless of pause state. Tapping opens the pause bottom sheet.

When any pause is active, the icon gets a subtle accent indicator (e.g. the icon tints to `InRange` cyan, or a small dot badge).

### Active Pause Indicator
When a pause is active, a pill appears in the BgHeader area (below IOB pill, same style). Shows category and countdown:
- "High alerts paused · 47 min" (amber tint, like TintWarning)
- "Low alerts paused · 1h 12 min" (danger tint, like TintDanger)

If both are paused, two pills. Tapping a pill opens the bottom sheet.

### Pause Bottom Sheet
Compact `ModalBottomSheet` with two rows:

**When not paused:**
```
Low alerts     [1h] [1.5h] [2h]
High alerts    [1h] [1.5h] [2h]
```
Tapping a duration chip starts the pause and dismisses the sheet.

**When paused:**
```
Low alerts     Active · 32 min left    [Cancel]
High alerts    [1h] [1.5h] [2h]
```
Cancel button clears the pause. Duration chips let you restart/extend (overwrites current expiry).

## UI — Alert Settings

At the top of AlertsSettings, before the individual alert blocks: a card showing any active pauses with cancel buttons. If no pauses active, show a "Pause alerts" button that opens the same bottom sheet (passed as a callback).

## ViewModel

New state flows in StrimmaViewModel:
- `pauseLowExpiryMs: StateFlow<Long?>` — observed from snoozePrefs
- `pauseHighExpiryMs: StateFlow<Long?>` — observed from snoozePrefs

These drive both the main screen pills and the bottom sheet state. Countdown text computed in the composable via `LaunchedEffect` polling (same pattern as `minutesAgo` in BgHeader).

## What This Does NOT Do

- No automatic pause on workout detection (future possibility, not in scope)
- No pause for stale/push fail alerts
- No custom durations beyond the three presets
- No persistent history of pauses
