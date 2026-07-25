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
MODEL="${STRIMMA_RELEASE_MODEL:-openai/gpt-4o-mini}"

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
  grep -oP 'versionName = "\K[^"]+' "$GRADLE_FILE"
}

is_valid_version() {
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$ ]]
}

bump_version() {
  local current="$1" kind="$2"
  local major minor patch
  IFS='.' read -r major minor patch_raw <<< "$current"
  patch="${patch_raw%%-*}"  # strip pre-release suffix
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
  if [[ "$TARGET_VERSION" =~ -rc\.([0-9]+)$ ]]; then
    # Already has RC suffix — increment
    CURRENT_RC="${BASH_REMATCH[1]}"
    NEXT_RC=$((CURRENT_RC + 1))
    TARGET_VERSION="${TARGET_VERSION%-rc.*}-rc.${NEXT_RC}"
  else
    # No RC suffix — append
    TARGET_VERSION="${TARGET_VERSION}-rc.1"
  fi
fi

BRANCH="release/${TARGET_VERSION}"
TITLE="chore(release): bump versionName to ${TARGET_VERSION}"

echo "Current version: $(current_version)"
echo "Target version:  $TARGET_VERSION"
echo "Branch:          $BRANCH"

# --- Find previous release tag ---

PREV_TAG="$(git -C "$REPO_ROOT" tag --sort=-v:refname | grep '^v' | head -1 || true)"
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

# --- Generate changelog via AI ---

generate_changelog() {
  local commits="$1" version="$2" prev_tag="$3"

  if [[ -z "${GITHUB_TOKEN:-}" ]]; then
    echo "Warning: GITHUB_TOKEN not set, generating raw changelog" >&2
    raw_changelog "$commits" "$version"
    return
  fi

  local date
  date="$(date -u +%Y-%m-%d)"

  local prompt
  prompt="Generate a changelog section for version ${version} (date: ${date}).

Rules:
- Keep a Changelog format (## [version] - date, then ### Category sections)
- Categories: Added, Fixed, Changed, Internal
- User-facing language: 'Added exercise screen' not 'Created ExerciseScreen.kt'
- Group related commits into single entries
- Link PR numbers: (#123)
- Skip trivial changes (typo fixes, import reordering)
- Omit empty categories
- Be concise. No fluff. Straight to the point.
- Only output the markdown, nothing else.

Previous tag: ${prev_tag:-none}

Commits:
${commits}"

  local response
  response="$(curl -sS --max-time 30 \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $GITHUB_TOKEN" \
    -d "$(jq -n \
      --arg model "$MODEL" \
      --arg prompt "$prompt" \
      '{
        model: $model,
        messages: [{ role: "user", content: $prompt }],
        temperature: 0.3,
        max_tokens: 1024
      }')" \
    "https://models.github.ai/inference/chat/completions" 2>/dev/null)" || true

  local changelog
  changelog="$(echo "$response" | jq -r '.choices[0].message.content // empty' 2>/dev/null || true)"

  if [[ -z "$changelog" ]]; then
    echo "Warning: AI changelog failed, using raw format" >&2
    raw_changelog "$commits" "$version"
    return
  fi

  echo "$changelog"
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
CHANGELOG_SECTION="$(generate_changelog "$COMMITS" "$TARGET_VERSION" "$PREV_TAG")"
echo "Generated changelog:"
echo "$CHANGELOG_SECTION"
echo ""

# --- Update files ---

# Bump versionName in build.gradle.kts
sed -i.bak "s|versionName = \"$(current_version)\"|versionName = \"${TARGET_VERSION}\"|" "$GRADLE_FILE"
rm -f "${GRADLE_FILE}.bak"

# Prepend changelog section to CHANGELOG.md
{
  echo "$CHANGELOG_SECTION"
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

${CHANGELOG_SECTION}

---

## Checklist

- [ ] Version bump is the only metadata change
- [ ] CI is green
- [ ] Merge PR, then tag from main:
  \`git tag -a v${TARGET_VERSION} -m "v${TARGET_VERSION}" && git push origin v${TARGET_VERSION}\`
EOF

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
git -C "$REPO_ROOT" checkout -b "$BRANCH"
git -C "$REPO_ROOT" add app/build.gradle.kts CHANGELOG.md
git -C "$REPO_ROOT" commit -m "$TITLE"

echo "Pushing..."
git -C "$REPO_ROOT" push -u origin "$BRANCH"

echo "Creating PR..."
BODY_FILE="/tmp/strimma-release-pr-body-${TARGET_VERSION}.md"
cat > "$BODY_FILE" <<EOF
## Release ${TARGET_VERSION}

### Changes

${CHANGELOG_SECTION}

---

## Checklist

- [ ] Version bump is the only metadata change
- [ ] CI is green
- [ ] Merge PR, then tag from main:
  \`git tag -a v${TARGET_VERSION} -m "v${TARGET_VERSION}" && git push origin v${TARGET_VERSION}\`
EOF

gh pr create \
  --repo "$(git -C "$REPO_ROOT" remote get-url origin | sed 's|.*github.com[:/]||; s|\.git$||')" \
  --base main \
  --head "$BRANCH" \
  --title "$TITLE" \
  --body-file "$BODY_FILE"

echo ""
echo "Done! PR created for v${TARGET_VERSION}"
echo "After merge, tag: git tag -a v${TARGET_VERSION} -m \"v${TARGET_VERSION}\" && git push origin v${TARGET_VERSION}"
