# Release Automation

**Date:** 2026-07-25
**Status:** Active

---

## Overview

Strimma uses a three-stage release pipeline:

1. **create-release-pr** — bumps `versionName`, categorizes commits, creates a PR
2. **tag-release** — on PR merge, extracts version from title, creates a `v*` tag, then dispatches Release APK
3. **release.yml** — builds signed APK + GitHub Release (tag push from a human, or `workflow_dispatch` from tag-release)

## Components

| File | Role |
|------|------|
| `scripts/release.sh` | Bumps `versionName`, categorizes commits by conventional prefix, generates PR body. Works locally and in CI (`--prepare` mode). |
| `.github/workflows/create-release-pr.yml` | `workflow_dispatch` — runs `release.sh --prepare`, creates verified commit + PR via GitHub API. Idempotent on re-run. |
| `.github/workflows/tag-release.yml` | Triggers on PR merge to `main` — validates trust boundaries, extracts version, creates `v*` tag, dispatches Release APK. |
| `.github/workflows/release.yml` | Triggers on `v*` tag push or `workflow_dispatch` — builds APK, creates GitHub Release with fenced markdown notes. |

## Trust Boundaries

`tag-release.yml` enforces three constraints before tagging:

1. **Same repo** — head repo must equal base repo (no forks)
2. **Release branch** — head ref must match `release/*`
3. **Version match** — head ref must be `release/<version>` where `<version>` matches the semver extracted from the PR title

The PR title must start with `chore(release):` and contain a valid semver version (e.g. `chore(release): bump versionName to 1.4.0`).

## Version Naming

| Component | Format | Example |
|-----------|--------|---------|
| Branch | `release/X.Y.Z` | `release/1.4.0` |
| PR title | `chore(release): bump versionName to X.Y.Z` | `chore(release): bump versionName to 1.4.0` |
| Git tag | `vX.Y.Z` | `v1.4.0` |
| GitHub Release name | `vX.Y.Z` | `v1.4.0` |
| `versionName` in build.gradle.kts | `X.Y.Z` | `1.4.0` |
| Pre-release | `X.Y.Z-rc.N` (branch/title/versionName); `vX.Y.Z-rc.N` (tag/release name) | `1.4.0-rc.1` / `v1.4.0-rc.1` |

**Note:** Branch, PR title, and `versionName` use `X.Y.Z` with no `v` prefix. Git tags and GitHub Release names use `vX.Y.Z` (including prereleases: `vX.Y.Z-rc.N`).

## Workflow DAG

```text
workflow_dispatch
  → create-release-pr.yml
    → release.sh --prepare (bump version, categorize commits)
    → GitHub API: create commit, branch, PR

PR merged to main
  → tag-release.yml
    → Validate: same repo, release/* branch, version match
    → Create + push v* tag
    → workflow_dispatch release.yml --ref v*
      (required: tags pushed with GITHUB_TOKEN do not trigger on:push workflows)

release.yml (tag push from a human, or dispatch from tag-release)
  → Build signed APK
  → Create GitHub Release (fenced markdown from PR body)
```

## Release Script Usage

```bash
# Explicit version
scripts/release.sh --version 1.4.0

# Auto-bump from current versionName
scripts/release.sh --bump patch

# Release candidate
scripts/release.sh --version 1.4.0 --rc

# CI mode (skip git ops, write to GITHUB_OUTPUT)
scripts/release.sh --prepare --bump minor
```

## Changelog Generation

Commits are categorized by conventional commit prefix (no AI):

| Prefix | Category | Example |
|--------|----------|---------|
| `feat:` | Added | `feat: add alert cooldown setting` |
| `fix:` | Fixed | `fix: correct tag range in release script` |
| `refactor:` | Changed | `refactor: simplify glucose unit conversion` |
| `chore:`, `ci:`, `test:`, `docs:`, `build:` | Skipped | Not user-facing |
| Scoped non-user-facing | Skipped | `feat(ci): ...`, `fix(test): ...` |
| Uncategorized | Internal | Anything without a recognized prefix |

## RC (Release Candidate) Logic

- `--version 1.4.0 --rc` → `1.4.0-rc.1` (first RC)
- `--version 1.4.0 --rc` when current is `1.4.0-rc.2` → `1.4.0-rc.3` (increment)
- `--bump patch` when current is `1.4.0-rc.1` → `1.4.0` (drop RC suffix)
- While on an RC train, bump compares against the train shape (patch < minor < major):
  - Same or smaller bump + `--rc` stays on the base and increments the RC
    (`1.4.0-rc.1` + minor/patch + `--rc` → `1.4.0-rc.2`)
  - Larger bump + `--rc` starts a new train
    (`1.4.0-rc.1` + major + `--rc` → `2.0.0-rc.1`)
- Errors if the base version `v1.4.0` tag already exists (already released)

## Failure Modes

| Symptom | Cause | Fix |
|---------|-------|-----|
| PR created but never auto-tagged | Branch name has `v` prefix (`release/v1.4.0`) | Use `release/1.4.0` (no `v`) |
| Tag workflow skips | PR title doesn't start with `chore(release):` | Ensure title matches format |
| Version validation fails | Title contains `v` prefix in version | Use `X.Y.Z` not `vX.Y.Z` in title |
| Tag exists but no GitHub Release | Tag was pushed with `GITHUB_TOKEN` and Release APK was not dispatched | Re-push the tag from a human credential, or re-run tag-release after the dispatch step is in place |
| Release body is auto-generated PR list | Fenced ` ```markdown ` notes failed to extract (often CRLF in PR body) | `release.yml` strips `\r` before fence matching; edit the release notes manually if already published |

## Testing

```bash
# Run release script tests
scripts/test-release.sh
```

Covers version bumping, RC resolution, edge cases, and changelog generation.
