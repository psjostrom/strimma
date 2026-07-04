#!/usr/bin/env bash
# Cheap CI coverage for repository agent and git hook policy.

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  local label="$3"
  if ! printf '%s' "$haystack" | grep -Fq "$needle"; then
    fail "$label: expected to find '$needle' in '$haystack'"
  fi
}

baseline_payload='{"tool_input":{"file_path":"config/detekt/detekt-baseline.xml"}}'
baseline_result="$(printf '%s' "$baseline_payload" | "$repo_root/scripts/agent-baseline-guard.sh" --host claude)"
assert_contains "$baseline_result" '"decision":"block"' "Claude baseline guard"

patch_payload='{"tool_input":{"command":"*** Begin Patch\n*** Update File: config/detekt/detekt-baseline.xml\n*** End Patch"}}'
patch_result="$(printf '%s' "$patch_payload" | "$repo_root/scripts/agent-baseline-guard.sh" --host claude)"
assert_contains "$patch_result" '"decision":"block"' "Patch baseline guard"

tmp_repo="$(mktemp -d)"
trap 'rm -rf "$tmp_repo"' EXIT

git -C "$tmp_repo" init -q
git -C "$tmp_repo" config user.name "Hook Test"
git -C "$tmp_repo" config user.email "hook-test@example.invalid"
mkdir -p "$tmp_repo/config/detekt"
printf '<baseline />\n' > "$tmp_repo/config/detekt/detekt-baseline.xml"
git -C "$tmp_repo" add config/detekt/detekt-baseline.xml
git -C "$tmp_repo" commit -qm "initial"
printf '<changed />\n' > "$tmp_repo/config/detekt/detekt-baseline.xml"

codex_stop_result="$(cd "$tmp_repo" && "$repo_root/scripts/agent-baseline-guard.sh" --host codex-stop)"
assert_contains "$codex_stop_result" '"continue":false' "Codex stop baseline guard"

docs_payload='{"tool_input":{"file_path":"app/src/main/java/com/psjostrom/strimma/ui/MainScreen.kt"}}'
docs_result="$(printf '%s' "$docs_payload" | "$repo_root/scripts/agent-docs-reminder.sh" --host claude)"
assert_contains "$docs_result" 'DOCS CHECK:' "Claude docs reminder"

fake_bin="$(mktemp -d)"
cat > "$fake_bin/git" <<'FAKE_GIT'
#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  rev-list)
    printf '%s\n' abc123
    ;;
  verify-commit)
    if [ "${VERIFY_OK:-0}" = "1" ]; then
      exit 0
    fi
    exit 1
    ;;
  *)
    command git "$@"
    ;;
esac
FAKE_GIT
chmod +x "$fake_bin/git"

zero_sha="0000000000000000000000000000000000000000"
delete_input="refs/heads/main $zero_sha refs/heads/main abc123"
printf '%s\n' "$delete_input" | env PATH="$fake_bin:$PATH" "$repo_root/.githooks/pre-push"

push_input="refs/heads/main def456 refs/heads/main abc123"
if printf '%s\n' "$push_input" | env PATH="$fake_bin:$PATH" "$repo_root/.githooks/pre-push" >/tmp/pre-push-reject.out 2>&1; then
  fail "pre-push should reject unverifiable commits"
fi
assert_contains "$(cat /tmp/pre-push-reject.out)" "Rejecting push" "pre-push rejection message"

printf '%s\n' "$push_input" | env VERIFY_OK=1 PATH="$fake_bin:$PATH" "$repo_root/.githooks/pre-push"

echo "Agent workflow hook tests passed."
