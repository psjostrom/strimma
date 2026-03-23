<p align="center">
  <img src="docs/screenshots/icon.png" width="96" alt="Strimma icon" />
</p>

<h1 align="center">Strimma</h1>

<p align="center">Android CGM companion app. Receives glucose readings from CGM apps, displays them with an interactive graph and persistent notification, and pushes to Nightscout.</p>

<p align="center">A modern alternative to xDrip+ — built with Kotlin, Jetpack Compose, and Material 3.</p>

## Features

- **Companion Mode** — reads glucose from any major CGM app's notification (CamAPS FX, Dexcom G6/G7/ONE, LibreLink, Libre 3, Juggluco, xDrip+, Diabox, Medtronic, Eversense, and more)
- **xDrip Broadcast receiver** — receives `com.eveningoutpost.dexdrip.BgEstimate` intents from any app that broadcasts in xDrip format
- **Nightscout upload** — pushes readings to any Nightscout server via `/api/v1/entries` with retry and offline resilience
- **Interactive graph** — pinch to zoom, pan, scrub-to-inspect with tooltip, 24h minimap with draggable viewport
- **Prediction with threshold warnings** — least-squares curve fitting (linear/quadratic) on last 12 min of readings, with "Low in X min" / "High in X min" warnings when trending toward thresholds
- **Persistent notification** — collapsed and expanded layouts with mini graph, BG value, trend arrow, and delta
- **Status bar icon** — current BG rendered as a bitmap icon
- **Glucose alerts** — urgent low, low, high, urgent high, and stale data, each with its own notification channel for independent sound/vibration/DND settings
- **Home screen widget** — Glance widget with BG, trend arrow, delta, mini graph, and configurable opacity
- **Statistics** — time in range, GMI, average glucose, CV%, coverage, with CSV export
- **Units** — mmol/L and mg/dL with one-tap toggle
- **BG broadcast** — emits xDrip-compatible intents so watches and other apps (AAPS, GDH) can receive data
- **Dark / Light / System theme**

## Screenshots

<p align="center">
  <img src="docs/screenshots/main-screen.png" width="240" alt="Main screen with BG graph" />
  <img src="docs/screenshots/notification-expanded.png" width="240" alt="Expanded notification with graph" />
  <img src="docs/screenshots/statistics.png" width="240" alt="Statistics" />
</p>

## Data Flow

```
Libre 3 sensor
  --> CamAPS FX (BLE)
    --> Android notification
      --> Strimma NotificationListener
        --> Room DB
          --> Nightscout /api/v1/entries
          --> Notification (graph + BG)
          --> Alerts
          --> Widget
          --> BG Broadcast (optional)
```

Strimma does not connect to the sensor directly. It reads the glucose value from your CGM app's ongoing notification (Companion Mode) or receives it via xDrip broadcast.

## Requirements

- Android 13 (API 33) or newer
- A CGM app that shows glucose in its notification (for Companion Mode)
- A Nightscout server (optional, for cloud upload)

## Setup

### 1. Install

Download the latest APK from [GitHub Releases](https://github.com/psjostrom/strimma/releases) and install it.

Since the APK is sideloaded (not from Google Play), Android restricts some permissions by default. You need to allow restricted settings for the app you used to install the APK (e.g. your file manager or browser):

1. Go to **Settings > Apps** and find the app you used to open/install the APK (e.g. "Files by Google", "Chrome", or your file manager)
2. Tap the **three-dot menu** (top right) and select **Allow restricted settings**
   - On some devices this is under **Settings > Apps > Special access > Install unknown apps**
3. You may need to confirm with your PIN/biometrics

This is a one-time step. Without it, Android will not let you grant Notification Access to Strimma in step 3 below.

### 2. Open Strimma

Launch the app. It will ask for notification permission — grant it so Strimma can show its persistent glucose notification.

### 3. Grant Notification Access (Companion Mode)

Strimma needs to read notifications from your CGM app to get glucose values:

1. Open Strimma **Settings** (gear icon)
2. Under **Data Source**, select **Companion Mode**
3. Tap **Grant Notification Access** when prompted
4. Find **Strimma** in the system list and enable it

If Strimma doesn't appear in the notification access list, you skipped step 1 above (Allow restricted settings).

### 4. Configure Nightscout (optional)

1. In Strimma **Settings**, enter your Nightscout URL (e.g. `https://my-ns.example.com`)
2. Enter your API secret
3. Readings will push automatically on each new value

### 5. Configure Alerts (optional)

Each alert type (urgent low, low, high, urgent high, stale data) has its own Android notification channel. You can:

- Enable/disable each alert independently
- Set custom thresholds
- Tap **Sound** to open Android's channel settings for that alert, where you can pick a ringtone, vibration pattern, and whether it bypasses Do Not Disturb

### 6. Alternative: xDrip Broadcast

If your setup already broadcasts in xDrip format (from AAPS, Juggluco, xDrip+, etc.):

1. In Strimma **Settings > Data Source**, select **xDrip Broadcast**
2. No notification access needed — Strimma receives data via broadcast intent

## Build from Source

```bash
git clone https://github.com/psjostrom/strimma.git
cd strimma
./gradlew assembleDebug
```

Install on a connected device:

```bash
./gradlew installDebug
```

Run tests:

```bash
./gradlew testDebugUnitTest
```

Requires Java 21 (configured in `gradle.properties`).

## Architecture

Single-module app. 35 Kotlin source files, ~3,800 lines.

| Package | Purpose |
|---------|---------|
| `data/` | Room entities, DAO, SettingsRepository, DirectionComputer, GlucoseUnit |
| `graph/` | Shared graph constants, color functions, Y-range computation, prediction curve fitting |
| `network/` | NightscoutClient + NightscoutPusher (Ktor, `/api/v1/entries`) |
| `notification/` | NotificationHelper (collapsed/expanded with graph), GraphRenderer, AlertManager |
| `receiver/` | GlucoseNotificationListener, XdripBroadcastReceiver, GlucoseParser, DebugLog |
| `service/` | StrimmaService (foreground), BootReceiver |
| `ui/` | Compose screens (Main, Settings, Stats, Debug), MainViewModel, theme |
| `widget/` | Glance widget, config activity |

**Stack:** Kotlin, Jetpack Compose, Material 3, Room, Hilt, Ktor Client, Coroutines/Flow, DataStore, EncryptedSharedPreferences, Glance.

**Tests:** 77 tests (57 unit + 20 integration) running on JVM via Robolectric. No emulator needed.

## Contributing

All changes to `main` go through pull requests — direct pushes are blocked. PRs require:

- **Signed commits** (GPG or SSH)
- **Linear history** (squash or rebase merge only)
- **All review conversations resolved**

Fork the repo, create a branch, and open a PR when ready.

## Acknowledgments

Strimma exists because of [xDrip+](https://github.com/NightscoutFoundation/xDrip). Its feature set, UI patterns, and data pipeline are directly inspired by xDrip+'s decade of pioneering work. The CGM package list in Companion Mode is based on xDrip+'s `UiBasedCollector.coOptedPackages`.

Strimma is not a replacement for xDrip+ — it's a modern alternative built on a different architectural foundation, offering the CGM community another option.

## License

Strimma is free software licensed under the [GNU General Public License v3.0](LICENSE).
