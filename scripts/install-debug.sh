#!/usr/bin/env bash

set -euo pipefail

if repo_root="$(git rev-parse --show-toplevel 2>/dev/null)"; then
  :
else
  repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found; install Android platform-tools first." >&2
  exit 1
fi

device_serials=()
device_labels=()
while IFS=$'\t' read -r serial model; do
  [[ -n "$serial" ]] || continue
  device_serials+=("$serial")
  if [[ -n "$model" ]]; then
    device_labels+=("$serial ($model)")
  else
    device_labels+=("$serial")
  fi
done < <(
  adb devices -l | awk '
    $2 == "device" {
      model = ""
      for (i = 1; i <= NF; i++) {
        if ($i ~ /^model:/) {
          model = $i
          sub(/^model:/, "", model)
        }
      }
      print $1 "\t" model
    }
  '
)

if ((${#device_serials[@]} == 0)); then
  echo "No authorized Android devices found. Connect a phone with adb and retry." >&2
  exit 1
fi

if ((${#device_serials[@]} == 1)); then
  selected_serial="${device_serials[0]}"
  printf 'Using %s\n' "${device_labels[0]}"
else
  echo "Select Android device:"
  PS3="Device: "
  select selection in "${device_labels[@]}"; do
    if [[ -n "$selection" ]]; then
      selected_serial="${device_serials[$((REPLY - 1))]}"
      break
    fi
    echo "Invalid selection."
  done
fi

"$repo_root/gradlew" :app:assembleDebug
adb -s "$selected_serial" install -r "$repo_root/app/build/outputs/apk/debug/app-debug.apk"
adb -s "$selected_serial" shell am start -n com.psjostrom.strimma.debug/com.psjostrom.strimma.ui.MainActivity
