# Strimma — P2 Roadmap & Competitive Analysis

**Date:** 2026-03-18
**Status:** Draft

---

## Table of Contents

1. [Current State (P1)](#1-current-state-p1)
2. [Competitive Landscape](#2-competitive-landscape)
3. [What Strimma Does That Others Don't](#3-what-strimma-does-that-others-dont)
4. [What Others Do That Strimma Doesn't](#4-what-others-do-that-strimma-doesnt)
5. [P2 Priorities](#5-p2-priorities)
6. [Future Phases](#6-future-phases)
7. [Non-Goals (Confirmed)](#7-non-goals-confirmed)

---

## 1. Current State (P1)

### What's Built

Strimma is a focused Android CGM display app. It replaces xDrip+ for a single use case: Libre 3 → CamAPS FX → notification parsing → local display + Springa push.

**Architecture:**
- 23 Kotlin files, ~1,500 lines total
- Modern stack: Compose + Material 3, Room, Hilt, Ktor, Coroutines
- compileSdk/targetSdk 36 (Android 16)
- Single-device target (Pixel 9 Pro)

**Data Pipeline:**
- `GlucoseNotificationListener` — NotificationListenerService that parses CamAPS FX's ongoing notification for glucose values
- `StrimmaService` — foreground service that processes readings, computes direction, updates notification, pushes to Springa
- `SpringaPusher` / `SpringaClient` — HTTP push with retry (12 attempts, linear backoff) and offline resilience (unpushed readings survive restart)
- `DirectionComputer` — EASD/ISPAD 2020 thresholds, 3-point averaged SGV, 5-minute delta

**Display:**
- Notification: collapsed (mini graph overlay with BG + arrow + delta) + expanded (larger graph with BG + arrow + delta)
- Status bar icon: BG number rendered as bitmap
- Main app: BG header (value, arrow, delta, staleness) + interactive Compose Canvas graph (pinch zoom, pan) + 24h minimap
- Graph: color-coded dots (blue in-range, orange above high, red below low), threshold lines, critical zones, scrub-to-inspect tooltip
- Dark theme only

**Settings:**
- Springa URL, API secret (EncryptedSharedPreferences), graph window (1-8h slider), BG low/high thresholds

**Debug:**
- In-memory debug log (last 50 entries), accessible from Settings

### What's Working Well

1. **Notification parsing is solid.** CamAPS FX notification → glucose extraction handles RemoteViews, title/text fallbacks, Unicode cleanup, comma/dot decimal separators.
2. **Direction computation matches Springa.** Same algorithm, same thresholds — directions should agree >98% of the time.
3. **Graph is genuinely useful.** The main graph with zoom/pan/scrub and the 24h minimap with viewport indicator is better UX than xDrip's graph for quick visual review.
4. **Architecture is clean.** Clear separation: data layer (Room + SettingsRepository), network (SpringaClient + SpringaPusher), service (StrimmaService), UI (ViewModel + Compose). Hilt wires it all. No god classes.
5. **Offline resilience.** Failed pushes are retried, unpushed readings survive process death.

### Known Issues & Gaps

1. **No alerts.** No sound, vibration, or visual alarm for low/high glucose. This is the single biggest functional gap vs. every competitor.
2. **No widget.** Must open the app or read the notification to see current BG.
3. **No prediction.** No trend extrapolation on the graph.
4. **Notification graph is transparent background.** Works on dark notification shades but may be invisible on light themes (not a problem on Pixel 9 Pro dark mode, but fragile).
5. **Graph rendering is duplicated.** `GraphRenderer` (Canvas/Bitmap for notifications) and `GlucoseGraph` (Compose Canvas for main UI) implement the same logic separately. Spec says "shared module" — not done yet.
6. **Settings save on every keystroke.** URL and API secret text fields call `onValueChange` (and persist) on every character typed. Should debounce or save on blur/confirm.
7. **Debug log is in-memory only.** Lost on process death. For a medical app running 24/7, persistent logging would help diagnose overnight failures.
8. **No mg/dL support.** mmol/L only.

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
| **Glucose alerts** | Yes (highly configurable) | Yes (low/high + medication reminders) | Yes | Yes (5 levels) | **No** |
| **Home screen widget** | Yes | Yes + floating overlay | Via complications | Yes + floating | **No** |
| **Watch support** | Yes (5 platforms) | Yes (Wear OS native) | Via G-Watch | Yes (Wear OS, MiBand, Amazfit, Garmin, Fitbit) | **No** (watch reads Springa) |
| **Nightscout upload** | Yes | Yes | No | No | Custom (Springa) |
| **LibreView upload** | No | Yes (unique) | No | No | No |
| **Calibration** | Yes | Yes (-40 to +20 mg/dL) | Yes (key feature) | No | No |
| **Prediction** | Yes (multiple algorithms) | No | No | No | **No** |
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
| **Code quality** | 976 Java files, 3,880-line Home.java | Single dev, public domain | Closed source | Clean Kotlin | 23 files, ~1,500 lines |

### xDrip+ Deep Comparison

xDrip+ is the app Strimma replaces. The xDrip modernization spec (`docs/xdrip-modernization-spec.md`) documents its problems in detail. Key contrasts:

| Dimension | xDrip+ | Strimma |
|-----------|--------|---------|
| Codebase size | 976 Java files | 23 Kotlin files |
| Largest class | 3,880 lines (Home.java) | ~165 lines (MainScreen.kt) |
| Target SDK | 24 (Android 7, 2016) | 36 (Android 16) |
| UI framework | XML + View system, Holo/Material 1 | Jetpack Compose + Material 3 |
| Database | Custom ActiveAndroid fork (abandoned ORM) | Room (official, migration-aware) |
| Networking | Dual OkHttp (2.x + 3.x), Retrofit 2.4 | Ktor 3.0 (Kotlin-native) |
| DI | Dagger 2.25 (barely used) + Injectors.java | Hilt 2.53 (fully integrated) |
| Threading | AsyncTask + raw Thread + RxJava 1+2 | Kotlin Coroutines + Flow |
| Security | Cleartext allowed, no encrypted prefs, exported components without permissions | HTTPS only (no cleartext flag), EncryptedSharedPreferences, minimal exported surface |
| Tests | 67 files / 976 (6.9%) | 0 (not started) |
| Features used | ~5% of surface | 100% |

Strimma's architecture is 10 years ahead of xDrip+. But xDrip+ has 10 years of feature accumulation. The gap is features, not quality.

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
- Purpose-built for the CamAPS FX + Springa pipeline (no configuration maze)
- Modern Compose UI (Juggluco's UI is functional but dated)
- Strimma doesn't try to be everything — it does one thing and does it well

### GlucoDataHandler Comparison

GDH is interesting because it's a *hub* — it doesn't connect to sensors itself, it receives from other apps (including CamAPS FX via notification, same approach as Strimma). Its watch support is excellent (Wear OS, MiBand, Amazfit, Garmin, Fitbit). Its AOD lock screen display and Android Auto integration are unique.

GDH fills a different niche: it's a data distribution layer, not a primary display. If Strimma ever needs watch complications or Android Auto, studying GDH's approach makes sense.

---

## 3. What Strimma Does That Others Don't

1. **Purpose-built for CamAPS FX + Springa.** Zero configuration for the specific pipeline (CamAPS → Strimma → Springa → SugarWave/SugarRun). Other apps require selecting data sources, configuring broadcast protocols, and setting up Nightscout endpoints.

2. **Direction computation at the source.** xDrip+ sends stale/wrong directions ~31% of the time. Strimma computes direction locally using EASD/ISPAD thresholds with 3-point averaging, same algorithm as Springa. Belt and suspenders — Springa recomputes as a safety net.

3. **Graph with minimap.** The 24h minimap with draggable viewport indicator is a better navigation model than xDrip's "swipe to scroll, pinch to zoom" with no overview context. No other CGM app has this.

4. **Scrub-to-inspect.** Touch and hold on the graph to see exact value, direction, delta, and time for any reading. xDrip has a tap-to-inspect but it's less polished.

5. **Clean notification layout.** Collapsed notification overlays BG + arrow + delta on top of a mini graph. No wasted space. xDrip's notification is text-heavy with graph as a separate element.

6. **Modern, maintainable codebase.** The entire app is 23 files, ~1,500 lines, with proper architecture. Any P2 feature builds on a solid foundation instead of fighting legacy code.

---

## 4. What Others Do That Strimma Doesn't

### Critical (affects safety)

| Feature | Who Has It | Why It Matters |
|---------|-----------|---------------|
| **Glucose alerts** | All competitors | Without alerts, a sleeping user won't know they're going low. CamAPS FX has its own alerts, but redundancy saves lives. |
| **Persistent logging** | xDrip+ (file logging), GDH | In-memory-only debug log is lost on process death. Can't diagnose "why did I miss a reading at 3 AM?" |

### High Value

| Feature | Who Has It | Why It Matters |
|---------|-----------|---------------|
| **Home screen widget** | xDrip+, Juggluco, GDH | Glanceable BG without opening app or reading notification. Reduces cognitive load. |
| **Prediction/extrapolation** | xDrip+ | Seeing where BG is heading (faded dots on graph) helps decide whether to act now or wait. |
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

## 5. P2 Priorities

### Tier 1: Safety & Reliability

These should ship before xDrip+ is uninstalled.

#### 1.1 Glucose Alerts

The single most important missing feature. Every competitor has this. Without alerts, Strimma is a display app, not a safety tool.

**Scope:**
- Low glucose alarm (configurable threshold, default 4.0 mmol/L)
- Urgent low alarm (configurable, default 3.0 mmol/L — different, more aggressive sound)
- High glucose alarm (configurable, default 13.0 mmol/L)
- Per-alarm: enable/disable, threshold, sound selection, vibration pattern
- Snooze: per-alert snooze (15/30/60 min), re-alert if still out of range after snooze expires
- Stale data alarm: alert if no reading received for >10 minutes
- Notification channel: separate `IMPORTANCE_HIGH` channel for alerts (distinct from the `IMPORTANCE_LOW` glucose display channel)
- Must work with phone on silent/DND — alerts need to override (user grants DND access)

**Design consideration:** CamAPS FX already has its own alerts. Strimma alerts are the redundancy layer — they should be less aggressive by default (longer snooze, no repeating alarm for highs) but urgent lows should be impossible to ignore.

#### 1.2 Persistent Debug Logging

Replace or supplement the in-memory `DebugLog` with file-based logging. Keep last 7 days of logs. Accessible from the debug screen + shareable (for diagnosing issues remotely).

**Why now:** Before xDrip gets uninstalled, we need to be confident that overnight gaps, missed pushes, and notification parsing failures are diagnosable after the fact. In-memory logs die with the process.

#### 1.3 Notification Graph Background

The current notification graph uses `Color.TRANSPARENT` background. This works on dark notification shades but is invisible on light themes. Should use a solid dark background (`#121212` or the notification shade's background color) for reliability across all Android themes.

### Tier 2: Daily Quality of Life

These make Strimma genuinely better to use day-to-day.

#### 2.1 Home Screen Widget

Glance widget showing:
- Current BG value (large, colored by range)
- Trend arrow
- Delta
- Minutes since last reading
- Mini sparkline (last 2 hours)

Use Jetpack Glance (Compose-based widget API). Update on each new reading via `AppWidgetManager.updateAppWidget()`.

#### 2.2 Prediction / Trend Extrapolation

Show predicted BG as faded dots on the graph, extrapolating from the current trend.

**Algorithm:** Linear extrapolation from the last 15 minutes of data, projected 30 minutes forward. Simple — no AR2 model like Nightscout. Faded dots + dashed line, same color coding as real dots.

**Why linear and not AR2:** This is a visual aid, not a medical prediction. The user sees "if the current rate of change continues, I'll be at X in 30 minutes." That's enough to decide whether to eat a snack or take insulin. A more sophisticated model adds complexity without proportional benefit for a single-user app.

#### 2.3 Settings Debounce

Stop persisting URL and API secret on every keystroke. Options:
- Save on field blur (onFocusChanged)
- Save on explicit "Save" button
- Debounce with 1-second delay after last keystroke

#### 2.4 Graph Rendering Consolidation

The spec says graph rendering should be shared between notification bitmaps and the Compose UI. Currently it's implemented twice (`GraphRenderer` for Canvas/Bitmap, `GlucoseGraph` for Compose DrawScope). Consolidate into one rendering function that can target either backend.

Not urgent — both implementations are consistent — but it's tech debt that will bite when adding prediction dots or other graph features.

### Tier 3: Completeness

These round out the app for broader usability.

#### 3.1 mg/dL Toggle

Add unit preference to settings. Affects:
- BG header display
- Delta display
- Graph Y axis labels
- Notification text
- Widget text
- Alert threshold display (but store internally as mmol/L always)

Conversion: `mgdl = mmol * 18.0182`, `mmol = mgdl / 18.0182`

#### 3.2 Statistics Screen

In-app statistics page showing:
- Time in range (% of readings between bgLow and bgHigh) — 24h, 7d, 30d
- GMI (Glucose Management Indicator / estimated HbA1c)
- Average glucose
- Glucose variability (CV%)
- Readings count and data coverage (% of expected 5-min intervals that have readings)

This data currently lives in Springa only. Having it locally is useful for quick daily review.

#### 3.3 BG Broadcast

Emit `com.eveningoutpost.dexdrip.BgEstimate` broadcast intent on each new reading. This enables:
- GlucoDataHandler to receive from Strimma (watch complications, Android Auto)
- AAPS to use Strimma as a BG source (if needed)
- Any xDrip-compatible app to receive data

**Bundle fields:** Match the xDrip broadcast format (timestamp, sgv, direction, delta, device identifier).

Optional — only implement if there's a concrete consumer. Currently watches read from Springa directly.

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

---

## Summary: P2 Delivery Order

```
1. Glucose alerts (safety — must have before xDrip removal)
2. Persistent logging (reliability — must have before xDrip removal)
3. Notification graph background fix (quick win)
4. Home screen widget (biggest daily QoL improvement)
5. Prediction/extrapolation (graph enhancement)
6. Settings debounce (minor polish)
7. Graph rendering consolidation (tech debt)
8. mg/dL toggle (completeness)
9. Statistics screen (completeness)
10. BG broadcast (if needed)
```

Items 1-3 should ship before xDrip+ is uninstalled. Items 4-7 make Strimma a better daily driver. Items 8-10 round things out.
