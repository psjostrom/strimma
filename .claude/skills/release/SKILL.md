---
name: release
description: Prepare and tag a Strimma release -- checks commit history, determines version bump, updates versionName in app/build.gradle.kts, writes CHANGELOG.md, creates release branch and PR
disable-model-invocation: true
---

# Strimma Release

Prepare a release for the Strimma Android app. This is a mechanical process -- follow every step.

## Context

- Strimma is an Android CGM companion app (Kotlin, Jetpack Compose)
- Version lives in `app/build.gradle.kts` as `versionName` (line ~41)
- CI builds a signed APK and creates a GitHub Release when a `v*` tag is pushed
- Branch protection requires PRs to merge into `main`

## Hard Rules

- **NEVER bump versionCode.** Strimma is not on Google Play. Only bump `versionName`.
- **NEVER use `gh release create`.** CI does this automatically when a version tag is pushed (`.github/workflows/release.yml`).
- **NEVER assume the latest commit is all that changed.** Always check the full commit log since the last tag.

## Process

### 1. Determine what changed

```bash
LAST_TAG=$(git tag --sort=-v:refname | grep '^v' | head -1)
echo "Last release: $LAST_TAG"
git log ${LAST_TAG}..main --oneline
```

Read every commit. Categorize each as:
- **Feature** -- new user-facing capability
- **Fix** -- bug fix
- **Improvement** -- enhancement to existing feature
- **Internal** -- refactor, test, CI, docs

### 2. Determine version bump

Based on ALL commits (not just the latest):
- **Major** (1.0.0): Breaking changes, fundamental redesign
- **Minor** (0.X.0): New features or significant enhancements present
- **Patch** (0.0.X): Bug fixes only, zero new features

A single bug fix on top of multiple features is a **minor** bump, not a patch.

Present the categorized commits and proposed version. Wait for approval before proceeding.

### 3. Write the changelog

Create or update `CHANGELOG.md` at the repo root. Use [Keep a Changelog](https://keepachangelog.com/) format:

```markdown
## [vX.Y.Z] - YYYY-MM-DD

### Added
- Feature descriptions (user-facing language, one per line)

### Fixed
- Bug fix descriptions

### Changed
- Improvements, behavior changes

### Internal
- Refactors, test improvements, CI changes (keep brief)
```

Guidelines:
- Write from the **user's perspective** -- "Added exercise history screen" not "Created ExerciseHistoryScreen.kt"
- Group related commits into single entries where it makes sense
- Skip trivial internal changes (typo fixes, import reordering)
- Link PR numbers where available: `(#123)`
- Prepend the new version section above existing entries
- Omit empty sections (if no fixes, skip ### Fixed)

### 4. Update versionName

Edit `app/build.gradle.kts` line ~41:
- Change `versionName = "X.Y.Z"` to the new version (no `v` prefix)
- Do NOT touch `versionCode` (line ~40)

### 5. Create release branch and PR

```bash
git checkout -b release/vX.Y.Z
git add app/build.gradle.kts CHANGELOG.md
```

Commit and push. Create a PR with the changelog section in the body and a checklist:
- [ ] CI passes
- [ ] Merge PR
- [ ] Tag from main: `git tag vX.Y.Z && git push origin vX.Y.Z`

### 6. After merge

Remind the user to tag from main:
```bash
git checkout main && git pull
git tag vX.Y.Z
git push origin vX.Y.Z
```

CI will build the signed APK and create the GitHub Release with auto-generated notes.
