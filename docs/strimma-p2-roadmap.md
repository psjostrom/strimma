# Strimma — P2 Roadmap & Competitive Analysis

**Date:** 2026-03-18
**Status:** In Progress

---

## Table of Contents

1. [Current State (P1 + P2 delivered)](#1-current-state-p1--p2-delivered)
2. [Competitive Landscape](#2-competitive-landscape)
3. [What Strimma Does That Others Don't](#3-what-strimma-does-that-others-dont)
4. [What Others Do That Strimma Doesn't](#4-what-others-do-that-strimma-doesnt)
5. [Remaining P2 Work](#5-remaining-p2-work)
6. [Future Phases](#6-future-phases)
7. [Non-Goals (Confirmed)](#7-non-goals-confirmed)

---

## 1. Current State (P1 + P2 delivered)

### What's Built

Strimma is a focused Android CGM display app. It replaces xDrip+ for a single use case: Libre 3 -> CamAPS FX -> notification parsing -> local display + Springa push.

**Architecture:**
- ~28 Kotlin files, ~2,200 lines total
- Modern stack: Compose + Material 3, Room, Hilt, Ktor, Coroutines
- compileSdk/targetSdk 36 (Android 16)
- Single-device target (Pixel 9 Pro)

**Data Pipeline:**
- `GlucoseNotificationListener` — NotificationListenerService that parses CamAPS FX's ongoing notification for glucose values
- `StrimmaService` — foreground service that processes readings, computes direction, updates notification, checks alerts, pushes to Springa
- `SpringaPusher` / `SpringaClient` — HTTP push with retry (12 attempts, linear backoff) and offline resilience (unpushed readings survive restart)
- `DirectionComputer` — EASD/ISPAD 2020 thresholds, 3-point averaged SGV, 5-minute delta

**Display:**
- Notification: collapsed (mini graph overlay with BG + arrow + delta) + expanded (larger graph with BG + arrow + delta)
- Status bar icon: BG number rendered as bitmap
- Main app: centered BG header (value, arrow, delta, staleness) + interactive Compose Canvas graph (pinch zoom, pan) + 24h minimap
- Graph: color-coded dots (blue in-range, orange above high, red below low), threshold lines, in-range zone band, scrub-to-inspect tooltip
- 30-minute prediction: faded dots with dashed lines extrapolated from current trend, visible in both main graph and notification graphs
- Dark theme with refined palette (navy-dark backgrounds, surface cards)

**Alerts (P2 — delivered):**
- 5 alert types, each with its own notification channel:
  - Urgent Low (default 3.0 mmol/L) — alarm sound, DND bypass, aggressive vibration
  - Low (default 4.0 mmol/L) — notification sound, standard vibration
  - High (default 10.0 mmol/L) — notification sound, standard vibration
  - Urgent High (default 13.0 mmol/L) — alarm sound, DND bypass, aggressive vibration
  - Stale Data (10+ minutes without reading) — notification sound, subtle vibration
- Per-alarm: enable/disable toggle, configurable threshold
- Per-alarm sound: each alert type has its own Android notification channel; "Sound" button opens system channel settings for ringtone/vibration/DND customization
- 30-minute snooze via notification action button (in-memory — re-alerts on process restart, which is correct safety behavior)
- Alerts auto-clear when glucose returns to range

**Settings:**
- Springa URL and API secret (EncryptedSharedPreferences) — saved on field blur, not every keystroke
- Graph window (1-8h slider), BG low/high thresholds
- Full alert configuration: per-alarm enable/threshold/sound
- Debug log access + share

**Debug (P2 — delivered):**
- File-based persistent logging (`filesDir/logs/strimma-YYYY-MM-DD.log`)
- 7-day log rotation, auto-pruned on app start
- In-memory StateFlow for live UI + file history in debug screen
- Share button via FileProvider

**Graph Infrastructure (P2 — delivered):**
- Shared `graph/GraphColors.kt` module with constants (`CRITICAL_LOW/HIGH`), color function, and Y-range computation
- Both `GraphRenderer` (notification bitmaps) and `GlucoseGraph` (Compose) use the shared module
- Prediction dots rendered in both graphs

### What Was Delivered in P2

| Feature | Status | Commit |
|---------|--------|--------|
| Glucose alerts (urgent low, low, high, urgent high, stale) | Done | `8755105`, `c04515b` |
| Per-alarm notification channels + sound selection | Done | `c04515b` |
| Persistent file-based debug logging | Done | `20c89a0` |
| Prediction / trend extrapolation (30 min) | Done | `200f2a2`, `a5f3d98` |
| Settings debounce (save on blur) | Done | `1879739` |
| Graph rendering consolidation (shared GraphColors) | Done | `200f2a2` |

### Known Issues & Remaining Gaps

1. **No widget.** Must open the app or read the notification to see current BG.
2. **Notification graph is transparent background.** Works on dark notification shades but may be invisible on light themes (not a problem on Pixel 9 Pro dark mode, but fragile).
3. **No mg/dL support.** mmol/L only.
4. **No statistics.** TIR, GMI, etc. only available in Springa.

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
| **Home screen widget** | Yes | Yes + floating overlay | Via complications | Yes + floating | **No** |
| **Watch support** | Yes (5 platforms) | Yes (Wear OS native) | Via G-Watch | Yes (Wear OS, MiBand, Amazfit, Garmin, Fitbit) | **No** (watch reads Springa) |
| **Nightscout upload** | Yes | Yes | No | No | Custom (Springa) |
| **LibreView upload** | No | Yes (unique) | No | No | No |
| **Calibration** | Yes | Yes (-40 to +20 mg/dL) | Yes (key feature) | No | No |
| **Prediction** | Yes (multiple algorithms) | No | No | No | Yes (30-min linear) |
| **Statistics (TIR, GMI)** | Yes | Yes | Limited | No | **No** |
| **Lock screen display** | Via notification | No | Yes | Yes (AOD wallpaper) | Via notification |
| **Interactive graph** | Yes (pinch zoom) | Yes (landscape, two-finger zoom) | Basic | Basic | Yes (zoom, pan, scrub, minimap) |
| **24h overview** | Yes | Yes | No | No | Yes (minimap) |
| **Data broadcast** | Yes (xDrip broadcast) | Yes (xDrip broadcast, Nightscout) | Yes (Share data) | Yes (broadcast) | **No** |
| **mg/dL support** | Yes | Yes | Yes | Yes | **No** |
| **AAPS integration** | Yes (BG source) | Yes (BG source) | Yes (data share) | No | No |
| **Android Auto** | No | No | No | Yes | No |
| **Dark theme** | Yes (hardcoded) | Partial | Yes | Yes | Yes |
| **Modern architecture** | No (Java, targetSdk 24, god classes) | Mixed | Unknown | Kotlin | Yes (Kotlin, Compose, Room, Hilt) |
| **Code quality** | 976 Java files, 3,880-line Home.java | Single dev, public domain | Closed source | Clean Kotlin | ~28 files, ~2,200 lines |

### xDrip+ Deep Comparison

xDrip+ is the app Strimma replaces. The xDrip modernization spec (`docs/xdrip-modernization-spec.md`) documents its problems in detail. Key contrasts:

| Dimension | xDrip+ | Strimma |
|-----------|--------|---------|
| Codebase size | 976 Java files | ~28 Kotlin files |
| Largest class | 3,880 lines (Home.java) | ~250 lines (MainScreen.kt) |
| Target SDK | 24 (Android 7, 2016) | 36 (Android 16) |
| UI framework | XML + View system, Holo/Material 1 | Jetpack Compose + Material 3 |
| Database | Custom ActiveAndroid fork (abandoned ORM) | Room (official, migration-aware) |
| Networking | Dual OkHttp (2.x + 3.x), Retrofit 2.4 | Ktor 3.0 (Kotlin-native) |
| DI | Dagger 2.25 (barely used) + Injectors.java | Hilt 2.53 (fully integrated) |
| Threading | AsyncTask + raw Thread + RxJava 1+2 | Kotlin Coroutines + Flow |
| Security | Cleartext allowed, no encrypted prefs, exported components without permissions | HTTPS only (no cleartext flag), EncryptedSharedPreferences, minimal exported surface |
| Tests | 67 files / 976 (6.9%) | 0 (not started) |
| Features used | ~5% of surface | 100% |

Strimma's architecture is 10 years ahead of xDrip+. The feature gap has narrowed significantly with P2 — alerts, prediction, and persistent logging close the most critical gaps.

### Juggluco Comparison

Juggluco is the closest competitor in spirit — a modern, focused CGM app by a single developer.

**Juggluco advantages:**
- Direct BLE sensor connection (doesn't need CamAPS as intermediary)
- LibreView upload (unique among third-party apps)
- Wear OS native app with complications
- Medication reminders
- On Google Play (no sideloading)
- Calibration support
- Statistics (TIR, GMI, variability)
- Voice readout of glucose values
- Multi-sensor support (overlap new/old sensors)

**Strimma advantages:**
- Cleaner graph UX (minimap + scrub tooltip vs. landscape-only)
- 30-minute prediction extrapolation (Juggluco doesn't have prediction)
- Purpose-built for the CamAPS FX + Springa pipeline (no configuration maze)
- Modern Compose UI (Juggluco's UI is functional but dated)
- Per-alarm notification channels with independent sound/vibration/DND settings
- Strimma doesn't try to be everything — it does one thing and does it well

### GlucoDataHandler Comparison

GDH is interesting because it's a *hub* — it doesn't connect to sensors itself, it receives from other apps (including CamAPS FX via notification, same approach as Strimma). Its watch support is excellent (Wear OS, MiBand, Amazfit, Garmin, Fitbit). Its AOD lock screen display and Android Auto integration are unique.

GDH fills a different niche: it's a data distribution layer, not a primary display. If Strimma ever needs watch complications or Android Auto, studying GDH's approach makes sense.

---

## 3. What Strimma Does That Others Don't

1. **Purpose-built for CamAPS FX + Springa.** Zero configuration for the specific pipeline (CamAPS -> Strimma -> Springa -> SugarWave/SugarRun). Other apps require selecting data sources, configuring broadcast protocols, and setting up Nightscout endpoints.

2. **Direction computation at the source.** xDrip+ sends stale/wrong directions ~31% of the time. Strimma computes direction locally using EASD/ISPAD thresholds with 3-point averaging, same algorithm as Springa. Belt and suspenders — Springa recomputes as a safety net.

3. **Graph with minimap.** The 24h minimap with draggable viewport indicator is a better navigation model than xDrip's "swipe to scroll, pinch to zoom" with no overview context. No other CGM app has this.

4. **Scrub-to-inspect.** Touch and hold on the graph to see exact value, direction, delta, and time for any reading. xDrip has a tap-to-inspect but it's less polished.

5. **30-minute prediction.** Linear extrapolation shown as faded dots on both the main graph and notification graphs. Juggluco and Diabox don't offer prediction.

6. **Per-alarm notification channels.** Each alert type (urgent low, low, high, urgent high, stale) has its own Android notification channel. The user can set a different ringtone, vibration pattern, and DND override per alarm type through the standard Android channel settings UI.

7. **Clean notification layout.** Collapsed notification overlays BG + arrow + delta on top of a mini graph with prediction. No wasted space. xDrip's notification is text-heavy with graph as a separate element.

8. **Modern, maintainable codebase.** The entire app is ~28 files, ~2,200 lines, with proper architecture. Any future feature builds on a solid foundation instead of fighting legacy code.

---

## 4. What Others Do That Strimma Doesn't

### High Value

| Feature | Who Has It | Why It Matters |
|---------|-----------|---------------|
| **Home screen widget** | xDrip+, Juggluco, GDH | Glanceable BG without opening app or reading notification. Reduces cognitive load. |
| **mg/dL toggle** | All competitors | International standard. Not urgent for single-user, but blocks sharing the app. |
| **Statistics (TIR, GMI)** | xDrip+, Juggluco | Time-in-range and estimated HbA1c from local data. Currently only available in Springa. |

### Nice to Have

| Feature | Who Has It | Why It Matters |
|---------|-----------|---------------|
| **Data broadcast** | xDrip+, Juggluco, Diabox, GDH | `com.eveningoutpost.dexdrip.BgEstimate` intent enables ecosystem apps (AAPS, GDH, watch apps) to receive data from Strimma. |
| **Lock screen wallpaper** | GDH | AOD glucose display without unlocking. |
| **Voice readout** | Juggluco | Hands-free BG check (running, driving). |
| **Android Auto** | GDH | BG display while driving. |

---

## 5. Remaining P2 Work

### Still To Do

```
1. Home screen widget (biggest remaining QoL improvement)
2. Notification graph background fix (quick win — transparent -> solid dark)
3. mg/dL toggle (completeness)
4. Statistics screen (completeness)
5. BG broadcast (if needed)
```

### Completed

```
[x] Glucose alerts — urgent low, low, high, urgent high, stale (8755105, c04515b)
[x] Per-alarm notification channels + sound selection (c04515b)
[x] Persistent file-based debug logging + share (20c89a0)
[x] Prediction / 30-min trend extrapolation (200f2a2, a5f3d98)
[x] Settings debounce — save on blur (1879739)
[x] Graph rendering consolidation — shared GraphColors (200f2a2)
```

---

## 6. Future Phases

These are post-P2 ideas, not committed. Listed for completeness.

### Phase 3: Polish

| Feature | Notes |
|---------|-------|
| **Light theme option** | Some users prefer light mode. Not a priority for a single-user dark-mode-only app. |
| **Notification style options** | Configurable collapsed/expanded layout, font sizes, graph time window for notifications (currently hardcoded 1h). |
| **Graph appearance settings** | Dot size, line thickness, color palette. |
| **Data export** | CSV/JSON export of readings. Backup/restore. |
| **Data retention config** | Currently hardcoded 30 days. |

### Phase 4: Expansion (if ever needed)

| Feature | Notes |
|---------|-------|
| **Direct BLE CGM** | If CamAPS is no longer used, Strimma would need its own Libre 3 BLE connection. Massive scope — Libre 3 bonding is complex and sensor-exclusive. |
| **Watch app** | Native Wear OS app with complications. Currently watches read Springa via SugarWave/SugarRun — this works well. Only needed if watch latency through Springa becomes a problem. |
| **Multi-user** | Not applicable for a personal app. |
| **Play Store** | Would require medical device compliance considerations. Sideload is fine for personal use. |

---

## 7. Non-Goals (Confirmed)

These remain out of scope. Listed to prevent scope creep.

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
- **Android Auto** — Not a priority for a single-user app (revisit if needed)
