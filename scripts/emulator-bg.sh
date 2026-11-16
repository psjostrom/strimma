#!/bin/bash
# Alert behaviour depends on BG values and elapsed time, so it cannot be checked
# on a real device without a real low. This script feeds fake readings to a
# debug build on an emulator through the xDrip broadcast path.
#
# For each value it moves the emulator clock forward 60 seconds, sends one
# xDrip broadcast stamped with the new time, and prints the Strimma alert
# channels that were posted and the `notification_alert` event-log entries.
# A `notification_alert` entry with buzz=1 or beep=1 means the alert made a
# sound. No entry, or buzz=0 and beep=0, means the alert stayed silent.
#
# Moving the clock needs `adb root`, so use a `google_apis` emulator image.
# Strimma must be set up with Data Source = xDrip Broadcast.
#
# Usage: scripts/emulator-bg.sh <label> <mg/dL>...
set -euo pipefail
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"

adb root >/dev/null
adb shell settings put global auto_time 0

label=$1
shift
for mgdl in "$@"; do
  adb shell logcat -b events -c
  now=$(( $(adb shell date +%s) + 60 ))
  adb shell date "@$now" >/dev/null
  adb shell am broadcast -a com.eveningoutpost.dexdrip.BgEstimate \
    --ed com.eveningoutpost.dexdrip.Extras.BgEstimate "$mgdl" \
    --el com.eveningoutpost.dexdrip.Extras.Time "${now}000" >/dev/null
  sleep 3
  events=$(adb shell logcat -b events -d)
  posted=$(echo "$events" | grep notification_enqueue | grep -oE 'channel=strimma_alert_[a-z_]+' \
    | sed 's/channel=strimma_alert_//' | tr '\n' ' ' || true)
  # Fields after the key: buzz, beep, blink, then platform-specific extras.
  alerts=$(echo "$events" | grep notification_alert | grep strimma \
    | sed -E 's/.*\|[0-9]+\|null\|[0-9]+,//; s/\]$//' | tr '\n' ' ' || true)
  printf '%s %s  %s mg/dL  posted:[%s]  buzz,beep,blink:[%s]\n' \
    "$label" "$(date -r "$now" +%H:%M)" "$mgdl" "$posted" "$alerts"
done
