#!/usr/bin/env bash
set -euo pipefail

# Strimma release script.
# Bumps versionName, categorizes commits for PR body release notes.
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

# --- Categorize commits by conventional commit prefix ---

generate_changelog() {
  local commits="$1" version="$2"
  local date
  date="$(date -u +%Y-%m-%d)"

  local added="" fixed="" changed="" internal=""

  while IFS='	' read -r sha subject; do
    [[ -z "$sha" ]] && continue

    # Skip non-user-facing commits
    # 1. Check scope — feat(ci): is NOT user-facing even though it starts with feat
    local scope=""
    local scope_re='^[a-z]+\(([^)]+)\):'
    if [[ "$subject" =~ $scope_re ]]; then
      scope="${BASH_REMATCH[1]}"
    fi
    case "$scope" in
      ci|test|chore|docs|build) continue ;;
    esac
    # 2. Check type — chore:, ci:, test:, etc. are never user-facing
    local type=""
    local type_re='^([a-z]+)'
    if [[ "$subject" =~ $type_re ]]; then
      type="${BASH_REMATCH[1]}"
    fi
    case "$type" in
      chore|ci|test|docs|build) continue ;;
    esac

    # Categorize and strip prefix
    local category="" entry=""
    case "$subject" in
      feat:*)     category="added"  ; entry="- ${subject#feat: }" ;;
      feat\(*:*)  category="added"  ; entry="- ${subject#*: }" ;;
      fix:*)      category="fixed"  ; entry="- ${subject#fix: }" ;;
      fix\(*:*)   category="fixed"  ; entry="- ${subject#*: }" ;;
      refactor:*) category="changed"; entry="- ${subject#refactor: }" ;;
      refactor\(*:*) category="changed"; entry="- ${subject#*: }" ;;
      *)          category="internal"; entry="- ${subject}" ;;
    esac

    entry+=" (\`${sha}\`)"
    case "$category" in
      added)    added+="$entry"$'\n' ;;
      fixed)    fixed+="$entry"$'\n' ;;
      changed)  changed+="$entry"$'\n' ;;
      internal) internal+="$entry"$'\n' ;;
    esac
  done <<< "$commits"

  # Build output — only include non-empty sections
  local output="## [${version}] - ${date}"$'\n\n'

  if [[ -n "$added" ]]; then
    output+="### Added"$'\n'"${added}"$'\n'
  fi
  if [[ -n "$fixed" ]]; then
    output+="### Fixed"$'\n'"${fixed}"$'\n'
  fi
  if [[ -n "$changed" ]]; then
    output+="### Changed"$'\n'"${changed}"$'\n'
  fi
  if [[ -n "$internal" ]]; then
    output+="### Internal"$'\n'"${internal}"$'\n'
  fi

  echo "$output"
}

echo "Generating changelog..."
RELEASE_NOTES="$(generate_changelog "$COMMITS" "$TARGET_VERSION")"
echo "Release notes:"
echo "$RELEASE_NOTES"

# --- Update files ---

# Bump versionName in build.gradle.kts
sed -i.bak "s|versionName = \"$(current_version)\"|versionName = \"${TARGET_VERSION}\"|" "$GRADLE_FILE"
rm -f "${GRADLE_FILE}.bak"

echo "Updated versionName to $TARGET_VERSION in build.gradle.kts"

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
${RELEASE_NOTES}
\`\`\`

---

## Test plan

<!-- Fill in manual test steps for the changes above -->
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
git -C "$REPO_ROOT" checkout -B "$BRANCH"
git -C "$REPO_ROOT" add app/build.gradle.kts
git -C "$REPO_ROOT" commit -m "$TITLE"

echo "Pushing..."
git -C "$REPO_ROOT" push -u origin "$BRANCH" --force-with-lease

echo "Creating PR..."
BODY_FILE="/tmp/strimma-release-pr-body-${TARGET_VERSION}.md"
cat > "$BODY_FILE" <<EOF
## Release ${TARGET_VERSION}

### Changes

\`\`\`markdown
${RELEASE_NOTES}
\`\`\`

---

## Test plan

<!-- Fill in manual test steps for the changes above -->
EOF

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
