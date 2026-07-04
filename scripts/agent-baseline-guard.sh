#!/usr/bin/env bash
# Block edits to lint/detekt baselines. Claude can run this before edits;
# Codex currently checks the same policy at Stop by inspecting the worktree diff.

set -euo pipefail

host="claude"
if [ "${1:-}" = "--host" ]; then
  host="${2:-claude}"
fi

reason="HARD RULE: Never update lint baselines to make CI pass. Fix the actual lint errors instead."

is_baseline_path() {
  printf '%s\n' "$1" | grep -Eq '(^|/)(lint-baseline|detekt-baseline)\.xml$'
}

emit_block() {
  case "$host" in
    codex-stop)
      printf '{"continue":false,"reason":"%s"}\n' "$reason"
      ;;
    *)
      printf '{"decision":"block","reason":"%s"}\n' "$reason"
      ;;
  esac
}

emit_allow() {
  case "$host" in
    codex-stop)
      ;;
    *)
      printf '{"decision":"approve"}\n'
      ;;
  esac
}

extract_hook_paths() {
  local input
  input="$(cat || true)"
  [ -n "$input" ] || return 0

  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$input" | jq -r '
      def patch_paths:
        (.tool_input.command? // "" | split("\n")[] | capture("^\\*\\*\\* (?:Add|Update|Delete) File: (?<path>.+)$")? | .path);
      (.tool_input.file_path? // empty),
      (.tool_input.path? // empty),
      patch_paths
    ' 2>/dev/null || true
  fi
}

extract_changed_paths() {
  git diff --name-only HEAD -- 2>/dev/null || true
  git ls-files --others --exclude-standard 2>/dev/null || true
}

case "$host" in
  codex-stop)
    paths="$(extract_changed_paths)"
    ;;
  *)
    paths="$(extract_hook_paths)"
    ;;
esac

while IFS= read -r path; do
  [ -n "$path" ] || continue
  if is_baseline_path "$path"; then
    emit_block
    exit 0
  fi
done <<EOF
$paths
EOF

emit_allow
