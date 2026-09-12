#!/usr/bin/env bash
# Tests for scripts/install-release.sh — device selection, build target, and error handling.

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
install_release="$repo_root/scripts/install-release.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1" needle="$2" label="$3"
  if ! printf '%s' "$haystack" | grep -Fq -- "$needle"; then
    fail "$label: expected to find '$needle'"
  fi
}

assert_not_contains() {
  local haystack="$1" needle="$2" label="$3"
  if printf '%s' "$haystack" | grep -Fq -- "$needle"; then
    fail "$label: did not expect to find '$needle'"
  fi
}

[[ -x "$install_release" ]] || fail "install-release.sh is missing or not executable"

tmp_repo="$(mktemp -d)"
fake_bin="$(mktemp -d)"
script_repo="$(mktemp -d)"
target_repo="$(mktemp -d)"
trap 'rm -rf "$tmp_repo" "$fake_bin" "$script_repo" "$target_repo"' EXIT

mkdir -p "$tmp_repo/scripts" "$tmp_repo/app/build/outputs/apk/release"
cp "$install_release" "$tmp_repo/scripts/install-release.sh"
chmod +x "$tmp_repo/scripts/install-release.sh"

cat > "$tmp_repo/gradlew" <<'FAKE_GRADLE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${GRADLE_LOG:?}"
mkdir -p "$FAKE_REPO/app/build/outputs/apk/release"
: > "$FAKE_REPO/app/build/outputs/apk/release/app-release.apk"
FAKE_GRADLE
chmod +x "$tmp_repo/gradlew"

cat > "$fake_bin/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1" == "devices" ]]; then
  printf 'List of devices attached\n'
  if [[ "${ADB_MODE:-two}" != "empty" ]]; then
    printf 'phone-123\tdevice product:caiman model:Pixel_9_Pro device:caiman\n'
    printf 'emulator-5554\tdevice product:sdk model:sdk_gphone device:emu64\n'
  fi
  exit 0
fi

if [[ "$1" == "-s" && "$3" == "install" ]]; then
  printf 'install:%s:%s\n' "$2" "$*" >> "${ADB_LOG:?}"
  exit 0
fi

if [[ "$1" == "-s" && "$3" == "shell" ]]; then
  printf 'launch:%s:%s\n' "$2" "$*" >> "${ADB_LOG:?}"
  exit 0
fi

echo "unexpected adb invocation: $*" >&2
exit 1
FAKE_ADB
chmod +x "$fake_bin/adb"

empty_output="$({
  set +e
  (cd "$tmp_repo" && env ADB_MODE=empty PATH="$fake_bin:$PATH" "$tmp_repo/scripts/install-release.sh") 2>&1
  status=$?
  printf '\n__STATUS__=%s\n' "$status"
})"
assert_contains "$empty_output" "No authorized Android devices found." "no-device error"
assert_contains "$empty_output" "__STATUS__=1" "no-device exit status"

adb_log="$tmp_repo/adb.log"
gradle_log="$tmp_repo/gradle.log"
picker_output="$(printf '1\n' | (cd "$tmp_repo" && env ADB_LOG="$adb_log" GRADLE_LOG="$gradle_log" FAKE_REPO="$tmp_repo" PATH="$fake_bin:$PATH" "$tmp_repo/scripts/install-release.sh") 2>&1)"
assert_contains "$picker_output" "Pixel_9_Pro" "device picker label"
assert_contains "$picker_output" "==> Building release APK..." "building banner"
assert_contains "$picker_output" "==> Installing release APK to phone-123..." "install banner"
assert_contains "$picker_output" "==> Launching release app..." "launch banner"
assert_contains "$picker_output" "==> Done." "done banner"
assert_contains "$(cat "$adb_log")" "install:phone-123" "selected device install"
assert_contains "$(cat "$adb_log")" "-r -d" "reinstall and downgrade flags"
assert_contains "$(cat "$adb_log")" "app-release.apk" "release apk artifact"
assert_contains "$(cat "$adb_log")" "launch:phone-123" "selected device launch"
assert_contains "$(cat "$adb_log")" "com.psjostrom.strimma/com.psjostrom.strimma.ui.MainActivity" "launcher component"
assert_not_contains "$(cat "$adb_log")" "emulator-5554" "unselected device"
assert_contains "$(cat "$gradle_log")" ":app:assembleRelease" "release build"

mkdir -p "$script_repo/scripts" "$target_repo/app/build/outputs/apk/release"
cp "$install_release" "$script_repo/scripts/install-release.sh"
chmod +x "$script_repo/scripts/install-release.sh"
cp "$tmp_repo/gradlew" "$target_repo/gradlew"
chmod +x "$target_repo/gradlew"
git -C "$target_repo" init -q

worktree_adb_log="$target_repo/adb.log"
worktree_gradle_log="$target_repo/gradle.log"
worktree_output="$({
  set +e
  printf '1\n' | (cd "$target_repo" && env ADB_LOG="$worktree_adb_log" GRADLE_LOG="$worktree_gradle_log" FAKE_REPO="$target_repo" PATH="$fake_bin:$PATH" "$script_repo/scripts/install-release.sh") 2>&1
  status=$?
  printf '\n__STATUS__=%s\n' "$status"
})"
assert_contains "$worktree_output" "__STATUS__=0" "current worktree invocation"
assert_contains "$(cat "$worktree_gradle_log")" ":app:assembleRelease" "current worktree build"
assert_contains "$(cat "$worktree_adb_log")" "install:phone-123" "current worktree install"

echo "install-release tests passed."
