#!/bin/bash
# Alert behaviour depends on BG values and elapsed time, so it cannot be checked
# on a real device without a real low. This script feeds fake readings to a
# debug build on an emulator through the xDrip broadcast path.
#
# For each value it moves the emulator clock forward 60 seconds, sends one
# xDrip broadcast stamped with the new time, and waits until Strimma has
# posted the glucose notification for that reading. It then prints each
# Strimma alert that was posted, with the buzz, beep and blink flags from the
# `notification_alert` event log. An alert with buzz=1 or beep=1 made a sound.
# An alert with buzz=0 and beep=0, or with no event-log entry, stayed silent.
#
# Moving the clock needs `adb root`, so use a `google_apis` emulator image.
# Strimma must be set up with Data Source = xDrip Broadcast. The clock stays
# ahead after a run, so consecutive runs continue one timeline.
#
# Usage: scripts/emulator-bg.sh <label> <mg/dL>...
set -euo pipefail
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"

if [ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" != 1 ]; then
  echo "$ANDROID_SERIAL is not an emulator. This script rewrites the device clock." >&2
  exit 1
fi
adb root >/dev/null
adb wait-for-device
adb shell settings put global auto_time 0

label=$1
shift
for mgdl in "$@"; do
  now=$(( $(adb shell date +%s | tr -d '\r') + 60 ))
  adb shell date "@$now" >/dev/null
  adb shell am broadcast -a com.eveningoutpost.dexdrip.BgEstimate \
    --ed com.eveningoutpost.dexdrip.Extras.BgEstimate "$mgdl" \
    --el com.eveningoutpost.dexdrip.Extras.Time "${now}000" >/dev/null
  # Every stored reading reposts the glucose notification right away. Strimma
  # evaluates alerts ReadingFanOut.DEBOUNCE_MS (1.5 s) later, and a newer
  # reading inside that window replaces the pending one. The script waits for
  # the repost and then 2.5 s more, so every value gets its own alert check.
  for _ in $(seq 20); do
    events=$(adb shell logcat -b events -d -T "$now.000")
    echo "$events" | grep -q 'channel=strimma_glucose' && break
    sleep 0.5
  done
  sleep 2.5
  events=$(adb shell logcat -b events -d -T "$now.000")
  clock=$(adb shell date +%H:%M | tr -d '\r')
  printf '%s %s  %s mg/dL ' "$label" "$clock" "$mgdl"
  echo "$events" | python3 -c '
import re, sys
log = sys.stdin.read()
# notification_enqueue: [uid,pid,pkg,id,tag,...,Notification(channel=<id> ...
channels = dict(re.findall(r"notification_enqueue: \[\d+,\d+,com\.psjostrom\.strimma[.\w]*,(\d+),.*?channel=strimma_alert_(\w+)", log))
# notification_alert: [<user>|<pkg>|<id>|<tag>|<uid>,buzz,beep,blink,...]
flags = dict(re.findall(r"notification_alert: \[\d+\|com\.psjostrom\.strimma[.\w]*\|(\d+)\|[^,]*,(\d,\d,\d)", log))
out = [name + "(buzz,beep,blink=" + flags.get(i, "none") + ")" for i, name in channels.items()]
print(" ".join(out) or "no alert")
'
done
