# Auto-Update

Strimma checks for new versions automatically and prompts you to install them.

## How It Works

Since Strimma is distributed via GitHub Releases (not Google Play), the app checks for updates itself:

1. Every 12 hours (and at app start), Strimma checks GitHub for a new release
2. If a newer version is available, a dialog appears with the version number and changelog
3. Tap **Update** to download and install, or **Later** to dismiss

You can also check manually at any time via **Settings → General → Check for updates**. To try pre-release versions (release candidates, betas), tap **Check for beta**.

## Required Updates

Some updates may be marked as **required** — for example, if a critical bug fix is needed. Required updates show a blocking dialog that cannot be dismissed. The app continues to function normally, but the dialog will reappear until you update.

## Permissions

Strimma needs the **Install unknown apps** permission to install updates. Android will prompt you to grant this the first time you update.

## For Maintainers

### Triggering a forced update

Edit `update.json` in the repository root and set `min_version` to the minimum acceptable version:

```json
{
  "min_version": "1.0.0"
}
```

Any device running a version older than `min_version` will see a blocking update dialog. Commit this change to `main` — the app fetches this file from the `main` branch via raw.githubusercontent.com.

### Release checklist

1. Tag and push: `git tag v1.2.3 && git push origin v1.2.3`
2. CI builds the APK and creates a GitHub Release
3. If the release fixes a critical issue, update `update.json` with the new `min_version`
