# Night alerts

## Intent

High and High Soon alerts wake the user at night for highs that a closed loop such as CamAPS FX corrects by itself. The user wants a different set of alerts at night, chosen per alert, without any action each evening. Night alerts are a third alert set that replaces the regular set during a fixed daily time window.

## Goals

- The user can turn each alert on or off for the night and set its threshold, exactly as for the regular set.
- The night set switches on and off by the clock, with no daily action.
- The Alerts screen shows which set is active right now.
- The Alerts screen gets shorter, not longer.

## Non-goals

- A different window per weekday. One window applies every day.
- Night-specific sounds, snooze duration or cooldown. These stay shared by all sets.
- A night badge on the main screen.
- A night graph band. The graph band stays the regular `bgLow`/`bgHigh` at night.

## Anti-goals

- Turning on night alerts must not silently change any alert. A new night set starts as a copy of the regular set.
- A setting shown under a tab must never change another tab's set.

## Behaviour

There are three alert sets. The code calls the regular set `regularAlertProtocol`, and the UI calls the regular set "Day". Every set is an `AlertProtocol`.

The active set follows this order of precedence:

1. If workout mode is on, the exercise set is active.
2. Otherwise, if night alerts are enabled and the local time is inside the night window, the night set is active.
3. Otherwise, the Day set is active.

`WorkoutModeManager.resolve` is the one place that resolves the active set. The `effectiveThresholds` StateFlow and the `currentEffectiveThresholds()` read both call `resolve`. Every live consumer reads one of the two: `AlertManager`, the widget, the local web server and the foreground notification.

The night window has a start time and an end time in the phone's local time. The defaults are 23:00 and 07:00. The window may cross midnight. The window includes the start minute and excludes the end minute. The settings screen rejects a window whose start equals its end. The window compares wall-clock `LocalTime` values, so a daylight-saving change needs no extra handling.

`currentEffectiveThresholds()` resolves the active set again for the current time on every call. The 30-second ticker cannot be trusted at the window boundary, because its delay stops while the phone is in deep sleep. `AlertManager` checks every new reading against the set for the current time. That check already cancels a showing alert when the active set no longer raises that alert. Switching sets needs no new state. The `effectiveThresholds` StateFlow follows the 30-second ticker and serves the display consumers.

An Urgent Low, Low, High or Urgent High alert raised while the night set is active gets the title prefix "Night · ". This matches `workoutPrefixedTitle`, which adds "Workout · " to the same four alerts and leaves the Soon and Stale titles without a prefix. (Builder's call.)

Night alerts are disabled by default. The night set has no stored values until the user enables night alerts for the first time. On that first enable, the app copies the Day set into the night set keys. After the copy, the night set and the Day set are independent.

## Alerts screen

The screen keeps every setting on one page. Top to bottom it shows these sections:

1. **Alert sets.** A segmented control with Day, Night and Exercise sits above one card. The card shows the selected set's seven alert rows, using the existing `AlertProtocolRows`. A dot on a tab marks the set that is active right now. The screen opens on the active set's tab.
   - The Day tab shows the Day set.
   - The Night tab shows a "Use night alerts" switch and the From and To times above the seven rows. While the switch is off, the rows are hidden.
   - The Exercise tab shows the existing line "Used while Workout Mode is active. Low and High also define the exercise graph range." above the seven rows.
2. **Sounds.** This section has one row per alert channel: Urgent Low, Low, High, Urgent High, Low Soon, High Soon and Stale Data. Each row opens the Android channel settings through the existing `onOpenAlertSound(channelId)` callback. The rows show no sound name. The current per-alert "Sound" links inside the Day rows are removed.
3. **Alert Behavior.** Snooze duration and cooldown stay unchanged.
4. **Pattern Insights.** The recurring-patterns switch stays unchanged.

Alert sets comes first, because it is the main control on the screen. Everything inside the Alert sets card belongs to the selected tab. The sections after that card are separate cards, and they are shared by all three sets.

The tab control is a Material 3 `SingleChoiceSegmentedButtonRow`, the same component as the snooze and cooldown controls. The active-set dot is a small `InRange` circle after the tab label. The From and To times are two outlined fields side by side, and each field opens a Material 3 time picker.

## Settings export and import

`SettingsRepository.exportToJson` lists every key by hand. The night switch, the two window times and all eleven night `AlertProtocol` fields are added to the export and the import. The eleven fields are seven switches and four thresholds.

`importFromJson` overwrites only the keys present in the file, as it does today for every other key. An older export without night keys leaves the current night settings unchanged. An import that contains night alert fields marks the night set as already copied, so a later first enable does not overwrite the imported night set with the Day set. (Builder's call.)

## Documentation

- `docs/guide/alerts.md` describes the three sets, the precedence order and the new screen layout.
- `docs/guide/workout-mode.md` states that workout mode takes precedence over night alerts.
- Screenshots of the Alerts screen in `docs/` are flagged as stale.
- All new strings are added in English, Swedish, Spanish, French and German.

## Approaches considered

**Placement of the night set on the Alerts screen.**

- A third inline section, following the Exercise Alerts pattern, would grow the screen from about 4½ to about 6½ phone screens.
- Day, Night and Exercise tabs edit one set at a time and shrink the screen to about 3 phone screens. **Chosen.** The tabs also show which set is active.
- A single row opening a separate night screen would be the smallest change, but it hides the night settings one level deeper.

**Placement of the settings shared by all sets.**

- **Shared settings as separate cards after the Alert sets card. Chosen.** Everything stays on one page, and the alert sets come first.
- A "Sounds and behavior" row opening a separate screen would keep the tabs at the top of the Alerts screen.
- Shared settings above the tabs keep them out of the tabbed card, but they push the alert sets about 1½ screens down.
- Shared settings inside the tabbed card were rejected. A shared setting shown under every tab looks like a per-tab setting.

**Night window.**

- One window for every day. **Chosen.** Separate weekday and weekend windows can come later.

## Testing

- `effectiveThresholds` selection against real `SettingsRepository` and a fake `Clock`. The cases are inside the window, outside it, across midnight, at both boundary minutes, night disabled, and workout mode during the night window.
- `AlertManager` at 02:00 with High off in the night set raises no High alert, and the same reading at 14:00 raises the High alert.
- The first enable copies the Day set, and a later Day edit does not change the night set.
- An export and import round trip keeps the night settings.
- An import of a file without night keys leaves the current night settings unchanged.
- An emulator end-to-end check with the `emulator-e2e` skill.

## Left to the builder

- Naming of the new `SettingsRepository` keys and of the type that names the active set.
- How `EffectiveThresholds` exposes the active set to the Alerts screen. `StatusRoute` keeps its `workoutModeOn` field unchanged, because watchfaces read that JSON.
- The time picker component, using Material 3.
