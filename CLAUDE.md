# Strimma

Android CGM companion app. Receives glucose from CamAPS FX via NotificationListenerService, displays with graph + notification, pushes to Nightscout.

## Build

```bash
./gradlew assembleDebug        # build
./gradlew installDebug         # build + install on connected device(s)
adb connect 192.168.1.10:<port> # wireless debug (port changes on reconnect)
```

Run tests with `./gradlew testDebugUnitTest`.

## Architecture

```
Libre 3 → CamAPS FX (BLE) → notification → GlucoseNotificationListener
  → StrimmaService → Room DB → NightscoutPusher → Nightscout /api/v1/entries
                   → NotificationHelper (graph bitmap)
                   → AlertManager (urgent low/low/high/urgent high/stale)
                   → BG Broadcast (xDrip-compatible intent, optional)
```

Single-module app. Hilt DI. All async via Coroutines/Flow.

## Project Structure

- `data/` — Room entities, DAO, settings, direction computation, unit conversion, data source selection, statistics
- `graph/` — Shared graph logic (colors, Y-range computation, critical thresholds) + dampened velocity prediction
- `network/` — Nightscout HTTP client and push logic (Ktor, `/api/v1/entries`)
- `notification/` — Foreground notification (collapsed/expanded with graph bitmap), alert manager
- `receiver/` — Data source receivers (CamAPS notification parser, xDrip broadcast receiver), debug logging
- `service/` — Foreground service, boot receiver
- `ui/` — Compose screens (Main, Settings, Stats, Debug), ViewModel, theme
- `ui/theme/` — Dark + light palettes, status colors, Material 3 theme with `ThemeMode` (Dark/Light/System)

## Conventions

- **Colors:** InRange=cyan (#56CCF2), AboveHigh=amber (#FFB800), BelowLow=coral (#FF4D6A). Defined in `ui/theme/Color.kt` (Compose, prefixed `Dark`/`Light`) and `graph/GraphColors.kt` (Canvas int constants, prefixed `CANVAS_`). Keep in sync.
- **Theme:** Dark/Light/System via `ThemeMode` enum. Graphs always render on dark surfaces regardless of theme mode.
- **Direction:** Computed locally via 3-point averaged SGV + EASD/ISPAD thresholds. Never trust CamAPS broadcast direction.
- **Units:** Configurable mmol/L or mg/dL via `GlucoseUnit` enum. Store internally as mmol/L, convert at display time. SGV stored as mg/dL (Nightscout protocol). Conversion factor: `18.0182`.
- **Graph rendering:** Two renderers sharing logic — Compose Canvas (MainScreen) and Android Canvas (GraphRenderer for notifications). Both use `computeYRange()` from `graph/GraphColors.kt`.
- **Notification graph:** 1-hour window. Main graph: configurable (1-8h, default 4h).
- **Nightscout push:** Immediate on new reading via `/api/v1/entries`. Retry with linear backoff. Unpushed readings marked `pushed=0` in Room.

## Key Domain Details

- minSdk 33 (Android 13) — oldest version still receiving security updates
- Target device: Pixel 9 Pro, Android 16 (API 36)
- Single user, single sensor (Libre 3)
- Medical data — reliability is non-negotiable
- xDrip+ retired — Strimma is the sole data source (validated equivalent coverage and accuracy)
- `foregroundServiceType="specialUse"` (not connectedDevice — no direct BLE)

## Spec

Full spec in `docs/strimma-spec.md`. Phase 2 roadmap in `docs/strimma-p2-roadmap.md`.
