# Strimma — P2 Roadmap & Competitive Analysis

**Date:** 2026-03-18
**Status:** P2 Complete

---

## Table of Contents

1. [Current State (P1 + P2 delivered)](#1-current-state-p1--p2-delivered)
2. [Competitive Landscape](#2-competitive-landscape)
3. [What Strimma Does That Others Don't](#3-what-strimma-does-that-others-dont)
4. [What Others Do That Strimma Doesn't](#4-what-others-do-that-strimma-doesnt)
5. [Remaining Work](#5-remaining-work)
6. [Future Phases](#6-future-phases)
7. [Non-Goals (Confirmed)](#7-non-goals-confirmed)

---

## 1. Current State (P1 + P2 delivered)

### What's Built

Strimma is a focused Android CGM display app. It replaces xDrip+ for a single use case: Libre 3 -> CamAPS FX -> notification parsing -> local display + Springa push.

**Architecture:**
- 32 Kotlin source files, ~3,500 lines production code
- 6 test files, 66 tests (46 unit + 20 integration)
- Modern stack: Compose + Material 3, Room, Hilt, Ktor, Coroutines
- compileSdk/targetSdk/minSdk 36 (Android 16)
- Java 21 (Zulu) — repo-specific via `gradle.properties`
- Single-device target (Pixel 9 Pro)

**Data Pipeline:**
- `GlucoseNotificationListener` — NotificationListenerService that parses CamAPS FX's ongoing notification for glucose values
- `GlucoseParser` — extracted top-level glucose text parser (testable, handles comma/dot decimals, Unicode cleanup)
- `StrimmaService` — foreground service that processes readings, computes direction, updates notification, checks alerts, updates widget, pushes to Springa
- `SpringaPusher` / `SpringaClient` — HTTP push with retry (12 attempts, linear backoff) and offline resilience (unpushed readings survive restart)
- `DirectionComputer` — EASD/ISPAD 2020 thresholds, 3-point averaged SGV, 5-minute delta

**Display:**
- Notification: collapsed (mini graph overlay with BG + arrow + delta, top gradient for text readability) + expanded (larger graph with BG + arrow + delta)
- Status bar icon: BG number rendered as bitmap
- Main app: centered BG header (value, arrow, delta, staleness) + interactive Compose Canvas graph (pinch zoom, pan) + 24h minimap + stats shortcut in toolbar
- Graph: color-coded dots (blue in-range, orange above high, red below low), threshold lines, in-range zone band, scrub-to-inspect tooltip
- 30-minute prediction: white dots with dashed lines extrapolated from direction computation rate, visible in both main graph and notification graphs
- Theme: Dark / Light / System picker in Settings. Graph surfaces adapt to theme. Status colors fixed across both themes.

**Home Screen Widget (P2 — delivered):**
- Jetpack Glance widget with current BG, trend arrow, delta, time since last reading
- Mini graph showing recent readings
- Configurable opacity via WidgetConfigActivity
- Updates on each new reading via GlanceAppWidgetManager

**Statistics Screen (P2 — delivered):**
- Time in Range (TIR) with visual bar
- Glucose Management Indicator (GMI / estimated HbA1c)
- Average glucose, coefficient of variation (CV%)
- Readings count and data coverage
- Period selector: 24h, 7d, 14d, 30d
- CSV export with share intent

**Alerts (P2 — delivered):**
- 5 alert types, each with its own notification channel:
  - Urgent Low (default 3.0 mmol/L) — alarm sound, DND bypass, aggressive vibration
  - Low (default 4.0 mmol/L) — notification sound, standard vibration
  - High (default 10.0 mmol/L) — notification sound, standard vibration
  - Urgent High (default 13.0 mmol/L) — alarm sound, DND bypass, aggressive vibration
  - Stale Data (10+ minutes without reading) — notification sound, subtle vibration
- Per-alarm: enable/disable toggle, configurable threshold
- Per-alarm sound: each alert type has its own Android notification channel; "Sound" button opens system channel settings for ringtone/vibration/DND customization
- Snooze persisted to SharedPreferences (survives process restart)
- Alerts auto-clear when glucose returns to range

**Settings (P2 — expanded):**
- Springa URL and API secret (EncryptedSharedPreferences) — saved on field blur
- Graph window (1-8h slider), BG low/high thresholds
- Theme picker (Dark / Light / System)
- Full alert configuration: per-alarm enable/threshold/sound
- Statistics access
- Debug log access + share

**Debug (P2 — delivered):**
- File-based persistent logging (`filesDir/logs/strimma-YYYY-MM-DD.log`)
- 7-day log rotation, auto-pruned on app start
- In-memory StateFlow for live UI + file history in debug screen
- Share button via FileProvider

**Graph Infrastructure (P2 — delivered):**
- Shared `graph/GraphColors.kt` module with constants (`CRITICAL_LOW/HIGH`), color function, and Y-range computation
- Both `GraphRenderer` (notification bitmaps) and `GlucoseGraph` (Compose) use the shared module
- Prediction dots rendered in both graphs using direction computation rate
- Compact graph top gradient for widget text readability

**Testing (P2 — delivered):**
- Robolectric 4.16 on SDK 36 with Java 21 (repo-specific)
- Unit tests (46): DirectionComputer (13), GlucoseParser (17), GraphColors (12), SecretHash (4)
- Integration tests (20): ReadingDao with in-memory Room (9), full reading pipeline (11)
- All tests run on JVM — no emulator needed

### What Was Delivered in P2

| Feature | Status |
|---------|--------|
| Glucose alerts (urgent low, low, high, urgent high, stale) | Done |
| Per-alarm notification channels + sound selection | Done |
| Persistent snooze (SharedPreferences) | Done |
| Persistent file-based debug logging | Done |
| Prediction / trend extrapolation (30 min, white dots, direction-matched) | Done |
| Settings debounce (save on blur) | Done |
| Graph rendering consolidation (shared GraphColors) | Done |
| Light mode + theme picker (Dark / Light / System) | Done |
| Theme-adaptive graph surfaces and canvas internals | Done |
| Home screen widget (Glance, with graph and opacity config) | Done |
| Statistics screen (TIR, GMI, CV%, coverage, CSV export) | Done |
| Unit tests (46 tests, 4 test classes) | Done |
| Integration tests (20 tests, Robolectric + Room) | Done |
| Java 21 repo-specific configuration | Done |

### Known Issues & Remaining Gaps

1. **No mg/dL support.** mmol/L only.
2. **No data broadcast.** Can't feed other apps (GDH, AAPS) from Strimma.

---

## 2. Competitive Landscape

### The Players

| App | Purpose | Sensor Support | Open Source | Distribution |
|-----|---------|---------------|-------------|-------------|
| **xDrip+** | Full CGM platform | 15+ CGM types | Yes | GitHub APK |
| **Juggluco** | CGM display + management | Libre 1/2/2+/3/3+, Dexcom G7, Sibionics, AccuChek | Yes (public domain) | Google Play |
| **Diabox** | CGM display | Libre 1/2/2+/3/3+ (via Bubble) | No | Telegram/GitHub |
| **GlucoDataHandler** | CGM hub + watch bridge | Any (receives from other apps) | Yes | Google Play |
| **Nightscout** | Cloud CGM dashboard | Any (receives via uploaders) | Yes | Self-hosted web |
| **CamAPS FX** | Closed-loop pump control | Libre 3 (direct BLE) | No | Prescription |
| **Strimma** | CGM display + Springa push | Libre 3 (via CamAPS notification) | Private | Sideload APK |

### Feature Matrix

| Feature | xDrip+ | Juggluco | Diabox | GDH | Strimma |
|---------|--------|----------|--------|-----|---------|
| **Direct BLE CGM** | Yes (15+ sensors) | Yes (Libre, Dexcom, etc.) | Yes (Libre + Bubble) | No (receives from others) | No (via CamAPS notification) |
| **Glucose alerts** | Yes (highly configurable) | Yes (low/high + medication reminders) | Yes | Yes (5 levels) | Yes (5 levels, per-alarm channels) |
| **Home screen widget** | Yes | Yes + floating overlay | Via complications | Yes + floating | Yes (Glance, with graph) |
| **Watch support** | Yes (5 platforms) | Yes (Wear OS native) | Via G-Watch | Yes (Wear OS, MiBand, Amazfit, Garmin, Fitbit) | No (watch reads Springa) |
| **Nightscout upload** | Yes | Yes | No | No | Custom (Springa) |
| **LibreView upload** | No | Yes (unique) | No | No | No |
| **Calibration** | Yes | Yes (-40 to +20 mg/dL) | Yes (key feature) | No | No |
| **Prediction** | Yes (multiple algorithms) | No | No | No | Yes (30-min linear) |
| **Statistics (TIR, GMI)** | Yes | Yes | Limited | No | Yes (TIR, GMI, CV%, coverage) |
| **Lock screen display** | Via notification | No | Yes | Yes (AOD wallpaper) | Via notification |
| **Interactive graph** | Yes (pinch zoom) | Yes (landscape, two-finger zoom) | Basic | Basic | Yes (zoom, pan, scrub, minimap) |
| **24h overview** | Yes | Yes | No | No | Yes (minimap) |
| **Data broadcast** | Yes (xDrip broadcast) | Yes (xDrip broadcast, Nightscout) | Yes (Share data) | Yes (broadcast) | **No** |
| **mg/dL support** | Yes | Yes | Yes | Yes | **No** |
| **CSV export** | Yes | Yes | No | No | Yes |
| **Dark/Light theme** | Dark only | Partial | Dark only | Yes | Yes (Dark/Light/System) |
| **Modern architecture** | No (Java, targetSdk 24, god classes) | Mixed | Unknown | Kotlin | Yes (Kotlin, Compose, Room, Hilt) |
| **Test coverage** | 67 files / 976 (6.9%) | Unknown | Unknown | Unknown | 66 tests, unit + integration |
| **Code quality** | 976 Java files, 3,880-line Home.java | Single dev, public domain | Closed source | Clean Kotlin | 32 files, ~3,500 lines |

### xDrip+ Deep Comparison

| Dimension | xDrip+ | Strimma |
|-----------|--------|---------|
| Codebase size | 976 Java files | 32 Kotlin files |
| Largest class | 3,880 lines (Home.java) | ~300 lines (MainScreen.kt) |
| Target SDK | 24 (Android 7, 2016) | 36 (Android 16) |
| UI framework | XML + View system, Holo/Material 1 | Jetpack Compose + Material 3 |
| Database | Custom ActiveAndroid fork (abandoned ORM) | Room (official, migration-aware) |
| Networking | Dual OkHttp (2.x + 3.x), Retrofit 2.4 | Ktor 3.0 (Kotlin-native) |
| DI | Dagger 2.25 (barely used) + Injectors.java | Hilt 2.53 (fully integrated) |
| Threading | AsyncTask + raw Thread + RxJava 1+2 | Kotlin Coroutines + Flow |
| Security | Cleartext allowed, no encrypted prefs | HTTPS only, EncryptedSharedPreferences |
| Tests | 67 files / 976 (6.9%) | 66 tests, Robolectric + Room |
| Data coverage | ~47% of readings received | ~97% of readings received |
| Features used | ~5% of surface | 100% |

### Juggluco Comparison

**Juggluco advantages:**
- Direct BLE sensor connection (doesn't need CamAPS as intermediary)
- LibreView upload (unique among third-party apps)
- Wear OS native app with complications
- Medication reminders
- On Google Play (no sideloading)
- Calibration support
- Voice readout of glucose values
- Multi-sensor support (overlap new/old sensors)

**Strimma advantages:**
- Cleaner graph UX (minimap + scrub tooltip vs. landscape-only)
- 30-minute prediction extrapolation (Juggluco doesn't have prediction)
- Purpose-built for the CamAPS FX + Springa pipeline (no configuration maze)
- Modern Compose UI with Dark/Light/System theme (Juggluco's UI is functional but dated)
- Per-alarm notification channels with independent sound/vibration/DND settings
- Home screen widget with mini graph
- Statistics with CSV export
- 66 automated tests

---

## 3. What Strimma Does That Others Don't

1. **Purpose-built for CamAPS FX + Springa.** Zero configuration for the specific pipeline. Other apps require selecting data sources, configuring broadcast protocols, and setting up Nightscout endpoints.

2. **Direction computation at the source.** xDrip+ sends stale/wrong directions ~31% of the time. Strimma computes direction locally using EASD/ISPAD thresholds with 3-point averaging. Springa recomputes as a safety net.

3. **Graph with minimap.** The 24h minimap with draggable viewport indicator is a better navigation model than xDrip's "swipe to scroll, pinch to zoom" with no overview context. No other CGM app has this.

4. **Scrub-to-inspect.** Touch and hold on the graph to see exact value, direction, delta, and time for any reading.

5. **30-minute prediction matched to direction.** Prediction uses the same rate as the direction arrow (not raw last-2-points), so the visual slope always agrees with the displayed arrow. White dots clearly distinct from data.

6. **Per-alarm notification channels.** Each alert type has its own Android notification channel with independent sound/vibration/DND settings.

7. **~97% data coverage.** NotificationListenerService captures nearly every reading. xDrip's Aidex broadcast receiver only gets ~47%.

8. **Widget with graph.** Glance widget shows BG, arrow, delta, and a mini graph — not just a number.

9. **Modern, tested codebase.** 32 files, ~3,500 lines, 66 automated tests. Any future feature builds on a solid foundation.

---

## 4. What Others Do That Strimma Doesn't

### Nice to Have

| Feature | Who Has It | Why It Matters |
|---------|-----------|---------------|
| **mg/dL toggle** | All competitors | International standard. Not urgent for single-user, but blocks sharing the app. |
| **Data broadcast** | xDrip+, Juggluco, Diabox, GDH | `com.eveningoutpost.dexdrip.BgEstimate` intent enables ecosystem apps to receive data. |
| **Lock screen wallpaper** | GDH | AOD glucose display without unlocking. |
| **Voice readout** | Juggluco | Hands-free BG check (running, driving). |
| **Android Auto** | GDH | BG display while driving. |

---

## 5. Remaining Work

```
1. mg/dL toggle (completeness)
2. BG broadcast (if needed — watches read Springa directly)
```

Everything else from the original P2 roadmap is delivered.

---

## 6. Future Phases

These are post-P2 ideas, not committed.

### Phase 3: Polish

| Feature | Notes |
|---------|-------|
| **Notification style options** | Configurable collapsed/expanded layout, font sizes, graph time window. |
| **Graph appearance settings** | Dot size, line thickness, color palette. |
| **Data retention config** | Currently hardcoded 30 days. |

### Phase 4: Expansion (if ever needed)

| Feature | Notes |
|---------|-------|
| **Direct BLE CGM** | If CamAPS is no longer used. Massive scope. |
| **Watch app** | Native Wear OS with complications. Currently watches use Springa. |
| **Multi-user** | Not applicable for a personal app. |
| **Play Store** | Medical device compliance. Sideload is fine. |

---

## 7. Non-Goals (Confirmed)

- **BLE CGM collection** — Libre 3 bonds exclusively to CamAPS FX
- **Calibration** — Not applicable for factory-calibrated Libre 3
- **Treatment tracking** — Insulin/carb management done elsewhere
- **Watch sync** — SugarWave/SugarRun talk to Springa directly
- **Multi-user** — Single user app
- **Cloud sync** — No Nightscout/Tidepool/MongoDB. Only Springa.
- **Multi-device** — Pixel 9 Pro only
- **Play Store** — APK sideload only
- **AAPS integration** — CamAPS FX handles closed-loop control
- **LibreView upload** — Abbott's system, not relevant to this pipeline
- **NFC scanning** — Libre 3 is BLE-only, no NFC readings
- **Android Auto** — Not a priority (revisit if needed)
