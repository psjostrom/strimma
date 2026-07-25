---
name: release
description: Prepare a Strimma release — bumps versionName, creates release PR. Tag is automatic on merge.
disable-model-invocation: true
---

# Strimma Release

Prepare a release for the Strimma Android app.

## Context

- Strimma is an Android CGM companion app (Kotlin, Jetpack Compose)
- Version lives in `app/build.gradle.kts` as `versionName` (line ~41)
- CI builds a signed APK and creates a GitHub Release when a `v*` tag is pushed
- Branch protection requires PRs to merge into `main`

## Hard Rules

- **NEVER bump versionCode.** Strimma is not on Google Play. Only bump `versionName`.
- **NEVER use `gh release create`.** CI does this automatically when a version tag is pushed (`.github/workflows/release.yml`).
- **NEVER assume the latest commit is all that changed.** Always check the full commit log since the last tag.

## Automated Path (preferred)

Use the GitHub Actions workflow or the local script.

### Option A: GitHub Actions (recommended)

1. Go to Actions → "Create release PR" → Run workflow
2. Fill in:
   - **version**: explicit version (e.g. `1.4.0` or `1.4.0-rc.1`), OR
   - **bump**: patch/minor/major (when version is empty)
   - **prerelease**: check for release candidate
3. Workflow bumps `versionName`, categorizes commits, creates a PR
4. Fill in the test plan in the PR body
5. Review PR, merge — tag is created automatically by `tag-release.yml`

### Option B: Local script

```bash
# Explicit version
scripts/release.sh --version 1.4.0

# Auto-bump
scripts/release.sh --bump minor

# Release candidate
scripts/release.sh --version 1.4.0 --rc
```

Script: bumps `versionName`, categorizes commits by conventional prefix, creates branch + PR.

## Manual Path (fallback)

Use when the automated path doesn't work or you need full control.

### 1. Determine what changed

```bash
LAST_TAG=$(git tag --sort=-v:refname | grep '^v' | grep -v '\-rc\.' | head -1)
git log ${LAST_TAG}..main --oneline
```

### 2. Determine version bump

- **Major** (X.0.0): Breaking changes
- **Minor** (x.Y.0): New features or significant enhancements
- **Patch** (x.y.Z): Bug fixes only

### 3. Update versionName

Edit `app/build.gradle.kts` line ~41. Do NOT touch `versionCode`.

### 4. Create release branch and PR

```bash
git checkout -b release/X.Y.Z
git add app/build.gradle.kts
git commit -m "chore(release): bump versionName to X.Y.Z"
git push -u origin release/X.Y.Z
```

Create PR with:
- Inside ` ```markdown ` fence: release notes for end users
- Outside fence: test plan

### 6. Tag after merge

Tag is created automatically by `tag-release.yml` on PR merge. If it fails:

```bash
git checkout main && git pull
git tag -a vX.Y.Z -m "vX.Y.Z"
git push origin vX.Y.Z
```

CI builds the signed APK and creates the GitHub Release from the fenced release notes.

## Testing

```bash
scripts/test-release.sh
```

Covers version bumping, RC resolution, edge cases, and changelog generation.
