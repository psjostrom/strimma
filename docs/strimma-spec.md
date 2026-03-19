# Strimma — Spec v1

**Date:** 2026-03-17
**Status:** Draft
**Author:** Steg

Strimma is an open-source Android CGM app inspired by xDrip+. It receives glucose data from CamAPS FX, displays it with notifications and an in-app graph, and pushes readings to Nightscout. Built on modern Android conventions (Kotlin, Compose, Room, Hilt), it aims to be an approachable, well-tested alternative for the CGM community.

---

## Table of Contents

1. [Context & Rationale](#1-context--rationale)
2. [System Architecture](#2-system-architecture)
3. [Phase 1 Scope](#3-phase-1-scope)
4. [Data Input: CamAPS Notification Listener](#4-data-input-aidex-broadcast-receiver-camaps-notification-listener)
5. [Direction Computation](#5-direction-computation)
6. [Data Output: Nightscout Push](#6-data-output-nightscout-push)
7. [Local Storage](#7-local-storage)
8. [Foreground Service & Lifecycle](#8-foreground-service--lifecycle)
9. [Notification Display](#9-notification-display)
10. [Main App UI](#10-main-app-ui)
11. [Settings](#11-settings)
12. [Side-by-Side Validation with xDrip](#12-side-by-side-validation-with-xdrip)
13. [Original Backend Changes (Historical)](#13-original-backend-changes-historical)
14. [Tech Stack](#14-tech-stack)
15. [Phase 2 Roadmap](#15-phase-2-roadmap)
16. [Non-Goals](#16-non-goals)

---

## 1. Context & Rationale

### Background

xDrip+ is an extraordinary open-source project that has served the diabetes community for over 10 years. It supports 15+ CGM types, 5 watch platforms, pump integration, calibration, and much more. It is actively maintained, widely used, and has genuinely saved lives. Strimma would not exist without xDrip+ — it serves as direct inspiration for both features and implementation approaches.

Strimma started as a focused CGM app for a specific setup (Libre 3 + CamAPS FX), but is designed from the ground up as an open-source project that can grow to serve a broader audience. It uses modern Android conventions (Kotlin, Compose, Room, Coroutines, targetSdk 36) to provide a codebase that is approachable for contributors familiar with current Android development.

The open-source roadmap for broader sensor support, Nightscout integration, and community features is documented in `docs/strimma-p2-roadmap.md`.

### Side-by-side validation (completed)

Strimma was validated against xDrip+ over 14 hours of simultaneous operation:

- **Coverage:** Strimma 97.0% vs xDrip 96.4% (5-min slots)
- **Value accuracy:** 84% exact match, 0.018 mmol/L average difference
- **Direction agreement:** 79% exact, 97% within one arrow step
- **Raw throughput:** Strimma captured 2x the readings (CamAPS fires ~1/min, xDrip catches ~half)

Both tables merged into a single `xdrip_readings` table in the original backend. xDrip+ retired. Strimma is the sole data source, pushing to `/api/v1/entries` (standard Nightscout path).

---

## 2. System Architecture

```
Libre 3 sensor
  │ (BLE)
  ▼
CamAPS FX (closed-loop pump control)
  │ (notification with glucose value)
  ▼
┌──────────────────────────────────────┐
│  Android                             │
│                                      │
│  ┌─────────┐                         │
│  │ Strimma │                         │
│  │         │                         │
│  │ Notif.  │                         │
│  │ Listener│                         │
│  └────┬────┘                         │
│       │                              │
│       ▼                              │
│  POST /api/v1/entries                │
│       │                              │
└───────┼──────────────────────────────┘
        │
        ▼
┌──────────────────────────────┐
│  Nightscout                  │
│                              │
│  /api/v1/entries             │
│  (standard Nightscout API)   │
│                              │
└──────────┬───────────────────┘
           │
           ▼
    Watches / other consumers
```

Strimma uses a `NotificationListenerService` to parse glucose values from CamAPS FX's ongoing notification (see §4).

---

## 3. Phase 1 Scope

### In Scope

| Feature                      | Description                                                                 |
| ---------------------------- | --------------------------------------------------------------------------- |
| CamAPS notification listener | Receive glucose data from CamAPS FX (via NotificationListenerService)       |
| Direction computation        | 3-point averaged sgv, EASD/ISPAD thresholds                                |
| Local Room DB                | Store readings locally                                                      |
| Nightscout push              | POST to `/api/v1/entries` (standard Nightscout API)                         |
| Foreground service           | Persistent process with notification                                        |
| Expanded notification        | BG number + trend arrow + delta + graph bitmap                              |
| Collapsed notification       | BG number + delta + mini graph bitmap                                       |
| Status bar icon              | BG number rendered as notification small icon                               |
| Main app                     | Large BG number + trend arrow + delta + interactive graph                   |
| Graph coloring               | Blue dots = in range, yellow/orange dots = above high, red dots = below low |
| Threshold lines              | Dashed horizontal lines at low (4.0) and high (10.0) thresholds             |
| Settings screen              | Nightscout URL, API secret, graph time window, threshold values             |

### Out of Scope (Phase 2)

Low/high glucose alerts, 24h overview timeline, home screen widget, prediction/extrapolation, unit switching (mg/dL), BLE direct CGM collection, treatment tracking, multi-user, watch sync.

---

## 4. Data Input: ~~Aidex Broadcast Receiver~~ CamAPS Notification Listener

> **Note (2026-03-17):** The Aidex broadcast approach described below did not work — CamAPS FX does not actually send the Aidex broadcast intents reliably (or at all) on modern Android. Instead, Strimma reads glucose data by parsing CamAPS FX's notification via a `NotificationListenerService`. The notification contains the BG value and trend arrow, which Strimma extracts on each notification update. The rest of the spec (direction computation, push strategy, storage, etc.) remains unchanged — only the data input mechanism differs.

### ~~How Companion Mode Works~~ (original spec, kept for reference)

Strimma does NOT connect to the Libre 3 sensor directly. The Libre 3 bonds exclusively to CamAPS FX via BLE — only one app can hold that bond. Dual-connection is not possible with current Libre 3 firmware.

Instead, Strimma receives glucose data via broadcast intents that CamAPS FX sends using the Aidex protocol. This is the same "companion mode" approach that xDrip+ uses. CamAPS FX broadcasts every reading it receives from the sensor, and any app with the correct intent filter and permission picks it up.

### Broadcast Receiver Registration

**The receiver MUST be registered dynamically at runtime, NOT statically in the manifest.**

CamAPS FX sends implicit broadcasts (action-based, not targeting Strimma by package name). On Android 8+ (API 26) with `targetSdk 36`, implicit broadcasts are NOT delivered to manifest-registered receivers. The receiver must be registered programmatically from the foreground service:

```kotlin
// In StrimmaService.onCreate()
val filter = IntentFilter("com.microtechmd.cgms.aidex.action.BgEstimate")
registerReceiver(aidexReceiver, filter, RECEIVER_EXPORTED)
```

The `RECEIVER_EXPORTED` flag (required on API 33+) tells the system this receiver accepts broadcasts from other apps.

We only listen for `BgEstimate`. Sensor lifecycle (start/stop/restart) is entirely managed by CamAPS FX — Strimma has no role in it.

Required permission in manifest:

```xml
<uses-permission android:name="com.microtechmd.cgms.aidex.permissions.RECEIVE_BG_ESTIMATE" />
```

**Why not a manifest receiver?** xDrip+ uses a manifest-registered receiver, which works with its `targetSdk` of 24 (pre-API 26 restriction). Strimma targets API 36, where implicit broadcast delivery to manifest receivers is restricted. Dynamic registration from a foreground service is the standard approach on modern Android.

### Broadcast Bundle Fields

Extracted from xDrip's `AidexBroadcastIntents.java`:

| Extra Key                                | Type     | Description                             |
| ---------------------------------------- | -------- | --------------------------------------- |
| `com.microtechmd.cgms.aidex.Time`        | `long`   | Reading timestamp (epoch ms)            |
| `com.microtechmd.cgms.aidex.BgType`      | `String` | `"mg/dl"` or `"mmol/l"`                 |
| `com.microtechmd.cgms.aidex.BgValue`     | `double` | Glucose value                           |
| `com.microtechmd.cgms.aidex.BgSlopeName` | `String` | Trend direction (NOT TRUSTED — see §5)  |
| `com.microtechmd.cgms.aidex.SensorId`    | `String` | Sensor identifier (logged but not used) |

### Processing Rules

1. **Validate required fields:** timestamp, BG type, and BG value must all be present
2. **Unit conversion:** If BG type is `"mmol/l"`, multiply by 18.0182 to get mg/dL
3. **Reject invalid values:** BG value must be > 0
4. **Deduplicate:** If a reading with the exact same timestamp already exists, discard. Libre 3 sends readings every ~1 minute, so the dedup window is by exact timestamp match, not a time range.
5. **Compute direction:** Do NOT use the `BgSlopeName` from the broadcast — compute from stored readings (§5)
6. **Store locally:** Insert into Room DB
7. **Push to Nightscout:** Fire HTTP request immediately (§6) — zero delay, the data must reach downstream consumers ASAP

---

## 5. Direction Computation

In companion mode, the direction field received from the CGM app can be outdated — the original backend documented a ~31% mismatch rate between received and recomputed directions. Strimma computes direction locally for accuracy.

### Algorithm

Based on the EASD/ISPAD 2020 thresholds (Moser et al., Diabetologia 2020):

1. For each new reading, find the stored reading closest to **5 minutes** before it
2. If no reading exists within 10 minutes, direction = `"NONE"`
3. Compute 3-point averaged sgv for both readings: `avg(readings[i-1], readings[i], readings[i+1])`
4. Calculate `deltaMgdlPerMin = (avgSgvNow - avgSgvPast) / timeMinutes`
5. Map delta to direction using **EASD/ISPAD 2020 thresholds** (Moser et al., Diabetologia 2020):

| Delta (mg/dL/min) | Direction     | Arrow |
| ----------------- | ------------- | ----- |
| ≤ -3.0            | DoubleDown    | ⇊     |
| ≤ -2.0            | SingleDown    | ↓     |
| ≤ -1.1            | FortyFiveDown | ↘     |
| ≤ +1.1            | Flat          | →     |
| ≤ +2.0            | FortyFiveUp   | ↗     |
| ≤ +3.0            | SingleUp      | ↑     |
| > +3.0            | DoubleUp      | ⇈     |

### Edge Cases

- **Fewer than 3 readings (app just started):** The 3-point average degrades gracefully — clamp indices to array bounds. With 1 reading, the "average" is just that reading. With 2 readings, average 2 instead of 3.
- **No reading within 10 minutes:** Direction = `"NONE"`, arrow = `"?"`. This handles sensor warmup, CamAPS FX restarts, or data gaps.
- **First reading after a gap:** Delta is null, direction is `"NONE"`. The UI shows `"?"` for the arrow and omits the delta text.

### Delta Computation

Delta (the "change" value shown in the UI, e.g., "-1.1 mmol/l") is the difference between the current 3-point averaged sgv and the 3-point averaged sgv from ~5 minutes ago, converted to mmol/L. When no 5-minute-ago reading exists, delta is null and omitted from the display.

---

## 6. Data Output: Nightscout Push

### Endpoint

`POST /api/v1/entries` (standard Nightscout API)

### Authentication

Header: `api-secret` — SHA-1 hash of the configured API secret.

```kotlin
val hashedSecret = MessageDigest.getInstance("SHA-1")
    .digest(secret.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
```

### Request Body

Nightscout-compatible JSON array:

```json
[
  {
    "sgv": 243,
    "date": 1710700000000,
    "dateString": "2026-03-17T14:30:00.000Z",
    "direction": "SingleUp",
    "type": "sgv",
    "device": "Strimma"
  }
]
```

Fields:

- `sgv` — Integer, mg/dL (even though display is mmol/L — Nightscout protocol uses mg/dL)
- `date` — Epoch milliseconds (from the Aidex broadcast timestamp)
- `dateString` — ISO 8601 formatted timestamp
- `direction` — Recomputed by Strimma (§5), NOT from Aidex broadcast
- `device` — `"Strimma"` (identifies this app as the source)

### Push Strategy

1. On each new reading, fire HTTP POST immediately — zero delay. The reading must reach Nightscout (and therefore any downstream consumers) within seconds of arriving from CamAPS FX.
2. If push fails, retry with linear backoff (5s, 10s, 15s, 20s, ...) up to 60s max interval
3. Failed readings are marked `pushed = 0` in Room DB — survives app restart
4. On app start / connectivity restore, push all pending readings in chronological batch (up to 100 per request)

### Offline Resilience

If the device is offline, readings accumulate in the local DB with `pushed = 0`. When connectivity returns, all pending readings are pushed in chronological batches. Nightscout's deduplication handles any edge-case duplicates.

---

## 7. Local Storage

### Room Database

**Database name:** `strimma.db`

**Table: `readings`**

| Column       | Type      | Description                        |
| ------------ | --------- | ---------------------------------- |
| `ts`         | `INTEGER` | Timestamp ms (PRIMARY KEY)         |
| `sgv`        | `INTEGER` | Glucose in mg/dL                   |
| `mmol`       | `REAL`    | Glucose in mmol/L                  |
| `direction`  | `TEXT`    | Computed direction string          |
| `delta_mmol` | `REAL`    | 5-min delta in mmol/L (nullable)   |
| `pushed`     | `INTEGER` | 0 = pending, 1 = pushed to Nightscout |

No sensor table. Sensor lifecycle is managed entirely by CamAPS FX.

### Retention

Keep 30 days of readings locally. Older readings are pruned daily. Nightscout is the long-term store.

### Queries

The app needs fast access to:

- Latest reading (for notification/UI update)
- Last N readings within time window (for graph rendering)
- Last 3 readings (for direction computation)
- Unpushed readings (for Nightscout sync)

All queries are indexed by `ts DESC`.

---

## 8. Foreground Service & Lifecycle

### Why a Foreground Service

The app must:

1. Receive glucose readings at any time (CamAPS FX updates its notification every ~1 minute with Libre 3, 24/7)
2. Push to Nightscout immediately after receiving a reading (zero delay)
3. Update the notification with the latest reading

On Android 16 / API 36 (Pixel 9 Pro), a foreground service with a persistent notification is the correct mechanism. The notification itself is the user-facing value — it shows the glucose graph.

### Service Configuration

```xml
<service
    android:name=".service.StrimmaService"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Continuous glucose monitor display — receives medical glucose readings and must maintain persistent notification with real-time graph" />
</service>

<service
    android:name=".receiver.GlucoseNotificationListener"
    android:exported="true"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

> **Note (2026-03-17):** Two services instead of one. `StrimmaService` is the foreground service that owns the notification and processes readings. `GlucoseNotificationListener` is a `NotificationListenerService` that reads CamAPS FX's notification and forwards parsed glucose values to `StrimmaService` via `startForegroundService()`. The listener requires "Notification access" permission granted in Android system settings.

`foregroundServiceType="specialUse"` because Strimma doesn't directly connect to any device via BLE/USB (which `connectedDevice` requires). The `specialUse` type is appropriate for medical monitoring apps that need persistent execution. xDrip+ uses `connectedDevice` because it actually does BLE — Strimma reads notifications, so `connectedDevice` would be rejected by the system on API 34+.

### Lifecycle

- **Boot:** Auto-start via `RECEIVE_BOOT_COMPLETED` broadcast receiver
- **Running:** Foreground service with persistent notification (cannot be swiped away)
- **Battery:** Request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to prevent doze from killing the service
- **Crash recovery:** If the service dies, `START_STICKY` ensures Android restarts it

### Permissions

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.INTERNET" />
<!-- Aidex permission removed — not used. Notification access granted via system settings instead. -->
```

---

## 9. Notification Display

### Notification Channel

```kotlin
NotificationChannel(
    "strimma_glucose",
    "Glucose Readings",
    NotificationManager.IMPORTANCE_LOW  // no sound, persistent
)
```

`IMPORTANCE_LOW` prevents the notification from making sounds on every update (every ~1 min) while keeping it visible in the shade.

### Status Bar Small Icon

The BG number rendered as a small icon bitmap. On each reading update:

1. Create a `Bitmap` at the status bar icon size (24x24 dp)
2. Draw the mmol/L value as text (e.g., "13.5") fitted to the bitmap
3. Color: white (standard status bar icon color)
4. Set as `setSmallIcon()` via `IconCompat.createWithBitmap()`

### Collapsed Notification (Default View)

```
┌─────────────────────────────────────────────┐
│ [icon] 13.5 ↘                        [▼]   │
│        Delta: -1.1 mmol/l                   │
│        ┌─────────────────────────────┐      │
│        │  ~~ mini graph ~~           │      │
│        └─────────────────────────────┘      │
└─────────────────────────────────────────────┘
```

Uses `RemoteViews` with a custom layout. The mini graph is a rendered bitmap (approximately 400x80 dp) showing the last 1 hour with threshold lines. Both collapsed and expanded notification graphs show the same 1-hour time window (configurable in phase 2).

### Expanded Notification (Big View)

```
┌─────────────────────────────────────────────┐
│ [icon] Strimma · now                  [▲]   │
│                                             │
│ 13.5 ↘                                     │
│ Delta: -1.1 mmol/l                          │
│                                             │
│   16 ┬─────────────────────────────         │
│      │               ·····                  │
│   12 │          ····                        │
│  ════╪══════════════════════════ 10.0       │
│    8 │  ····                                │
│  ────┼─────────────────────────── 4.0       │
│      └────────────┬──────┬─────             │
│                  14:00  14:30               │
└─────────────────────────────────────────────┘
```

Uses `NotificationCompat.DecoratedCustomViewStyle()` with a custom `RemoteViews` layout. The graph is a rendered bitmap (approximately 400x200 dp) showing the same 1-hour time window as the collapsed view.

### Graph Rendering for Notifications

The graph rendering logic (dot placement, threshold lines, coloring, axis labels) is shared between notifications and the main app UI. The core algorithm is implemented once in a shared module that can render to either a `Canvas` (for notification bitmaps) or a Compose `DrawScope` (for the in-app graph).

Rendered to `Bitmap` using `Canvas` API:

1. Query readings for the last 1 hour (notification graphs) or the configured time window (main app graph, default 4-5 hours)
2. Calculate Y axis: dynamically expand to fit all visible data points, with `bgLow - 0.5` and `bgHigh + 0.5` as the minimum range
3. Draw threshold lines: dashed red at `bgLow`, dashed orange at `bgHigh`
4. Draw dots: blue if `bgLow ≤ mmol ≤ bgHigh`, yellow/orange if above `bgHigh`, red if below `bgLow`
5. Draw connecting lines between consecutive dots (same color as the dot)
6. Draw X axis time labels (hour markers)
7. Dark background (#121212) matching the notification shade

### Update Frequency

The notification updates every time a new reading arrives from CamAPS FX (~every 1 minute with Libre 3). The graph bitmap is re-rendered on each update.

---

## 10. Main App UI

### Technology

Jetpack Compose with Material 3. Dark theme only (phase 1).

### Layout

```
┌──────────────────────────────────────┐
│  ☰  Strimma                    ⚙️   │  ← toolbar
├──────────────────────────────────────┤
│                                      │
│  0 Minutes ago              13.5 ↘  │  ← header
│  -1.1 mmol/l                         │
│                                      │
│  ┌──────────────────────────────┐    │
│  │                              │    │  ← main graph
│  │   interactive glucose graph  │    │  (pinch zoom,
│  │   4-5 hours default          │    │   scroll H+V)
│  │                              │    │
│  │   blue dots = in range       │    │
│  │   yellow dots = above high   │    │
│  │   red dots = below low       │    │
│  │                              │    │
│  │   dashed lines at thresholds │    │
│  │                              │    │
│  └──────────────────────────────┘    │
│                                      │
└──────────────────────────────────────┘
```

### Header

- **Time since last reading:** "0 Minutes ago", "3 Minutes ago", updates every minute. If no reading for > 10 minutes, text turns red as a staleness warning.
- **BG value:** Large text (48sp), colored by range (blue in range, yellow/orange above high, red below low). If data is stale (> 10 min), the value grays out.
- **Trend arrow:** Unicode arrow matching direction. `"?"` if direction is `"NONE"`.
- **Delta:** Formatted as `"-1.1 mmol/l"` or `"+0.5 mmol/l"`. Omitted if delta is null (first reading, data gap).

### Graph

- **Library:** Custom Compose Canvas drawing (no external chart library needed for dot + line graphs)
- **Default window:** 4-5 hours, configurable in settings
- **Interaction:** Pinch to zoom (both axes), horizontal scroll (pan through time), vertical scroll (pan through glucose range)
- **Dot coloring:**
  - Blue (#4FC3F7): `bgLow ≤ mmol ≤ bgHigh`
  - Yellow/Orange (#FFB74D): `mmol > bgHigh`
  - Red (#EF5350): `mmol < bgLow`
- **Threshold lines:** Dashed horizontal lines at `bgLow` and `bgHigh`
- **Connecting lines:** Thin lines between consecutive dots, same color as the leading dot
- **X axis:** Time labels at hour boundaries
- **Y axis:** mmol/L labels at integer values
- **Background:** Dark (#121212)

### Navigation

Single-screen app in phase 1. Settings accessible via gear icon in toolbar. No drawer, no bottom nav.

---

## 11. Settings

### Phase 1 Settings Screen

| Setting           | Type          | Default     | Description                                   |
| ----------------- | ------------- | ----------- | --------------------------------------------- |
| Nightscout URL    | Text          | (empty)     | Base URL for Nightscout server                |
| API Secret        | Text (masked) | (empty)     | Shared secret for Nightscout authentication   |
| Graph Window      | Slider        | 4 hours     | Time range for graph display (1-8 hours)      |
| BG Low Threshold  | Number        | 4.0 mmol/L  | Below this = red dots, low threshold line     |
| BG High Threshold | Number        | 10.0 mmol/L | Above this = yellow dots, high threshold line |

### Storage

Non-sensitive settings (thresholds, graph window) stored in Jetpack `DataStore` (Preferences). The API secret stored in `EncryptedSharedPreferences` (from `androidx.security.crypto`, backed by Android Keystore). This is the only sensitive field — no reason to encrypt threshold values.

### Phase 2 Settings (Specced, Not Built)

- Alert thresholds and sounds
- Unit switching (mmol/L ↔ mg/dL)
- Notification style options
- Graph appearance (dot size, line thickness)
- Data retention period
- Export/backup

---

## 12. Side-by-Side Validation with xDrip

### How It Works

1. Both apps are installed simultaneously on the same Pixel 9 Pro
2. CamAPS FX sends Aidex broadcasts — Android delivers to both receivers
3. xDrip pushes to `POST /api/v1/entries` → `xdrip_readings` table (existing)
4. Strimma pushes to `POST /api/v1/strimma/entries` → `strimma_readings` table (new)
5. Both datasets accumulate independently in the Nightscout-compatible backend

### What to Compare

| Metric                 | How to Validate                                                                           |
| ---------------------- | ----------------------------------------------------------------------------------------- |
| **Completeness**       | Count readings per hour in both tables — should be identical                              |
| **Timing**             | Compare timestamps — both should receive within seconds of each other                     |
| **Direction accuracy** | Compare direction fields — both should match the independently recomputed direction       |
| **Push reliability**   | Check for gaps — Strimma should have zero gaps over 24+ hours                             |
| **Graph quality**      | Visual comparison — Strimma's graph should look as good or better than xDrip's            |

### Transition Criteria

Strimma is ready for standalone use (xDrip+ no longer needed for this setup) when:

1. Zero gaps over 72 continuous hours
2. Direction matches server-side recomputation > 98% of the time
3. Push latency (reading timestamp to Nightscout receipt) is ≤ 10 seconds
4. Notification and graph render correctly with no crashes

### Switching Over

When validation criteria are met:

1. Update the backend's `/api/sgv` endpoint to read from `strimma_readings` instead of `xdrip_readings`
2. xDrip+ is no longer needed for this specific setup
3. Keep `xdrip_readings` table for historical data

---

## 13. Original Backend Changes (Historical)

> **Note:** This section describes changes made to the original development backend ("Springa") during the side-by-side validation phase. It is not relevant to generic Nightscout setups. Strimma now pushes to the standard Nightscout `/api/v1/entries` endpoint and works with any Nightscout-compatible server.

### New Table

```sql
CREATE TABLE strimma_readings (
    email TEXT NOT NULL,
    ts INTEGER NOT NULL,
    mmol REAL NOT NULL,
    sgv INTEGER NOT NULL,
    direction TEXT NOT NULL,
    PRIMARY KEY (email, ts)
);
```

Identical schema to `xdrip_readings`. Same monthly sharding strategy.

### New Endpoint: POST /api/v1/strimma/entries

Identical logic to the existing `POST /api/v1/entries` but writes to `strimma_readings`:

1. Validate `api-secret` header (same secret)
2. Parse Nightscout-format entries
3. Recompute direction from stored `strimma_readings` (same algorithm)
4. `INSERT OR REPLACE` into `strimma_readings`
5. Return `{ ok: true, count: N }`

Note: The original backend still recomputes direction on its side as a safety net, even though Strimma now sends correct directions. Belt and suspenders — the two computations should agree. Any disagreement is a bug to investigate.

### Modified Endpoint: GET /api/sgv

Add a query parameter or config flag to select the source table:

```
GET /api/sgv?source=strimma  → reads from strimma_readings
GET /api/sgv                 → reads from xdrip_readings (default, unchanged)
```

When Strimma is validated, flip the default. When Strimma is the sole data source, the `source` parameter can be removed and `strimma_readings` becomes the primary table.

---

## 14. Tech Stack

### Android

**Package name:** `com.psjostrom.strimma`

| Component     | Choice                                         | Rationale                                                            |
| ------------- | ---------------------------------------------- | -------------------------------------------------------------------- |
| Language      | Kotlin                                         | Modern, concise, coroutine-native                                    |
| UI            | Jetpack Compose + Material 3                   | Current Android UI standard, dark/light theme built-in               |
| Database      | Room                                           | Type-safe, migration-aware, Flow integration                         |
| Networking    | Ktor Client                                    | Kotlin-native, coroutine-based, no OkHttp legacy                     |
| DI            | Hilt                                           | Standard for single-module Android apps                              |
| Concurrency   | Kotlin Coroutines + Flow                       | Structured concurrency, lifecycle-aware                              |
| Settings      | Jetpack DataStore + EncryptedSharedPreferences | DataStore for prefs, EncryptedSharedPreferences for API secret       |
| Graph         | Custom Compose Canvas + shared GraphColors     | Dot+line+prediction graphs, shared color/range logic                 |
| Widget        | Jetpack Glance                                 | Compose-based widget API with AppWidgetManager updates               |
| Notifications | NotificationCompat + RemoteViews               | Custom layouts with bitmap graphs                                    |
| Alerts        | 5 notification channels                        | Per-alarm sound/vibration/DND via Android channel settings           |
| Testing       | JUnit 4 + Robolectric 4.16 + Room in-memory    | Unit + integration on JVM, SDK 36, no emulator                       |
| Java          | 21 (Zulu)                                      | Repo-specific via `gradle.properties`, needed for Robolectric SDK 36 |
| Build         | Gradle 8.x + AGP 8.x                           | Current                                                              |
| compileSdk    | 36                                             | Android 16                                                           |
| targetSdk     | 36                                             | Android 16                                                           |
| minSdk        | 33                                             | Android 13 — oldest version still receiving security updates         |

### Backend (Historical)

The original development backend required these changes (see §13 for details):

- 1 new Turso table
- 2 new API routes (copy of existing with table name change)
- 1 modified API route (source param on `/api/sgv`)

For generic Nightscout setups, no backend changes are needed — Strimma uses the standard `/api/v1/entries` API.

---

## 15. Phase 2 Roadmap

### Delivered

| Feature                 | Description                                                                                                                                                              |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Alerts**              | 5 alert types (urgent low, low, high, urgent high, stale) with per-alarm notification channels, configurable thresholds, persistent snooze, DND bypass for urgent alerts |
| **Prediction**          | Least-squares curve fitting (linear + quadratic) on last 12 min of readings, inspired by xDrip+. Picks best model by residual error. Renders curved prediction line on main graph and notification. "Low in X min" / "High in X min" warning shown in BG header when prediction crosses thresholds. |
| **Home screen widget**  | Jetpack Glance widget with BG, arrow, delta, mini graph, configurable opacity                                                                                            |
| **Statistics**          | TIR, GMI, average glucose, CV%, coverage — with period selector (24h/7d/14d/30d) and CSV export                                                                          |
| **Theme**               | Dark / Light / System picker with theme-adaptive graph surfaces                                                                                                          |
| **Persistent logging**  | File-based debug logs with 7-day rotation, shareable via FileProvider                                                                                                    |
| **Settings debounce**   | URL and API secret save on field blur instead of every keystroke                                                                                                         |
| **Graph consolidation** | Shared `graph/GraphColors.kt` module, compact top gradient for widget readability                                                                                        |
| **Testing**             | 77 tests: 57 unit (DirectionComputer, GlucoseParser, GraphColors, SecretHash, GlucoseUnit) + 20 integration (Room DAO, reading pipeline via Robolectric 4.16)           |
| **Unit switching**      | mmol/L ↔ mg/dL toggle via `GlucoseUnit` enum. Internal storage stays mmol/L, conversion at display time. Affects all display surfaces.                                   |
| **BG broadcast**        | xDrip-compatible `com.eveningoutpost.dexdrip.BgEstimate` intent emitted on each reading. Settings > Integration toggle, off by default. Verified with GDH.               |

### Remaining

Phase 2 is complete. See `docs/strimma-p2-roadmap.md` for Phase 3 (open-source) roadmap.

---

## 16. Phase 1 Non-Goals

These are out of scope for phase 1. Several are planned for later phases — see the open-source roadmap in `docs/strimma-p2-roadmap.md`.

- **BLE CGM collection** — Phase 1 uses CamAPS FX notification listener. Direct BLE planned for later phases.
- **Calibration** — Not applicable for factory-calibrated Libre 3. Planned for sensors that need it.
- **Treatment tracking** — Low priority. Insulin/carb management typically handled by AAPS or pen apps.
- **Nightscout download** — Strimma pushes to Nightscout but does not yet download or sync from it.
- **Watch integration** — Watches read from Nightscout directly. Wear OS complications planned.
- **Broader device support** — minSdk lowered to 33 (Android 13, oldest with security updates). Medical data warrants this floor.
