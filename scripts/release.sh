#!/usr/bin/env bash
set -euo pipefail

# Strimma release script.
# Bumps versionName, generates changelog via AI, updates CHANGELOG.md.
# Works locally and in CI (--prepare mode).
#
# Usage:
#   scripts/release.sh --version 1.4.0
#   scripts/release.sh --version 1.4.0 --rc          # → 1.4.0-rc.1
#   scripts/release.sh --bump patch
#   scripts/release.sh --bump minor --rc             # → X.Y.0-rc.1
#   scripts/release.sh --prepare --bump patch        # CI mode

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GRADLE_FILE="$REPO_ROOT/app/build.gradle.kts"
CHANGELOG_FILE="$REPO_ROOT/CHANGELOG.md"
MODEL="${STRIMMA_RELEASE_MODEL:-openai/gpt-4o}"

usage() {
  cat <<EOF
Usage:
  scripts/release.sh --version <x.y.z>          Explicit version
  scripts/release.sh --version <x.y.z> --rc     Explicit version as RC (appends -rc.N)
  scripts/release.sh --bump <patch|minor|major> Auto-bump from current versionName
  scripts/release.sh --bump minor --rc          Bump + mark as RC

Options:
  --rc         Mark as release candidate. Appends -rc.1 (or increments if already RC).
  --prepare    CI mode: skip git ops, write outputs to \$GITHUB_OUTPUT
  --help       Show this help
EOF
}

# --- Arg parsing ---

VERSION=""
BUMP=""
PREPARE=false
RC=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --bump) BUMP="$2"; shift 2 ;;
    --rc) RC=true; shift ;;
    --prepare) PREPARE=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "$VERSION" && -z "$BUMP" ]]; then
  echo "Error: provide --version or --bump" >&2
  usage >&2
  exit 1
fi

if [[ -n "$VERSION" && -n "$BUMP" ]]; then
  echo "Error: use either --version or --bump, not both" >&2
  exit 1
fi

# --- Version resolution ---

current_version() {
  sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$GRADLE_FILE"
}

is_valid_version() {
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$ ]]
}

bump_version() {
  local current="$1" kind="$2"
  local major minor patch
  IFS='.' read -r major minor patch_raw <<< "$current"
  patch="${patch_raw%%-*}"  # strip pre-release suffix
  if [[ "$patch_raw" == *-* && "$kind" == "patch" ]]; then
    echo "${major}.${minor}.${patch}"
    return
  fi
  case "$kind" in
    major) echo "$((major + 1)).0.0" ;;
    minor) echo "${major}.$((minor + 1)).0" ;;
    patch) echo "${major}.${minor}.$((patch + 1))" ;;
    *) echo "Invalid bump kind: $kind" >&2; exit 1 ;;
  esac
}

if [[ -n "$VERSION" ]]; then
  if ! is_valid_version "$VERSION"; then
    echo "Error: invalid version format: $VERSION" >&2
    exit 1
  fi
  TARGET_VERSION="$VERSION"
else
  CURRENT="$(current_version)"
  TARGET_VERSION="$(bump_version "$CURRENT" "$BUMP")"
fi

# Apply RC suffix if requested
if [[ "$RC" == true ]]; then
  RC_BASE="${TARGET_VERSION%%-rc.*}"
  if git -C "$REPO_ROOT" rev-parse -q --verify "refs/tags/v${RC_BASE}" > /dev/null; then
    echo "Error: ${RC_BASE} has already been released (tag v${RC_BASE} exists)." >&2
    exit 1
  fi

  if [[ "$TARGET_VERSION" =~ -rc\.([0-9]+)$ ]]; then
    # Already has RC suffix — increment
    CURRENT_RC="${BASH_REMATCH[1]}"
    NEXT_RC=$((CURRENT_RC + 1))
    TARGET_VERSION="${TARGET_VERSION%-rc.*}-rc.${NEXT_RC}"
  else
    # No RC suffix — check if current is already a stable release of this version
    CURRENT="$(current_version)"
    CURRENT_BASE="${CURRENT%%-*}"  # strip any existing pre-release suffix
    if [[ "$TARGET_VERSION" == "$CURRENT_BASE" && ! "$CURRENT" == *-* ]]; then
      echo "Error: ${TARGET_VERSION} is already the current stable version." >&2
      echo "Use --bump to target the next version, or specify a higher version." >&2
      exit 1
    fi
    # Check if current is an RC of this same base — increment instead of creating -rc.1
    if [[ "$CURRENT" =~ ^${TARGET_VERSION}-rc\.([0-9]+)$ ]]; then
      CURRENT_RC="${BASH_REMATCH[1]}"
      NEXT_RC=$((CURRENT_RC + 1))
      TARGET_VERSION="${TARGET_VERSION}-rc.${NEXT_RC}"
    else
      TARGET_VERSION="${TARGET_VERSION}-rc.1"
    fi
  fi
fi

BRANCH="release/${TARGET_VERSION}"
TITLE="chore(release): bump versionName to ${TARGET_VERSION}"

echo "Current version: $(current_version)"
echo "Target version:  $TARGET_VERSION"
echo "Branch:          $BRANCH"

# --- Find previous release tag ---

PREV_TAG="$(git -C "$REPO_ROOT" tag --sort=-v:refname | grep '^v' | grep -v '\-rc\.' | head -1 || true)"
if [[ -n "$PREV_TAG" ]]; then
  echo "Previous tag: $PREV_TAG"
else
  echo "Previous tag: none (first release)"
fi

# --- Collect commits ---

if [[ -n "$PREV_TAG" ]]; then
  COMMITS="$(git -C "$REPO_ROOT" log "${PREV_TAG}..HEAD" --pretty=format:'%h	%s' 2>/dev/null || true)"
else
  COMMITS="$(git -C "$REPO_ROOT" log --pretty=format:'%h	%s' 2>/dev/null || true)"
fi

if [[ -z "$COMMITS" ]]; then
  echo "Error: no commits found since $PREV_TAG" >&2
  exit 1
fi

echo "Commits since ${PREV_TAG:-initial}:"
echo "$COMMITS"
echo ""

# --- Extract code context from changed files ---

extract_code_context() {
  local prev="$1"
  local context=""

  # Get changed Kotlin files
  local changed_files
  if [[ -n "$prev" ]]; then
    changed_files="$(git -C "$REPO_ROOT" diff --name-only "$prev"..HEAD -- '*.kt' 2>/dev/null || true)"
  else
    changed_files="$(git -C "$REPO_ROOT" diff --name-only HEAD -- '*.kt' 2>/dev/null || true)"
  fi

  if [[ -z "$changed_files" ]]; then
    echo ""
    return
  fi

  while IFS= read -r file; do
    [[ -z "$file" ]] && continue

    # Extract full diff hunks (up to 50 lines per file) — gives AI real UI code, not just signatures
    local hunks
    hunks="$(git -C "$REPO_ROOT" diff "$prev"..HEAD -U3 -- "$file" 2>/dev/null \
      | grep -E '^[+-].*' \
      | grep -v '^[+-]{3}' \
      | head -50)"

    if [[ -n "$hunks" ]]; then
      context+="=== $file ==="$'\n'
      context+="$hunks"$'\n'
      context+=$'\n'
    fi
  done <<< "$changed_files"

  echo "$context"
}

CODE_CONTEXT="$(extract_code_context "$PREV_TAG")"

if [[ -n "$CODE_CONTEXT" ]]; then
  echo "Code context extracted:"
  echo "$CODE_CONTEXT" | head -60
  echo ""
fi

# --- Generate changelog via AI ---

generate_changelog() {
  local commits="$1" version="$2" prev_tag="$3" code_context="$4"

  if [[ -z "${GITHUB_TOKEN:-}" ]]; then
    echo "Warning: GITHUB_TOKEN not set, generating raw changelog" >&2
    raw_changelog "$commits" "$version"
    CHANGELOG_ONLY="$(raw_changelog "$commits" "$version")"
    TEST_PLAN_ONLY=""
    return
  fi

  local date
  date="$(date -u +%Y-%m-%d)"

  local context_block=""
  if [[ -n "$code_context" ]]; then
    context_block="

Key code changes (function signatures, class declarations):
${code_context}"
  fi

  local prompt
  prompt="Generate release notes AND a test plan for version ${version} (date: ${date}).

You MUST return both sections. Do NOT skip the test plan.

The output must have TWO sections separated by exactly this line: ===TEST_PLAN===

SECTION 1 — Release notes (Keep a Changelog format):
- ## [version] - date, then ### Category sections (Added, Fixed, Changed, Internal)
- Link PR numbers: (#123)

Rules:
- Each entry must describe what changed for the USER, not what the developer did
- Bad: 'Created ExerciseScreen.kt' → Good: 'Added exercise tracking screen'
- Bad: 'Refactored GlucoseStore to use Flow' → Good: 'Improved glucose data loading performance'
- Group related commits into single entries (e.g. multiple dependency bumps → one entry)
- If multiple commits reference the same PR number, they are ONE change — merge into a single entry
- Skip purely internal/infrastructure changes (CI config, test fixes, dependency bumps that don't affect behavior)
- Skip commits prefixed with fix(test):, test:, chore:, ci:, build:, docs: — these are NOT user-facing
- Only describe changes visible to the end user
- Omit empty categories
- Be concise but descriptive — one sentence per entry, no fluff
- Only output the markdown, nothing else.

SECTION 2 — Test plan (after ===TEST_PLAN===):
- Group test steps by feature/area changed
- Write concrete manual steps: what to tap, what to verify, what to expect
- Cover the happy path AND edge cases for each change
- Include regression checks for related features that might be affected
- Use checkboxes: - [ ] Step description
- This is for a real person testing on a physical Android device
- CRITICAL: Only describe what the code ACTUALLY does. Read the diff carefully:
  - What UI components are used? (buttons, sliders, segmented controls, text fields)
  - What are the actual option values/labels shown to the user?
  - What are the validation bounds? Are they user-selectable or just limits?
  - Never guess UI details — if unsure, describe the feature generically${context_block}

Previous tag: ${prev_tag:-none}

Commits:
${commits}"

  local response
  response="$(curl -sS --max-time 60 \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $GITHUB_TOKEN" \
    -d "$(jq -n \
      --arg model "$MODEL" \
      --arg prompt "$prompt" \
      '{
        model: $model,
        messages: [{ role: "user", content: $prompt }],
        temperature: 0.3,
        max_tokens: 4096
      }')" \
    "https://models.github.ai/inference/chat/completions" 2>/dev/null)" || true

  local full_response
  full_response="$(echo "$response" | jq -r '.choices[0].message.content // empty' 2>/dev/null || true)"

  if [[ -z "$full_response" ]]; then
    echo "Warning: AI changelog failed, using raw format" >&2
    CHANGELOG_ONLY="$(raw_changelog "$commits" "$version")"
    TEST_PLAN_ONLY=""
    return
  fi

  # Strip markdown fences the AI may wrap around its output
  full_response="$(echo "$full_response" | sed '/^```/d')"

  # Split on ===TEST_PLAN===
  if echo "$full_response" | grep -q '^===TEST_PLAN===$'; then
    CHANGELOG_ONLY="$(echo "$full_response" | sed -n '1,/===TEST_PLAN===/p' | sed '$d')"
    TEST_PLAN_ONLY="$(echo "$full_response" | sed -n '/===TEST_PLAN===/,$ p' | tail -n +2)"
  else
    CHANGELOG_ONLY="$full_response"
    TEST_PLAN_ONLY=""
  fi
}

raw_changelog() {
  local commits="$1" version="$2"
  local date
  date="$(date -u +%Y-%m-%d)"

  local lines=""
  while IFS='	' read -r sha subject; do
    lines+="- \`${sha}\` ${subject}"$'\n'
  done <<< "$commits"

  cat <<EOF
## [${version}] - ${date}

### Internal
${lines}
EOF
}

echo "Generating changelog..."
CHANGELOG_ONLY=""
TEST_PLAN_ONLY=""
generate_changelog "$COMMITS" "$TARGET_VERSION" "$PREV_TAG" "$CODE_CONTEXT"
echo "Generated changelog:"
echo "$CHANGELOG_ONLY"
if [[ -n "$TEST_PLAN_ONLY" ]]; then
  echo "Generated test plan:"
  echo "$TEST_PLAN_ONLY"
fi
echo ""

# --- Update files ---

# Bump versionName in build.gradle.kts
sed -i.bak "s|versionName = \"$(current_version)\"|versionName = \"${TARGET_VERSION}\"|" "$GRADLE_FILE"
rm -f "${GRADLE_FILE}.bak"

# Prepend changelog section to CHANGELOG.md
{
  echo "$CHANGELOG_ONLY"
  echo ""
  cat "$CHANGELOG_FILE"
} > "${CHANGELOG_FILE}.tmp"
mv "${CHANGELOG_FILE}.tmp" "$CHANGELOG_FILE"

echo "Updated versionName to $TARGET_VERSION in build.gradle.kts"
echo "Updated CHANGELOG.md"

# --- CI mode: write outputs ---

if [[ "$PREPARE" == true ]]; then
  if [[ -z "${GITHUB_OUTPUT:-}" ]]; then
    echo "Warning: GITHUB_OUTPUT not set, skipping outputs" >&2
    exit 0
  fi

  # Write body file for the workflow
  BODY_FILE="/tmp/strimma-release-pr-body-${TARGET_VERSION}.md"
  cat > "$BODY_FILE" <<EOF
## Release ${TARGET_VERSION}

### Changes

\`\`\`markdown
${CHANGELOG_ONLY}
\`\`\`
EOF

  if [[ -n "$TEST_PLAN_ONLY" ]]; then
    cat >> "$BODY_FILE" <<EOF

### Test plan

${TEST_PLAN_ONLY}
EOF
  fi

  {
    echo "version=${TARGET_VERSION}"
    echo "branch=${BRANCH}"
    echo "title=${TITLE}"
    echo "body_file=${BODY_FILE}"
  } >> "$GITHUB_OUTPUT"

  echo "Wrote release metadata to \$GITHUB_OUTPUT"
  exit 0
fi

# --- Local mode: commit, push, create PR ---

echo ""
echo "Creating branch $BRANCH..."
git -C "$REPO_ROOT" checkout -B "$BRANCH"
git -C "$REPO_ROOT" add app/build.gradle.kts CHANGELOG.md
git -C "$REPO_ROOT" commit -m "$TITLE"

echo "Pushing..."
git -C "$REPO_ROOT" push -u origin "$BRANCH" --force-with-lease

echo "Creating PR..."
BODY_FILE="/tmp/strimma-release-pr-body-${TARGET_VERSION}.md"
cat > "$BODY_FILE" <<EOF
## Release ${TARGET_VERSION}

### Changes

\`\`\`markdown
${CHANGELOG_ONLY}
\`\`\`
EOF

if [[ -n "$TEST_PLAN_ONLY" ]]; then
  cat >> "$BODY_FILE" <<EOF

### Test plan

${TEST_PLAN_ONLY}
EOF
fi

REPO_SLUG="$(git -C "$REPO_ROOT" remote get-url origin | sed 's|.*github.com[:/]||; s|\.git$||')"

# Check for existing open PR for this branch
EXISTING_PR_URL="$(gh pr list --repo "$REPO_SLUG" --head "$BRANCH" --base main --state open --json url --jq '.[0].url' 2>/dev/null || true)"

if [[ -n "$EXISTING_PR_URL" ]]; then
  PR_NUMBER="$(gh pr list --repo "$REPO_SLUG" --head "$BRANCH" --base main --state open --json number --jq '.[0].number')"
  gh pr edit "$PR_NUMBER" --repo "$REPO_SLUG" --title "$TITLE" --body-file "$BODY_FILE"
  echo ""
  echo "Done! Updated existing PR #${PR_NUMBER} for v${TARGET_VERSION}"
else
  gh pr create \
    --repo "$REPO_SLUG" \
    --base main \
    --head "$BRANCH" \
    --title "$TITLE" \
    --body-file "$BODY_FILE"
  echo ""
  echo "Done! PR created for v${TARGET_VERSION}"
fi
