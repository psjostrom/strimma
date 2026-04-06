# Nightscout Follower Mode

**Date:** 2026-03-18
**Status:** Implemented

---

## Summary

New `NIGHTSCOUT_FOLLOWER` glucose source that polls a Nightscout server's `/api/v1/entries` endpoint, stores readings in Room, and processes them through the existing pipeline. Read-only — no Nightscout push. Direction and delta computed locally via `DirectionComputer` (EASD/ISPAD thresholds), same as all other sources.

## Use Case

A parent, partner, or caregiver follows someone else's Nightscout server. They get the full Strimma experience — graph, notification, widget, alerts, BG broadcast — without a local CGM.

## Data Flow

```
Remote Nightscout server
  → NightscoutFollower (polls /api/v1/entries.json)
  → DirectionComputer (EASD/ISPAD, 3-point averaged SGV)
  → Room DB (pushed=1)
  → onNewReading callback → NotificationHelper, AlertManager, Widget, BG Broadcast
```

`NightscoutFollower` owns its own reading pipeline — it does NOT go through `StrimmaService.processReading()`. This avoids the `pusher.pushReading()` call in `processReading()` and allows backfill to insert silently (no notification storm). The service provides an `onNewReading` callback for post-insert UI updates.

Server-provided direction is ignored — Strimma always computes direction and delta locally via EASD/ISPAD thresholds.

## Nightscout API

### Endpoint

`GET /api/v1/entries.json`

### Query Parameters

- `find[date][$gt]={epochMs}` — entries newer than timestamp
- `count={n}` — max entries to return (default 10, we override)
- `sort$desc=date` — newest first (for backfill pagination if needed)

### Authentication

`api-secret` header with SHA-1 hashed secret. Same mechanism as existing push.

### Response Fields Used

| Field | Type | Usage |
|-------|------|-------|
| `sgv` | number | Glucose in mg/dL. Convert to mmol/L via `/18.0182` |
| `date` | number | Epoch milliseconds. Used as Room PK (`ts`) |
| `type` | string | Filter to `"sgv"` only, ignore `"mbg"`, `"cal"` |

All other fields (`direction`, `noise`, `filtered`, `unfiltered`, `rssi`, `dateString`) are ignored.

## New Class: NightscoutFollower

Location: `network/NightscoutFollower.kt`

Hilt-injected singleton. Lifecycle-managed by `StrimmaService` — a coroutine `Job` launched in the service's `CoroutineScope`, cancelled on source switch. Analogous to the existing periodic loops (push retry, prune, stale check), not to `XdripBroadcastReceiver` (which is a passive `BroadcastReceiver`).

### Constructor Dependencies

- `NightscoutClient` — for `fetchEntries()`
- `ReadingDao` — for `latestOnce()`, `insert()`, `since()`
- `DirectionComputer` — for direction/delta computation
- `SettingsRepository` — for follower URL/secret/poll interval

### Interface with StrimmaService

`NightscoutFollower` does NOT call `StrimmaService.processReading()`. Instead, it takes a callback `onNewReading: suspend (GlucoseReading) -> Unit` when started. The service provides a lambda that handles post-insert work (notification, alerts, widget, broadcast) without exposing its private methods.

```kotlin
fun start(scope: CoroutineScope, onNewReading: suspend (GlucoseReading) -> Unit): Job
```

Returns the `Job` so the service can cancel it on source switch.

### Responsibilities

1. **Backfill on start:** Fetch 7 days of history when the latest reading in Room is older than 7 days (or Room is empty). Backfill inserts directly into Room via `dao.insert()` with direction computed per-reading. Only the final reading triggers the `onNewReading` callback (notification/alerts/widget). All intermediate readings are silent bulk inserts.
2. **Incremental poll:** After backfill, poll every `followerPollSeconds` for entries newer than the latest reading in Room. Each new reading triggers `onNewReading`.
3. **Deduplication:** Room PK is `ts` (epoch ms). `INSERT OR REPLACE` handles duplicates naturally.
4. **Error handling:** Log failures to DebugLog. No crash, no retry storm. Next poll cycle retries automatically.
5. **Connection status:** Expose a `StateFlow<FollowerStatus>` for the main screen.
6. **Pagination:** Request `count=2016` for 5-min cadence. If the server returns exactly `count` entries, there may be more — paginate by fetching again with `since` set to the latest returned entry's date. Repeat until fewer than `count` entries are returned. This handles 1-minute cadence (up to ~10,080 entries for 7 days) without a hardcoded limit.

### Backfill Detail

Backfill processes readings oldest-first so `DirectionComputer` has correct context. For each entry:

1. Convert sgv to mmol, validate range
2. Fetch recent readings from Room for direction computation context
3. Run `DirectionComputer.compute()` to get direction and delta
4. Insert into Room with `pushed = 1`
5. Do NOT trigger notifications, alerts, widget, or BG broadcast

After the full backfill completes, call `onNewReading` with the most recent reading to trigger a single notification/alert/widget update reflecting current state.

### Polling Logic

```
job = scope.launch:
  // Backfill phase
  status = Connecting
  latestTs = dao.latestOnce()?.ts ?: 0
  sevenDaysAgo = now - 7 days
  if latestTs < sevenDaysAgo:
    backfill(since = sevenDaysAgo)  // bulk insert, silent except final reading

  // Incremental phase
  loop:
    delay(followerPollSeconds * 1000)
    latestTs = dao.latestOnce()?.ts ?: (now - 7 days)
    entries = client.fetchEntries(url, secret, since=latestTs)
    if entries == null → update status to DISCONNECTED, continue
    if entries.isEmpty → update status to CONNECTED (no new data), continue
    for entry in entries.sortedBy { it.date }:
      if entry.type != "sgv" → skip
      mmol = entry.sgv / 18.0182
      if mmol < 1.0 || mmol > 50.0 → skip (sanity check)
      compute direction/delta via DirectionComputer
      insert reading with pushed=1
      onNewReading(reading)  // triggers notification, alerts, widget, broadcast
    update status to CONNECTED
```

### FollowerStatus

```kotlin
sealed class FollowerStatus {
    object Idle : FollowerStatus()           // not in follower mode
    object Connecting : FollowerStatus()     // first poll in progress
    data class Connected(val lastPollTs: Long) : FollowerStatus()
    data class Disconnected(val since: Long) : FollowerStatus()
}
```

### Cadence Support

The polling and processing must handle both 1-minute and 5-minute reading intervals. This requires no special code — `DirectionComputer` already targets a 5-minute window and uses the reading closest to that target. With 1-minute data there are more points for 3-point averaging, with 5-minute data fewer, but the algorithm handles both.

## NightscoutClient Changes

Add `fetchEntries()` alongside existing `pushReadings()`:

```kotlin
suspend fun fetchEntries(
    baseUrl: String,
    apiSecret: String,
    since: Long,
    count: Int = 2016  // 7 days of 5-min readings
): List<NightscoutEntry>?  // null = network error
```

`GET {baseUrl}/api/v1/entries.json?find[date][$gt]={since}&count={count}`

Auth: `api-secret` header with SHA-1 hash (existing `hashSecret()`).

### Response Parsing

The existing `NightscoutEntry` data class needs to be extended to handle response fields. Since push and fetch use different field sets, add a separate response model:

```kotlin
@Serializable
data class NightscoutEntryResponse(
    val sgv: Int? = null,
    val date: Long? = null,
    val type: String? = null
)
```

All fields nullable for defensive parsing (`ignoreUnknownKeys = true` already set).

## GlucoseSource Enum

Add new value:

```kotlin
enum class GlucoseSource(val label: String, val description: String) {
    COMPANION("Companion Mode", "Parse notifications from CGM apps"),
    XDRIP_BROADCAST("xDrip Broadcast", "Receive xDrip-compatible BG broadcasts"),
    NIGHTSCOUT_FOLLOWER("Nightscout Follower", "Follow a remote Nightscout server")
}
```

## Settings

### New Keys

| Key | Type | Storage | Default |
|-----|------|---------|---------|
| `follower_url` | String | DataStore | `""` |
| `follower_secret` | String | EncryptedSharedPreferences | `""` |
| `follower_poll_seconds` | Int | DataStore | `60` |

Separate from push credentials (`nightscout_url`, `nightscout_secret`). Switching between sources doesn't lose either set of credentials.

### Settings UI Changes

The "DATA SOURCE" section gains a third radio option: "Nightscout Follower — Follow a remote Nightscout server".

When `NIGHTSCOUT_FOLLOWER` is selected:

- **NIGHTSCOUT section** is hidden (no push in follower mode).
- **New "FOLLOWING" section** appears with:
  - Nightscout URL field (same style as current Nightscout URL)
  - API Secret field (same style, PasswordVisualTransformation)
  - Poll interval slider: 30s to 300s (5 min)
    - Label: `"Poll Interval: {value}s"`
    - Description text: `"How often to check for new readings. Lower values catch updates faster but use more battery. CGM readings typically arrive every 5 minutes."`

When `COMPANION` or `XDRIP_BROADCAST` is selected:
- "FOLLOWING" section is hidden
- "NIGHTSCOUT" section visible as before

## StrimmaService Changes

### Source Switching

The existing `glucoseSource.collect` block extends to handle the new source:

```
COMPANION → notification listener (existing, no change)
XDRIP_BROADCAST → register broadcast receiver (existing, no change)
NIGHTSCOUT_FOLLOWER → start NightscoutFollower coroutine (new)
```

When switching away from `NIGHTSCOUT_FOLLOWER`, cancel the polling coroutine.

### Push Suppression

`NightscoutFollower` handles its own reading pipeline — it does NOT go through `processReading()`. It inserts readings directly into Room with `pushed = 1` and calls the `onNewReading` callback for notification/alert/widget/broadcast updates. The `pusher.pushReading()` call in `processReading()` is never reached for follower readings.

The periodic `pusher.pushPending()` loop still runs (harmless — finds nothing unpushed with `pushed = 0`).

### Connection Status Display

`MainScreen` shows a small status line when in follower mode:

- Below the hero BG area, subtle text:
  - `"Following - 45s ago"` — normal, shows time since last successful poll
  - `"Following - connecting..."` — first poll in progress
  - `"Following - connection lost"` — last poll(s) failed, shows how long

Uses `onSurfaceVariant` color, small font (12sp). Hidden when not in follower mode.

## Testing

### Unit Tests

**NightscoutFollower:**
- Backfill fetches 7 days on empty DB
- Incremental poll uses latest reading timestamp as `since`
- Entries with `type != "sgv"` are skipped
- Entries with out-of-range mmol values are skipped
- Status transitions: Idle → Connecting → Connected / Disconnected
- Handles both 1-min and 5-min cadence entries

**NightscoutClient.fetchEntries():**
- Constructs correct URL with query params
- Sends hashed api-secret header
- Parses response with unknown fields gracefully
- Returns null on network error
- Returns empty list on 200 with no entries

### Integration Tests

- Full poll cycle: mock server returns entries → readings appear in Room with correct direction/delta
- Backfill + incremental: first poll gets history, second poll only gets new entries
- Source switching: start follower → stop → readings stop arriving

## Files Changed

| File | Change |
|------|--------|
| `data/GlucoseSource.kt` | Add `NIGHTSCOUT_FOLLOWER` |
| `data/SettingsRepository.kt` | Add `followerUrl`, `followerSecret`, `followerPollSeconds` |
| `network/NightscoutClient.kt` | Add `fetchEntries()`, add `NightscoutEntryResponse` |
| `network/NightscoutFollower.kt` | **New file.** Polling loop, backfill, status. |
| `service/StrimmaService.kt` | Handle `NIGHTSCOUT_FOLLOWER` in source switching, provide `onNewReading` callback |
| `ui/SettingsScreen.kt` | Add follower settings section, hide/show based on source |
| `ui/MainScreen.kt` | Show follower connection status |
| `ui/MainViewModel.kt` | Expose follower status |

## Source Switching and Existing Data

When switching between sources, existing readings in Room are kept. There is no `source` column on `GlucoseReading` — all readings share one table regardless of origin.

This is fine for the primary use case: a follower device doesn't have a local CGM, so there's no overlap. If someone switches from COMPANION to FOLLOWER on the same device (unusual), old readings remain and new follower readings accumulate alongside them. `INSERT OR REPLACE` on timestamp PK means if both sources produce a reading at the exact same millisecond, the newer insert wins — acceptable edge case.

No DB clear on source switch. No migration needed.

## SettingsRepository: Follower Secret

Follows the existing `nightscout_secret` pattern — synchronous `getString`/`putString` on `EncryptedSharedPreferences`, not DataStore Flow:

```kotlin
fun getFollowerSecret(): String = encryptedPrefs.getString(KEY_FOLLOWER_SECRET, "") ?: ""
fun setFollowerSecret(secret: String) {
    encryptedPrefs.edit().putString(KEY_FOLLOWER_SECRET, secret).apply()
}
```

## Not In Scope

- Push to a second Nightscout server (relay mode)
- Nightscout API v3 (v1 is universal)
- Token-based auth (api-secret only for v1)
- Nightscout WebSocket/SSE (poll is simpler and sufficient)
