# Strimma — Spec v1

**Date:** 2026-03-17
**Status:** Draft
**Author:** Steg

Strimma is a new Android app that replaces xDrip+ for CGM monitoring. It receives glucose data from CamAPS FX via the Aidex broadcast protocol, displays it with notifications and an in-app graph, and pushes readings to Springa.

---

## Table of Contents

1. [Context & Rationale](#1-context--rationale)
2. [System Architecture](#2-system-architecture)
3. [Phase 1 Scope](#3-phase-1-scope)
4. [Data Input: CamAPS Notification Listener](#4-data-input-aidex-broadcast-receiver-camaps-notification-listener)
5. [Direction Computation](#5-direction-computation)
6. [Data Output: Springa Push](#6-data-output-springa-push)
7. [Local Storage](#7-local-storage)
8. [Foreground Service & Lifecycle](#8-foreground-service--lifecycle)
9. [Notification Display](#9-notification-display)
10. [Main App UI](#10-main-app-ui)
11. [Settings](#11-settings)
12. [Side-by-Side Validation with xDrip](#12-side-by-side-validation-with-xdrip)
13. [Springa Changes](#13-springa-changes)
14. [Tech Stack](#14-tech-stack)
15. [Phase 2 Roadmap](#15-phase-2-roadmap)
16. [Non-Goals](#16-non-goals)

---

## 1. Context & Rationale

### Why not modernize xDrip+?

xDrip+ is a 10+ year old codebase with 976 Java files, 3,880-line god classes, dual OkHttp versions, RxJava 1.x (EOL), targetSdk 24 (Android 7, 2016), and no architectural pattern. The full modernization spec (`Documentation/technical/modernization-spec.md`) details every issue.

For this use case — a single user with a Libre 3 sensor connected to CamAPS FX — xDrip+ provides approximately 5% of its feature surface:

- Receive glucose broadcasts from CamAPS FX (AidexReceiver)
- Display glucose + trend + graph
- Push readings to Springa (Nightscout-compatible REST API)

The other 95% (15 CGM plugins, 5 watch platforms, pump integration, calibration engine, multi-user support, Tidepool sync, etc.) is unused dead weight.

Starting fresh with modern Android (Kotlin, Compose, Room, Coroutines) for this narrow scope is less work than fixing xDrip+'s mixed-case package names alone.

### Why it must run side-by-side with xDrip first

Strimma handles medical data depended on 24/7. Before xDrip can be decommissioned:

1. Both apps run simultaneously, receiving the same CamAPS FX broadcasts
2. Both push to Springa — into **separate tables** so data can be compared
3. Validate: graph accuracy, data completeness, push reliability, direction computation quality
4. Only after Strimma proves 100% reliable does xDrip get uninstalled

---

## 2. System Architecture

```
Libre 3 sensor
  │ (BLE)
  ▼
CamAPS FX (closed-loop pump control)
  │ (notification with glucose value)
  ▼
┌──────────────────────────────────────────────┐
│  Android                                      │
│                                               │
│  ┌─────────┐     ┌─────────┐                 │
│  │ xDrip+  │     │ Strimma │  (side by side) │
│  │         │     │         │                  │
│  │ Aidex   │     │ Notif.  │                  │
│  │ Receiver│     │ Listener│                  │
│  └────┬────┘     └────┬────┘                  │
│       │               │                       │
│       ▼               ▼                       │
│  POST /api/v1/   POST /api/v1/               │
│  entries         strimma/entries              │
│       │               │                       │
└───────┼───────────────┼───────────────────────┘
        │               │
        ▼               ▼
┌──────────────────────────────────────┐
│  Springa (Next.js + Turso)           │
│                                      │
│  xdrip_readings    strimma_readings  │
│  (existing)        (new, identical)  │
│                                      │
│  GET /api/sgv ◄── reads from         │
│                   configurable table │
└──────────┬───────────────────────────┘
           │
           ▼
    SugarWave / SugarRun
    (Garmin, unchanged)
```

xDrip+ receives CamAPS FX data via Aidex broadcast intents. Strimma uses a `NotificationListenerService` to parse glucose values from CamAPS FX's ongoing notification — the Aidex broadcast approach did not work (see §4). Both apps receive every reading independently.

---

## 3. Phase 1 Scope

### In Scope

| Feature | Description |
|---------|-------------|
| CamAPS notification listener | Receive glucose data from CamAPS FX (via NotificationListenerService) |
| Direction computation | 3-point averaged sgv, EASD/ISPAD thresholds (same algorithm as Springa) |
| Local Room DB | Store readings locally |
| Springa push | POST to `/api/v1/strimma/entries` (separate from xDrip) |
| Foreground service | Persistent process with notification |
| Expanded notification | BG number + trend arrow + delta + graph bitmap |
| Collapsed notification | BG number + delta + mini graph bitmap |
| Status bar icon | BG number rendered as notification small icon |
| Main app | Large BG number + trend arrow + delta + interactive graph |
| Graph coloring | Blue dots = in range, yellow/orange dots = above high, red dots = below low |
| Threshold lines | Dashed horizontal lines at low (4.0) and high (10.0) thresholds |
| Settings screen | Springa URL, API secret, graph time window, threshold values |

### Out of Scope (Phase 2)

Low/high glucose alerts, 24h overview timeline, home screen widget, prediction/extrapolation, unit switching (mg/dL), BLE direct CGM collection, treatment tracking, multi-user, watch sync.

---

## 4. Data Input: ~~Aidex Broadcast Receiver~~ CamAPS Notification Listener

> **Note (2026-03-17):** The Aidex broadcast approach described below did not work — CamAPS FX does not actually send the Aidex broadcast intents reliably (or at all) on modern Android. Instead, Strimma reads glucose data by parsing CamAPS FX's notification via a `NotificationListenerService`. The notification contains the BG value and trend arrow, which Strimma extracts on each notification update. The rest of the spec (direction computation, push strategy, storage, etc.) remains unchanged — only the data input mechanism differs.

### ~~How Companion Mode Works~~ (original spec, kept for reference)

Strimma does NOT connect to the Libre 3 sensor directly. The Libre 3 bonds exclusively to CamAPS FX via BLE — only one app can hold that bond. Dual-connection is not possible with current Libre 3 firmware.

Instead, Strimma receives glucose data via broadcast intents that CamAPS FX sends using the Aidex protocol. This is the same "companion mode" hack that xDrip+ uses. CamAPS FX broadcasts every reading it receives from the sensor, and any app with the correct intent filter and permission picks it up.

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

**Why not a manifest receiver?** xDrip+ gets away with a manifest-registered receiver because its `targetSdk` is 24 (pre-API 26 restriction). Strimma targets API 36, where this restriction is enforced. Dynamic registration from a foreground service is the correct modern approach — the service keeps the process alive, and the receiver stays registered as long as the service runs.

### Broadcast Bundle Fields

Extracted from xDrip's `AidexBroadcastIntents.java`:

| Extra Key | Type | Description |
|-----------|------|-------------|
| `com.microtechmd.cgms.aidex.Time` | `long` | Reading timestamp (epoch ms) |
| `com.microtechmd.cgms.aidex.BgType` | `String` | `"mg/dl"` or `"mmol/l"` |
| `com.microtechmd.cgms.aidex.BgValue` | `double` | Glucose value |
| `com.microtechmd.cgms.aidex.BgSlopeName` | `String` | Trend direction (NOT TRUSTED — see §5) |
| `com.microtechmd.cgms.aidex.SensorId` | `String` | Sensor identifier (logged but not used) |

### Processing Rules

1. **Validate required fields:** timestamp, BG type, and BG value must all be present
2. **Unit conversion:** If BG type is `"mmol/l"`, multiply by 18.0182 to get mg/dL
3. **Reject invalid values:** BG value must be > 0
4. **Deduplicate:** If a reading with the exact same timestamp already exists, discard. Libre 3 sends readings every ~1 minute, so the dedup window is by exact timestamp match, not a time range.
5. **Compute direction:** Do NOT use the `BgSlopeName` from the broadcast — compute from stored readings (§5)
6. **Store locally:** Insert into Room DB
7. **Push to Springa:** Fire HTTP request immediately (§6) — zero delay, the data must reach the watch ASAP

---

## 5. Direction Computation

xDrip+ companion mode returns stale/wrong direction fields ~31% of the time (documented in Springa's `lib/xdrip.ts`). Strimma computes direction locally and is the source of truth.

### Algorithm

Identical to Springa's `recomputeDirections()` (`/Users/persjo/code/private/Springa/lib/xdrip.ts` lines 103-140):

1. For each new reading, find the stored reading closest to **5 minutes** before it
2. If no reading exists within 10 minutes, direction = `"NONE"`
3. Compute 3-point averaged sgv for both readings: `avg(readings[i-1], readings[i], readings[i+1])`
4. Calculate `deltaMgdlPerMin = (avgSgvNow - avgSgvPast) / timeMinutes`
5. Map delta to direction using **EASD/ISPAD 2020 thresholds** (Moser et al., Diabetologia 2020):

| Delta (mg/dL/min) | Direction | Arrow |
|-------------------|-----------|-------|
| ≤ -3.0 | DoubleDown | ⇊ |
| ≤ -2.0 | SingleDown | ↓ |
| ≤ -1.1 | FortyFiveDown | ↘ |
| ≤ +1.1 | Flat | → |
| ≤ +2.0 | FortyFiveUp | ↗ |
| ≤ +3.0 | SingleUp | ↑ |
| > +3.0 | DoubleUp | ⇈ |

### Edge Cases

- **Fewer than 3 readings (app just started):** The 3-point average degrades gracefully — clamp indices to array bounds. With 1 reading, the "average" is just that reading. With 2 readings, average 2 instead of 3. Same as Springa's `avgSgv()` implementation.
- **No reading within 10 minutes:** Direction = `"NONE"`, arrow = `"?"`. This handles sensor warmup, CamAPS FX restarts, or data gaps.
- **First reading after a gap:** Delta is null, direction is `"NONE"`. The UI shows `"?"` for the arrow and omits the delta text.

### Delta Computation

Delta (the "change" value shown in the UI, e.g., "-1.1 mmol/l") is the difference between the current 3-point averaged sgv and the 3-point averaged sgv from ~5 minutes ago, converted to mmol/L. When no 5-minute-ago reading exists, delta is null and omitted from the display.

---

## 6. Data Output: Springa Push

### Endpoint

`POST /api/v1/strimma/entries` (new endpoint, see §13)

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

1. On each new reading, fire HTTP POST immediately — zero delay. The reading must reach Springa (and therefore the watch) within seconds of arriving from CamAPS FX.
2. If push fails, retry with linear backoff (5s, 10s, 15s, 20s, ...) up to 60s max interval
3. Failed readings are marked `pushed = 0` in Room DB — survives app restart
4. On app start / connectivity restore, push all pending readings in chronological batch (up to 100 per request)

### Offline Resilience

If the device is offline, readings accumulate in the local DB with `pushed = 0`. When connectivity returns, all pending readings are pushed in chronological batches. Springa's `INSERT OR REPLACE` handles any edge-case duplicates.

---

## 7. Local Storage

### Room Database

**Database name:** `strimma.db`

**Table: `readings`**

| Column | Type | Description |
|--------|------|-------------|
| `ts` | `INTEGER` | Timestamp ms (PRIMARY KEY) |
| `sgv` | `INTEGER` | Glucose in mg/dL |
| `mmol` | `REAL` | Glucose in mmol/L |
| `direction` | `TEXT` | Computed direction string |
| `delta_mmol` | `REAL` | 5-min delta in mmol/L (nullable) |
| `pushed` | `INTEGER` | 0 = pending, 1 = pushed to Springa |

No sensor table. Sensor lifecycle is managed entirely by CamAPS FX.

### Retention

Keep 30 days of readings locally. Older readings are pruned daily. Springa is the long-term store.

### Queries

The app needs fast access to:
- Latest reading (for notification/UI update)
- Last N readings within time window (for graph rendering)
- Last 3 readings (for direction computation)
- Unpushed readings (for Springa sync)

All queries are indexed by `ts DESC`.

---

## 8. Foreground Service & Lifecycle

### Why a Foreground Service

The app must:
1. Receive glucose readings at any time (CamAPS FX updates its notification every ~1 minute with Libre 3, 24/7)
2. Push to Springa immediately after receiving a reading (zero delay)
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

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| Springa URL | Text | (empty) | Base URL for Springa API |
| API Secret | Text (masked) | (empty) | Shared secret for Springa authentication |
| Graph Window | Slider | 4 hours | Time range for graph display (1-8 hours) |
| BG Low Threshold | Number | 4.0 mmol/L | Below this = red dots, low threshold line |
| BG High Threshold | Number | 10.0 mmol/L | Above this = yellow dots, high threshold line |

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
5. Both datasets accumulate independently in Springa's Turso DB

### What to Compare

| Metric | How to Validate |
|--------|----------------|
| **Completeness** | Count readings per hour in both tables — should be identical |
| **Timing** | Compare timestamps — both should receive within seconds of each other |
| **Direction accuracy** | Compare direction fields — Strimma should match Springa's recomputed direction more closely than xDrip's stale direction |
| **Push reliability** | Check for gaps — Strimma should have zero gaps over 24+ hours |
| **Graph quality** | Visual comparison — Strimma's graph should look as good or better than xDrip's |

### Validation Endpoint

Springa gets a comparison endpoint (phase 1):

`GET /api/debug/compare-readings?hours=24`

Returns:
```json
{
  "xdrip": { "count": 1440, "gaps": 0, "direction_mismatches": 446 },
  "strimma": { "count": 1440, "gaps": 0, "direction_mismatches": 2 },
  "missing_in_strimma": [],
  "missing_in_xdrip": []
}
```

### Transition Criteria

Strimma is ready to replace xDrip when:
1. Zero gaps over 72 continuous hours
2. Direction matches Springa's recomputation > 98% of the time
3. Push latency (reading timestamp to Springa receipt) is ≤ 10 seconds
4. Notification and graph render correctly with no crashes

### Switching Over

When ready:
1. Update Springa's `/api/sgv` endpoint to read from `strimma_readings` instead of `xdrip_readings`
2. Uninstall xDrip
3. Keep `xdrip_readings` table for historical data

---

## 13. Springa Changes

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

Note: Springa still recomputes direction on its side as a safety net, even though Strimma now sends correct directions. Belt and suspenders — the two computations should agree. Any disagreement is a bug to investigate.

### New Endpoint: GET /api/debug/compare-readings

See §12. Compares `xdrip_readings` vs `strimma_readings` for the authenticated user over a configurable time window.

### Modified Endpoint: GET /api/sgv

Add a query parameter or config flag to select the source table:

```
GET /api/sgv?source=strimma  → reads from strimma_readings
GET /api/sgv                 → reads from xdrip_readings (default, unchanged)
```

When Strimma is validated, flip the default. When xDrip is uninstalled, the `source` parameter can be removed and `strimma_readings` becomes the only table.

---

## 14. Tech Stack

### Android

**Package name:** `com.psjostrom.strimma`

| Component | Choice | Rationale |
|-----------|--------|-----------|
| Language | Kotlin | Modern, concise, coroutine-native |
| UI | Jetpack Compose + Material 3 | Current Android UI standard, dark theme built-in |
| Database | Room | Type-safe, migration-aware, LiveData/Flow integration |
| Networking | Ktor Client | Kotlin-native, coroutine-based, no OkHttp legacy |
| DI | Hilt | Standard for single-module Android apps |
| Concurrency | Kotlin Coroutines + Flow | Structured concurrency, lifecycle-aware |
| Settings | Jetpack DataStore + EncryptedSharedPreferences | DataStore for prefs, EncryptedSharedPreferences for API secret |
| Graph | Custom Compose Canvas | No external lib needed for dot+line graphs |
| Notifications | NotificationCompat + RemoteViews | Custom layouts with bitmap graphs |
| Build | Gradle 8.x + AGP 8.x | Current |
| compileSdk | 36 | Android 16 |
| targetSdk | 36 | Android 16 — no CGM BLE collection, so no reason for low target |
| minSdk | 36 | Single device (Pixel 9 Pro, Android 16) |

### Springa

No new dependencies. Changes are:
- 1 new Turso table
- 2 new API routes (copy of existing with table name change)
- 1 modified API route (source param on `/api/sgv`)

---

## 15. Phase 2 Roadmap

Specced here for future reference. Not built in phase 1.

| Feature | Description |
|---------|-------------|
| **Alerts** | Configurable low/high glucose alarms with sound, vibration, and snooze |
| **24h timeline** | Compressed overview graph at bottom of main screen (like xDrip screenshot 3) |
| **Prediction** | Linear extrapolation from recent trend, shown as faded dots on graph |
| **Home screen widget** | Glanceable glucose display without opening the app |
| **Unit switching** | mmol/L ↔ mg/dL toggle |
| **BG broadcast** | `com.eveningoutpost.dexdrip.BgEstimate` intent for external app compat |
| **Settings expansion** | Graph appearance, notification options, data retention, export |

---

## 16. Non-Goals

These are explicitly out of scope for all phases:

- **BLE CGM collection** — Libre 3 bonds exclusively to CamAPS FX; dual-connection is not possible with current firmware
- **Calibration** — Not applicable for factory-calibrated Libre 3
- **Treatment tracking** — Insulin/carb management is done elsewhere
- **Watch sync** — SugarWave/SugarRun talk to Springa directly
- **Multi-user** — Single user app
- **Cloud sync** — No Nightscout, Tidepool, MongoDB, InfluxDB upload. Only Springa.
- **Multi-device** — Pixel 9 Pro only
- **Play Store distribution** — APK sideload only
