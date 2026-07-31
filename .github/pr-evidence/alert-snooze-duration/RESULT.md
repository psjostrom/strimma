# QA: alert-snooze-duration

**Status:** `verified`  
**Device:** Android emulator `emulator-5554` (AVD `Pixel_10_Pro`, API 37)  
**Build:** `com.psjostrom.strimma.debug` from branch `feat/alert-snooze-duration` (`dea35f0`)  
**Date:** 2026-07-31

## Flow

1. Installed debug APK on emulator; completed setup wizard (notification listener already allowed via `cmd notification allow_listener`).
2. Opened **Settings → Alerts**, scrolled to **Alert Snooze Duration**.
3. Confirmed picker options: `15m`, `30m`, `1h`, `2h`, `3h` (default `30m`).
4. Selected **1h**; UI showed checkmark on `1h`.
5. Confirmed persistence: DataStore `settings.preferences_pb` contains `alert_snooze_duration` = `H1`.

## Evidence

| Artifact | Notes |
|---|---|
| `snooze-1h-selected.png` | Alerts screen; `1h` selected with checkmark |
| `snooze-row.png` / `ui-snooze-row.xml` | Picker + description visible |
| `settings.preferences_pb` | `alert_snooze_duration` → `H1` |
| `alerts-scrolled.png` | Row discovered after scroll |

## Notes

- Physical Pixel 9 Pro was not used (lock screen / unattended QA).
- Argent `describe` returned a stale Compose tree; navigation used `adb` + `uiautomator dump` + screenshots.
- Did not fire a live alert notification to exercise the Snooze action label end-to-end (settings persistence + picker UI covered as the planned UI gate).
