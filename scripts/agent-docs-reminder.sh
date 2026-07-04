#!/usr/bin/env bash
# Remind agents about Strimma's docs gate after Kotlin source edits.

set -euo pipefail

host="claude"
if [ "${1:-}" = "--host" ]; then
  host="${2:-claude}"
fi

message="DOCS CHECK: You edited a source file that may affect user-visible behavior. Before declaring done: grep docs/ for references to this feature, update affected docs, flag stale screenshots. See AGENTS.md Documentation Updates rule."

is_kotlin_source_path() {
  printf '%s\n' "$1" | grep -Eq 'app/src/.*\.kt$'
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
  if is_kotlin_source_path "$path"; then
    printf '%s\n' "$message"
    exit 0
  fi
done <<EOF
$paths
EOF
