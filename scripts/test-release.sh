#!/usr/bin/env bash
# Tests for scripts/release.sh — version bumping, RC logic, and changelog generation.

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
release_sh="$repo_root/scripts/release.sh"
# Copy release.sh into temp repo so REPO_ROOT resolves to the temp dir
# (release.sh computes REPO_ROOT from its own location via BASH_SOURCE)

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_eq() {
  local expected="$1" actual="$2" label="$3"
  if [[ "$expected" != "$actual" ]]; then
    fail "$label: expected '$expected', got '$actual'"
  fi
}

assert_contains() {
  local haystack="$1" needle="$2" label="$3"
  if ! printf '%s' "$haystack" | grep -Fq "$needle"; then
    fail "$label: expected to find '$needle'"
  fi
}

assert_exit_nonzero() {
  local label="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    fail "$label: expected non-zero exit, but got 0"
  fi
}

# --- Setup: temp repo with controlled build.gradle.kts ---

tmp_repo="$(mktemp -d)"
trap 'rm -rf "$tmp_repo"' EXIT

# Copy release.sh into temp repo scripts/ dir so REPO_ROOT resolves correctly
# (release.sh computes REPO_ROOT from its own BASH_SOURCE location)
mkdir -p "$tmp_repo/scripts"
cp "$release_sh" "$tmp_repo/scripts/release.sh"
chmod +x "$tmp_repo/scripts/release.sh"
release_sh="$tmp_repo/scripts/release.sh"

git -C "$tmp_repo" init -q
git -C "$tmp_repo" config user.name "Test"
git -C "$tmp_repo" config user.email "test@example.invalid"

mkdir -p "$tmp_repo/app"
cat > "$tmp_repo/app/build.gradle.kts" <<'GRADLE'
android {
    defaultConfig {
        versionCode = 1
        versionName = "1.3.0"
    }
}
GRADLE

cat > "$tmp_repo/CHANGELOG.md" <<'CL'
# Changelog

## Previous
- Old entry
CL

git -C "$tmp_repo" add -A
git -C "$tmp_repo" commit -qm "initial"

# --- Test 1: Explicit version ---

output="$(cd "$tmp_repo" && bash "$release_sh" --prepare --version 1.4.0 2>&1)"
assert_contains "$output" "Target version:  1.4.0" "explicit version target"
assert_contains "$output" "Branch:          release/1.4.0" "explicit version branch"

# Verify build.gradle.kts was updated
current_ver="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$tmp_repo/app/build.gradle.kts")"
assert_eq "1.4.0" "$current_ver" "build.gradle.kts versionName"

# Verify versionCode untouched
version_code="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$tmp_repo/app/build.gradle.kts")"
assert_eq "1" "$version_code" "versionCode unchanged"

# Verify CHANGELOG.md prepended
changelog_head="$(head -1 "$tmp_repo/CHANGELOG.md")"
assert_contains "$changelog_head" "1.4.0" "changelog prepended"

# --- Test 2: Auto-bump patch ---

# Reset to 1.3.0
cat > "$tmp_repo/app/build.gradle.kts" <<'GRADLE'
android {
    defaultConfig {
        versionCode = 1
        versionName = "1.3.0"
    }
}
GRADLE

output="$(cd "$tmp_repo" && bash "$release_sh" --prepare --bump patch 2>&1)"
assert_contains "$output" "Target version:  1.3.1" "bump patch"

# --- Test 3: Auto-bump minor ---

cat > "$tmp_repo/app/build.gradle.kts" <<'GRADLE'
android {
    defaultConfig {
        versionCode = 1
        versionName = "1.3.5"
    }
}
GRADLE

output="$(cd "$tmp_repo" && bash "$release_sh" --prepare --bump minor 2>&1)"
assert_contains "$output" "Target version:  1.4.0" "bump minor"

# --- Test 4: Auto-bump major ---

cat > "$tmp_repo/app/build.gradle.kts" <<'GRADLE'
android {
    defaultConfig {
        versionCode = 1
        versionName = "1.3.5"
    }
}
GRADLE

output="$(cd "$tmp_repo" && bash "$release_sh" --prepare --bump major 2>&1)"
assert_contains "$output" "Target version:  2.0.0" "bump major"

# --- Test 5: RC from clean version ---

cat > "$tmp_repo/app/build.gradle.kts" <<'GRADLE'
android {
    defaultConfig {
        versionCode = 1
        versionName = "1.3.0"
    }
}
GRADLE

output="$(cd "$tmp_repo" && bash "$release_sh" --prepare --version 1.4.0 --rc 2>&1)"
assert_contains "$output" "Target version:  1.4.0-rc.1" "RC from clean version"

# --- Test 6: RC increment from existing RC ---

cat > "$tmp_repo/app/build.gradle.kts" <<'GRADLE'
android {
    defaultConfig {
        versionCode = 1
        versionName = "1.4.0-rc.2"
    }
}
GRADLE

output="$(cd "$tmp_repo" && bash "$release_sh" --prepare --version 1.4.0 --rc 2>&1)"
assert_contains "$output" "Target version:  1.4.0-rc.3" "RC increment"

# --- Test 7: Bump from RC drops to stable ---

cat > "$tmp_repo/app/build.gradle.kts" <<'GRADLE'
android {
    defaultConfig {
        versionCode = 1
        versionName = "1.4.0-rc.1"
    }
}
GRADLE

output="$(cd "$tmp_repo" && bash "$release_sh" --prepare --bump patch 2>&1)"
assert_contains "$output" "Target version:  1.4.0" "bump patch from RC drops suffix"

# --- Test 8: RC of already-released version fails ---

cat > "$tmp_repo/app/build.gradle.kts" <<'GRADLE'
android {
    defaultConfig {
        versionCode = 1
        versionName = "1.3.0"
    }
}
GRADLE

git -C "$tmp_repo" tag v1.4.0

assert_exit_nonzero "RC of released version" \
  bash "$tmp_repo/scripts/release.sh" --prepare --version 1.4.0 --rc

# Clean up tag
git -C "$tmp_repo" tag -d v1.4.0

# --- Test 9: Invalid version format fails ---

cat > "$tmp_repo/app/build.gradle.kts" <<'GRADLE'
android {
    defaultConfig {
        versionCode = 1
        versionName = "1.3.0"
    }
}
GRADLE

assert_exit_nonzero "invalid version" \
  bash "$tmp_repo/scripts/release.sh" --prepare --version "not-a-version"

# --- Test 10: Both --version and --bump fails ---

assert_exit_nonzero "both flags" \
  bash "$tmp_repo/scripts/release.sh" --prepare --version 1.4.0 --bump patch

# --- Test 11: Raw changelog fallback (no GITHUB_TOKEN) ---

cat > "$tmp_repo/app/build.gradle.kts" <<'GRADLE'
android {
    defaultConfig {
        versionCode = 1
        versionName = "1.3.0"
    }
}
GRADLE

# Reset CHANGELOG.md
echo "# Changelog" > "$tmp_repo/CHANGELOG.md"

output="$(cd "$tmp_repo" && unset GITHUB_TOKEN; bash "$release_sh" --prepare --version 1.4.0 2>&1)"
changelog_content="$(cat "$tmp_repo/CHANGELOG.md")"
assert_contains "$changelog_content" "1.4.0" "changelog contains version"

echo "Release script tests passed."
