---
name: emulator-e2e
description: Use when checking a Strimma behaviour change end to end on an Android emulator, before a PR opens or during a release. Covers feeding fake BG readings, alerts that need a low, cooldowns, and driving the UI.
---

# Emulator end-to-end

Every behaviour change is checked on an emulator before the PR opens. That
includes alerts, which need a low. Never leave a check for a real phone when the
emulator can run it.

## Setup

- Use a `google_apis` emulator image, because `scripts/emulator-bg.sh` needs
  `adb root` to move the clock. The script refuses to run on a real phone.
- Install the debug build. In the setup wizard, choose Data Source =
  **xDrip Broadcast**. Set any thresholds and cooldowns the check needs in
  Settings → Alerts.

## Feeding readings

- `scripts/emulator-bg.sh <label> <mg/dL>...` sends one reading per value, with
  the emulator clock moved 60 seconds forward each time. It prints each alert
  that was posted and whether it made a sound (`notification_alert` buzz/beep).
- An alert that fires on the first value of a 15-minute cooldown can fire again
  on the 16th value.
- Low Soon and High Soon need a trend. Send a steady fall or rise of about
  9 mg/dL per reading until the prediction fires.
- The clock stays ahead after a run, and Strimma rejects a reading older than
  the newest stored one. Keep automatic time off for the whole session. If the
  clock falls back, run `adb shell pm clear com.psjostrom.strimma.debug` and set
  Strimma up again.

## Driving the UI

Use `adb shell uiautomator dump` and `adb shell input tap`. Argent `describe`
returns a stale tree for Strimma's Compose screens.
