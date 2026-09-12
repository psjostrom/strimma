#!/usr/bin/env bash

set -euo pipefail

variant="${1:-debug}"
case "$variant" in
  debug)
    gradle_task=":app:assembleDebug"
    apk_rel_path="app/build/outputs/apk/debug/app-debug.apk"
    install_flags=(-r)
    component="com.psjostrom.strimma.debug/com.psjostrom.strimma.ui.MainActivity"
    ;;
  release)
    gradle_task=":app:assembleRelease"
    apk_rel_path="app/build/outputs/apk/release/app-release.apk"
    install_flags=(-r -d)
    component="com.psjostrom.strimma/com.psjostrom.strimma.ui.MainActivity"
    ;;
  *)
    echo "Usage: $0 [debug|release]" >&2
    exit 1
    ;;
esac

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

if [[ -z "${selected_serial:-}" ]]; then
  echo "No device selected." >&2
  exit 1
fi

apk_path="$repo_root/$apk_rel_path"

if [[ "$variant" == "release" ]]; then
  rm -f "$apk_path"
fi

echo "==> Building $variant APK..."
"$repo_root/gradlew" "$gradle_task"

if [[ "$variant" == "release" && ! -f "$apk_path" ]]; then
  unsigned_apk="$repo_root/app/build/outputs/apk/release/app-release-unsigned.apk"
  if [[ -f "$unsigned_apk" ]]; then
    echo "Error: Release build generated an unsigned APK. Set STRIMMA_KEYSTORE_FILE and signing credentials to produce an installable release APK." >&2
  else
    echo "Error: Release APK not found at $apk_path" >&2
  fi
  exit 1
fi

echo "==> Installing $variant APK to $selected_serial..."
adb -s "$selected_serial" install "${install_flags[@]}" "$apk_path"

echo "==> Launching $variant app..."
launch_output="$(adb -s "$selected_serial" shell am start -W -n "$component" 2>&1)"
echo "$launch_output"
if printf '%s' "$launch_output" | grep -Eqi "(^Error:|Status: error)"; then
  echo "Error: Failed to launch $variant app on $selected_serial." >&2
  exit 1
fi

echo "==> Done."
