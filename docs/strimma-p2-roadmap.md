# Strimma — Roadmap & Competitive Analysis

**Date:** 2026-03-19
**Status:** Phase 3 substantially complete

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

Strimma is an open-source Android CGM app inspired by xDrip+. Currently focused on Libre 3 via CamAPS FX notification parsing, with a roadmap toward broader sensor support and Nightscout integration.

**Architecture:**
- Modern stack: Compose + Material 3, Room, Hilt, Ktor, Coroutines
- compileSdk/targetSdk 36 (Android 16), minSdk 33 (Android 13 — oldest version still receiving security updates)
- Java 21 (Zulu) — repo-specific via `gradle.properties`
- Unit + integration tests via Robolectric on JVM (no emulator needed)

**Data Pipeline:**
- `GlucoseNotificationListener` — NotificationListenerService that parses CamAPS FX's ongoing notification for glucose values
- `GlucoseParser` — extracted top-level glucose text parser (testable, handles comma/dot decimals, Unicode cleanup)
- `StrimmaService` — foreground service that processes readings, computes direction, updates notification, checks alerts, updates widget, pushes to Nightscout, broadcasts BG
- `NightscoutPusher` / `NightscoutClient` — HTTP push to `/api/v1/entries` (standard Nightscout path) with retry (12 attempts, linear backoff) and offline resilience (unpushed readings survive restart).
- `DirectionComputer` — EASD/ISPAD 2020 thresholds, 3-point averaged SGV, 5-minute delta

**Display:**
- Notification: collapsed (mini graph overlay with BG + arrow + delta, top gradient for text readability) + expanded (larger graph with BG + arrow + delta)
- Status bar icon: BG number rendered as bitmap
- Main app: centered BG header (value, arrow, delta, staleness) + interactive Compose Canvas graph (pinch zoom, pan) + 24h minimap + stats shortcut in toolbar
- Graph: color-coded dots (blue in-range, orange above high, red below low), threshold lines, in-range zone band, scrub-to-inspect tooltip
- 30-minute prediction: weighted least-squares curve fitting (linear + quadratic) with exponential time decay and endpoint anchoring, visible in both main graph and notification graphs. Threshold crossing detection ("Low in X min" / "High in X min") shown in notification text and main screen pills.
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

**Unit Support (P2 — delivered):**
- mmol/L ↔ mg/dL toggle in Settings > Display
- `GlucoseUnit` enum handles all formatting, conversion, and threshold parsing
- Internal storage stays mmol/L — conversion at display time only
- Affects: hero BG, graph Y-axis labels, tooltips, notifications, alerts, stats, widget
- Graph Y-axis adapts step size (1/2 mmol or 25/50 mg/dL) and left margin for 3-digit labels

**BG Broadcast (P2 — delivered):**
- xDrip-compatible `com.eveningoutpost.dexdrip.BgEstimate` intent emitted on each reading
- Extras: BgEstimate (sgv as double), Raw, Time, BgSlope, SensorId, BgSlopeName
- Toggle in Settings > Integration, off by default
- Verified working with GlucoDataHandler (GDH)

**Settings (P2 — expanded):**
- Nightscout URL and API secret (EncryptedSharedPreferences) — saved on field blur
- Graph window (1-8h slider), BG low/high thresholds
- Unit toggle (mmol/L / mg/dL) — threshold fields adapt to selected unit
- Theme picker (Dark / Light / System)
- Full alert configuration: per-alarm enable/threshold/sound
- BG broadcast toggle (Integration section)
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
- Unit tests: DirectionComputer, GlucoseParser, GraphColors, SecretHash, GlucoseUnit
- Integration tests: ReadingDao with in-memory Room, full reading pipeline
- All tests run on JVM — no emulator needed

### What Was Delivered in P2

| Feature | Status |
|---------|--------|
| Glucose alerts (urgent low, low, high, urgent high, stale) | Done |
| Per-alarm notification channels + sound selection | Done |
| Persistent snooze (SharedPreferences) | Done |
| Persistent file-based debug logging | Done |
| Prediction (30 min, weighted least-squares, threshold crossing alerts) | Done |
| Settings debounce (save on blur) | Done |
| Graph rendering consolidation (shared GraphColors) | Done |
| Light mode + theme picker (Dark / Light / System) | Done |
| Theme-adaptive graph surfaces and canvas internals | Done |
| Home screen widget (Glance, with graph and opacity config) | Done |
| Statistics screen (TIR, GMI, CV%, coverage, CSV export) | Done |
| mg/dL unit toggle (mmol/L ↔ mg/dL, GlucoseUnit enum) | Done |
| BG broadcast (xDrip-compatible intent, verified with GDH) | Done |
| Unit tests (Robolectric) | Done |
| Integration tests (Room in-memory) | Done |
| Java 21 repo-specific configuration | Done |

### Known Issues & Remaining Gaps

None — P2 is complete.

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
| **Strimma** | CGM display + Nightscout push/follow | 60+ CGM apps (via notification parsing) | Yes (GPL v3) | GitHub releases |

### Feature Matrix

| Feature | xDrip+ | Juggluco | Diabox | GDH | Strimma |
|---------|--------|----------|--------|-----|---------|
| **Direct BLE CGM** | Yes (15+ sensors) | Yes (Libre, Dexcom, etc.) | Yes (Libre + Bubble) | No (receives from others) | No (via CamAPS notification) |
| **Glucose alerts** | Yes (highly configurable) | Yes (low/high + medication reminders) | Yes | Yes (5 levels) | Yes (5 levels, per-alarm channels) |
| **Home screen widget** | Yes | Yes + floating overlay | Via complications | Yes + floating | Yes (Glance, with graph) |
| **Watch support** | Yes (5 platforms) | Yes (Wear OS native) | Via G-Watch | Yes (Wear OS, MiBand, Amazfit, Garmin, Fitbit) | No (watch reads Nightscout) |
| **Nightscout upload** | Yes | Yes | No | No | Yes (Nightscout) |
| **LibreView upload** | No | Yes (unique) | No | No | No |
| **Calibration** | Yes | Yes (-40 to +20 mg/dL) | Yes (key feature) | No | No |
| **Prediction** | Yes (multiple algorithms) | No | No | No | Yes (30-min weighted least-squares with threshold crossing) |
| **Statistics (TIR, GMI)** | Yes | Yes | Limited | No | Yes (TIR, GMI, CV%, coverage) |
| **Lock screen display** | Via notification | No | Yes | Yes (AOD wallpaper) | Via notification |
| **Interactive graph** | Yes (pinch zoom) | Yes (landscape, two-finger zoom) | Basic | Basic | Yes (zoom, pan, scrub, minimap) |
| **24h overview** | Yes | Yes | No | No | Yes (minimap) |
| **Data broadcast** | Yes (xDrip broadcast) | Yes (xDrip broadcast, Nightscout) | Yes (Share data) | Yes (broadcast) | Yes (xDrip broadcast) |
| **mg/dL support** | Yes | Yes | Yes | Yes | Yes (mmol/L ↔ mg/dL toggle) |
| **CSV export** | Yes | Yes | No | No | Yes |
| **Dark/Light theme** | Dark only | Partial | Dark only | Yes | Yes (Dark/Light/System) |
| **Modern architecture** | Java, targetSdk 24, mature codebase | Mixed | Unknown | Kotlin | Yes (Kotlin, Compose, Room, Hilt) |
| **Test coverage** | 67 files / 976 (6.9%) | Unknown | Unknown | Unknown | Unit + integration, all on JVM |
| **Code quality** | 976 Java files, 3,880-line Home.java | Single dev, public domain | Closed source | Clean Kotlin | Small, focused codebase |

### xDrip+ Deep Comparison

| Dimension | xDrip+ | Strimma |
|-----------|--------|---------|
| Codebase size | 976 Java files | 35 Kotlin files |
| Largest class | 3,880 lines (Home.java) | ~300 lines (MainScreen.kt) |
| Target SDK | 24 (Android 7, 2016) | 36 (Android 16) |
| UI framework | XML + View system, Holo/Material 1 | Jetpack Compose + Material 3 |
| Database | ActiveAndroid (custom fork) | Room |
| Networking | OkHttp + Retrofit | Ktor 3.0 |
| DI | Dagger 2.25 | Hilt 2.53 |
| Threading | AsyncTask + raw Thread + RxJava 1+2 | Kotlin Coroutines + Flow |
| Security | Cleartext allowed, no encrypted prefs | HTTPS only, EncryptedSharedPreferences |
| Tests | 67 files / 976 (6.9%) | Unit + integration, Robolectric + Room |
| Data coverage (Android 16, companion mode) | ~47% | ~97% |
| Feature scope | Comprehensive (15+ sensors, 5 watch platforms) | Focused (CamAPS FX + Nightscout pipeline) |

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
- Purpose-built for the CamAPS FX + Nightscout pipeline (no configuration maze)
- Modern Compose UI with Dark/Light/System theme (Juggluco's UI is functional but dated)
- Per-alarm notification channels with independent sound/vibration/DND settings
- Home screen widget with mini graph
- Statistics with CSV export
- Automated test suite (unit + integration)

---

## 3. What Strimma Does That Others Don't

1. **Purpose-built for CamAPS FX + Nightscout.** Zero configuration for the specific pipeline. Other apps require selecting data sources, configuring broadcast protocols, and setting up Nightscout endpoints.

2. **Direction computation at the source.** In companion mode, received direction fields can be outdated (~31% mismatch rate observed). Strimma computes direction locally using EASD/ISPAD thresholds with 3-point averaging. the Nightscout server recomputes as a safety net.

3. **Graph with minimap.** The 24h minimap with draggable viewport indicator is a better navigation model than xDrip's "swipe to scroll, pinch to zoom" with no overview context. No other CGM app has this.

4. **Scrub-to-inspect.** Touch and hold on the graph to see exact value, direction, delta, and time for any reading.

5. **30-minute prediction matched to direction.** Prediction uses the same rate as the direction arrow (not raw last-2-points), so the visual slope always agrees with the displayed arrow. White dots clearly distinct from data.

6. **Per-alarm notification channels.** Each alert type has its own Android notification channel with independent sound/vibration/DND settings.

7. **~97% data coverage, validated.** In 14h side-by-side testing, Strimma covered 97.0% of 5-min slots vs xDrip's 96.4%. Values matched within 0.018 mmol/L on average. xDrip retired — Strimma is the sole data source.

8. **Widget with graph.** Glance widget shows BG, arrow, delta, and a mini graph — not just a number.

9. **xDrip-compatible BG broadcast.** Emits `com.eveningoutpost.dexdrip.BgEstimate` on each reading, enabling AAPS, GDH, and watches to receive data. Verified working with GDH.

10. **Modern, tested codebase.** Small, focused Kotlin codebase with unit and integration tests. Any future feature builds on a solid foundation.

---

## 4. What Others Do That Strimma Doesn't

### Nice to Have

| Feature | Who Has It | Why It Matters |
|---------|-----------|---------------|
| **Lock screen wallpaper** | GDH | AOD glucose display without unlocking. |
| **Voice readout** | Juggluco | Hands-free BG check (running, driving). |
| **Android Auto** | GDH | BG display while driving. |

---

## 5. Remaining P2 Work

All P2 work is complete. See delivery table in § 1.

---

## 6. Open Source Roadmap: A Modern Alternative to xDrip+

xDrip+ is one of the most important open-source medical projects ever built. For over 10 years it has given the diabetes community control over their own data — glucose monitoring, alerts, watch integration, cloud sync, and closed-loop pump support. It is actively maintained, widely used, and has genuinely saved lives.

Strimma exists because of xDrip+. Its feature set, UI patterns, and data pipeline are directly inspired by xDrip+'s decade of pioneering work. Strimma is not a replacement — it's a **modern alternative** built on a different architectural foundation, offering the CGM community another option alongside xDrip+ and Juggluco.

This section outlines what Strimma needs to become a viable open-source option for the broader community.

### Why build an alternative

- **Architectural diversity is healthy.** A single open-source option means a single point of failure. The CGM community benefits from having multiple well-maintained options, just as Linux has multiple distributions.
- **Modern Android conventions.** Strimma targets SDK 36, uses Compose, Room, Hilt, and Coroutines. This makes it easier for new contributors familiar with current Android development to jump in.
- **Plugin-based extensibility.** Strimma's data source architecture is designed for community contributions — adding a sensor is one file with its own tests.
- **Proven reliability.** Validated against xDrip+ in 14h side-by-side testing: 97% coverage, 0.018 mmol/L avg value difference. xDrip+ retired — Strimma is the sole data source.

### What "xDrip alternative" means

The CGM user base breaks into segments:

| Segment | % of users | What they need |
|---------|-----------|----------------|
| **Libre + closed loop** (CamAPS, AAPS) | ~30% | Companion mode data, Nightscout upload, alerts, AAPS broadcast |
| **Libre standalone** | ~25% | Direct BLE or NFC, Nightscout upload, alerts, watch |
| **Dexcom G6/G7** | ~30% | Companion mode or direct BLE, Nightscout, alerts, watch |
| **Other sensors** (Eversense, Medtrum, etc.) | ~10% | Companion mode, Nightscout, alerts |
| **Followers** (parents, partners) | ~5% | Nightscout download, alerts, watch |

### Architecture: Plugin-based data sources

Strimma uses a `GlucoseSource` enum to select between data sources. The service picks the active source based on settings. Each source has its own receiver class:

```kotlin
enum class GlucoseSource(val label: String, val description: String) {
    COMPANION("Companion Mode", "Parse notifications from CGM apps"),
    XDRIP_BROADCAST("xDrip Broadcast", "Receive xDrip-compatible BG broadcasts")
}
```

Adding a new sensor means adding an enum value and a corresponding receiver class with its own tests.

### Phase 3: Community-Ready (xDrip parity for ~60% of users) — substantially complete

Strimma is usable by most Libre and Dexcom companion-mode users. The notification parsing architecture covers 60+ CGM apps, Nightscout follower mode is implemented, and GitHub releases provide distribution.

#### 3.1 Data Source Abstraction

| Priority | Feature | Scope |
|----------|---------|-------|
| ~~**P0**~~ | ~~Plugin interface + source picker in Settings~~ | ~~Done. `GlucoseSource` enum with `COMPANION` and `XDRIP_BROADCAST`, Settings UI radio picker, wired through service + notification listener.~~ |
| ~~**P0**~~ | ~~Notification listener source (current, any app)~~ | ~~Done. `GlucoseNotificationListener` parses CamAPS FX notifications in COMPANION mode.~~ |
| ~~**P0**~~ | ~~xDrip broadcast receiver source~~ | ~~Done. `XdripBroadcastReceiver` receives `com.eveningoutpost.dexdrip.BgEstimate` in XDRIP_BROADCAST mode.~~ |
| ~~**P1**~~ | ~~Dexcom companion mode source~~ | ~~Done. Already covered by `GlucoseNotificationListener` — 14 Dexcom package variants (G6/G7/ONE/D1+/Stelo, all regions) in the allowlist. No separate API exists; the official Dexcom app exposes data only via notifications.~~ |
| ~~**P1**~~ | ~~Libre companion mode source~~ | ~~Done. Already covered by `GlucoseNotificationListener` — LibreLink and Libre 3 regional variants plus Juggluco (`tk.glucodata`) in the allowlist. No broadcast API exists in official Libre apps.~~ |
| **P2** | Direct BLE: Dexcom G7/ONE+ | Direct connection without official app. Complex but well-documented protocol. |
| **P2** | Direct BLE: Libre 2/2+ (via OOP2) | Requires out-of-process algorithm. Existing open-source implementations available. |
| **P3** | Direct BLE: Libre 3 | Most complex — sensor bonds exclusively to one app. May require patched firmware. |

#### 3.2 Data Output

| Priority | Feature | Scope |
|----------|---------|-------|
| ~~**P0**~~ | ~~BG broadcast (xDrip format)~~ | ~~Done (P2). Emits `com.eveningoutpost.dexdrip.BgEstimate`, verified with GDH.~~ |
| ~~**P0**~~ | ~~Nightscout upload~~ | ~~Done. POST to any Nightscout server via standard `/api/v1/entries` format.~~ |
| ~~**P0**~~ | ~~mg/dL toggle~~ | ~~Done (P2). GlucoseUnit enum, display-time conversion, internal storage stays mmol/L.~~ |
| ~~**P1**~~ | ~~Nightscout download (follower mode)~~ | ~~Done. `NightscoutFollower` polls a remote Nightscout server. `GlucoseSource.NIGHTSCOUT_FOLLOWER` wired through service.~~ |
| **P2** | Tidepool upload | Optional cloud sync for users who use Tidepool for clinic reports. |

#### 3.3 Device Compatibility

| Priority | Feature | Scope |
|----------|---------|-------|
| ~~**P0**~~ | ~~Lower minSdk~~ | ~~Done. Lowered to 33 (Android 13) — oldest version still receiving security updates. Medical data warrants this floor.~~ |
| **P0** | Multi-device testing | Test on at least: Pixel, Samsung, OnePlus. Different OEMs handle background services differently. |
| **P1** | Wear OS complications | Glucose data on watch faces via complications. Use existing GDH approach as reference. |
| **P2** | Wear OS standalone app | Full watch app with graph. Lower priority — complications cover 80% of the use case. |

#### 3.4 Community Infrastructure

| Priority | Feature | Scope |
|----------|---------|-------|
| ~~**P0**~~ | ~~Open source license (GPL v3)~~ | ~~Done. GPL v3 license added.~~ |
| ~~**P0**~~ | ~~README + setup guide~~ | ~~Done. README with setup guide added.~~ |
| ~~**P0**~~ | ~~GitHub releases with signed APKs~~ | ~~Done. GitHub Actions release workflow added.~~ |
| Deferred | Localization (English, Swedish baseline) | Extract all strings, set up `values-xx/strings.xml` structure. Low priority — English covers the user base. |
| Deferred | F-Droid listing | GitHub releases with signed APKs already covers distribution. Revisit if community demand emerges. |
| **P2** | Contribution guide | Architecture overview, how to add a data source plugin, how to run tests. |

### Phase 4: Full xDrip Parity

These close the remaining gaps for power users.

| Feature | Notes |
|---------|-------|
| **Calibration** | Manual calibration for sensors that need it (Libre 1/2 without factory calibration). Not needed for G7 or Libre 3. |
| **Treatment logging** | Insulin doses, carbs, notes. xDrip has this but it's rarely used — most users track treatments in AAPS, mylife, or pen apps. |
| **Prediction models** | Current weighted least-squares with quadratic fitting is comparable to AR2. IOB-aware prediction requires insulin data (AAPS integration or treatment logging). Diminishing returns without new data sources. |
| **Multiple watch platforms** | Garmin, Fitbit, Pebble. Wear OS first, others via companion data apps. |
| **Android Auto** | BG display while driving. GDH has this. |
| **Home screen floating widget** | Always-visible BG overlay. Juggluco has this. |

### Out of scope for Strimma

These xDrip+ features are not planned. Users who need them should continue using xDrip+.

- **Pump integration** (630G/640G/670G) — AAPS handles closed-loop control
- **Tizen/Pebble watch support** — low demand, Wear OS is the focus
- **Speech recognition** — niche use case
- **QR code settings export** — JSON export is the modern equivalent
- **Tasker integration** — possible later via broadcast intents if there's demand
- **InfoContentProvider** — broadcast intents provide the same data with better security

### Phase 3 Status

Phase 3 is substantially complete. All data source and data output work is done. The notification parsing architecture already covers Dexcom, Libre, Juggluco, and 60+ other CGM apps without app-specific code — there are no hidden APIs to integrate.

**Remaining Phase 3 items** are either hardware-dependent (Wear OS, multi-device testing) or deferred (localization, F-Droid). These will be addressed when there's community demand or hardware availability.

### Effort Estimate

| Phase | Scope | Effort |
|-------|-------|--------|
| ~~Phase 3.1-3.2 (data sources + Nightscout + broadcast)~~ | ~~Core platform~~ | ~~Done~~ |
| Phase 3.3 (device compat + Wear OS) | Device breadth | Medium — requires hardware |
| Phase 3.4 (community infra) | Non-code | Deferred — revisit on demand |
| Phase 4 (full parity) | Long tail | Ongoing — community-driven |

### What Strimma brings to the table

| Strength | Detail |
|----------|--------|
| **Architecture** | Plugin-based data sources. Adding a sensor is one class with its own tests. |
| **Modern stack** | Compose + Material 3, Room, Hilt, Coroutines, targetSdk 36. Familiar to any Android developer who learned after 2022. |
| **Testability** | Comprehensive test suite. Every new data source plugin gets its own tests. |
| **Approachable codebase** | Small, focused codebase. A new contributor can understand the entire app in an afternoon. |
| **UX** | Minimap navigation, scrub-to-inspect, prediction, Dark/Light/System theme, widget with graph. |
| **Complementary to xDrip+** | Different architectural approach — not competing, offering choice. Users who prefer xDrip+ should keep using it. |

---

## 7. Phase 1 Scope vs Open Source Goals

These were out of scope for phase 1. The open-source roadmap adds them as goals:

| Feature | Phase 1 | Current Status |
|---------|-------------|-------------|
| BLE CGM collection | CamAPS notification only | Phase 4 (direct BLE — complex, requires reverse engineering) |
| Companion mode (Dexcom, Libre) | CamAPS notification only | Done — 60+ CGM app packages in notification parser allowlist |
| Calibration | Not needed for Libre 3 | Phase 4 (sensors that need it) |
| Treatment tracking | Out of scope | Phase 4 (low priority) |
| Watch sync | Via Nightscout | Deferred (requires Wear OS hardware) |
| Follower mode | Out of scope | Done — `NightscoutFollower` polls remote server |
| Cloud sync | Nightscout upload | Done — upload + follower mode |
| Multi-device | Android 16 only | minSdk 33, multi-OEM testing deferred |
| Distribution | Manual APK | Done — GitHub releases with signed APKs |
| AAPS integration | Out of scope | Done — xDrip-compatible BG broadcast |
| LibreView upload | Out of scope | Out of scope (Abbott's walled garden) |
| Android Auto | Out of scope | Phase 4 |
