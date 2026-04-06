# Integration Connection Status

**Date:** 2026-04-04
**Status:** Implemented

## Problem

Only Tidepool shows connection status in settings. Nightscout push, Nightscout follower, LibreLinkUp follower, and treatment sync all log status to the debug screen only. Users have to dig through debug logs to know if their integrations are working.

## Goal

Every integration that connects to an external service shows its connection status inline in settings, right where the URL/credentials are configured. Pattern: "Connected" in cyan when working (with "Last push/sync: X ago" where applicable), error message in red when failing. Errors clear automatically on next success.

## Scope

### In scope
- Shared `IntegrationStatus` sealed class replacing `FollowerStatus`
- NightscoutPusher: expose status StateFlow (Connected + lastPushTs / Error)
- TreatmentSyncer: expose status StateFlow (Connected + lastSyncTs / Error)
- DataSourceSettings: show status for NS push, NS follower, LLU follower
- TreatmentsSettings: show status for treatment sync
- Remove follower status banner from MainScreen
- Remove unused `main_follower_*` string resources (all locales)

### Out of scope
- LocalWebServer (Strimma is the server, not the client — no connection to monitor)
- BG Broadcast (fire-and-forget intent, no acknowledgment)
- Auto-Update (passive check, already has its own modal)

## Design

### Shared status type

Replace `FollowerStatus` with `IntegrationStatus` in `network/IntegrationStatus.kt`:

```kotlin
sealed class IntegrationStatus {
    data object Idle : IntegrationStatus()
    data object Connecting : IntegrationStatus()
    data class Connected(val lastActivityTs: Long) : IntegrationStatus()
    data class Error(val message: String) : IntegrationStatus()
}
```

`FollowerStatus` is currently defined in `NightscoutFollower.kt` and used by both NightscoutFollower and LibreLinkUpFollower. Extract to its own file, rename all references.

#### Migration: Disconnected → Error

`Disconnected(since: Long)` becomes `Error(message: String)`. The `since` timestamp was only used for "Disconnected X min ago" display on MainScreen, which is being removed.

The followers currently don't have error context — their HTTP clients return null on failure. Error messages will be descriptive but generic:
- NightscoutFollower: `"Connection lost"` (poll failed), `"Backfill failed"` (backfill fetch failed)
- LibreLinkUpFollower: `"Login failed"` (doLogin returned false), `"Connection lost"` (poll/graph fetch failed), `"Re-authentication failed"`, `"No connections found"`

These match the existing DebugLog messages and give the user enough to act on. The debug log retains the detailed info.

### NightscoutPusher changes

Add `_status` / `status` StateFlow<IntegrationStatus>:
- Set `Connected(lastActivityTs = now)` on successful push (both `pushReading` and `pushPending`)
- Set `Error("Push failing for 15+ minutes")` when `PushFailureTracker` fires the alert callback with `firing=true`
- Clear error → Connected on next success (already handled: `failureTracker.onSuccess()` fires `onAlertChanged(false)`)
- Stay `Idle` when URL/secret empty (checked at push time, not at startup)

Note: individual push retries before the 15-minute threshold do NOT set Error. The status stays Connected (showing last successful push time). This is intentional — transient failures are normal and the retry loop handles them. Only sustained failure surfaces as Error.

### TreatmentSyncer changes

Add `_status` / `status` StateFlow<IntegrationStatus>:
- Set `Connected(lastActivityTs = now)` after successful sync (in `syncSince` after `dao.upsert`)
- Set `Error(message)` on sync failure (catch block, using `e.message`)
- Stay `Idle` when URL/secret empty

### UI: Shared composable

Extract a reusable `IntegrationStatusRow` composable in `ui/settings/IntegrationStatusRow.kt`:

```kotlin
@Composable
fun IntegrationStatusRow(
    status: IntegrationStatus,
    activityLabel: String  // e.g. "Last push", "Last sync", "Last reading"
) {
    when (status) {
        is IntegrationStatus.Idle -> {} // show nothing
        is IntegrationStatus.Connecting -> {
            Text(
                stringResource(R.string.integration_connecting),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        is IntegrationStatus.Connected -> {
            val relative = DateUtils.getRelativeTimeSpanString(
                status.lastActivityTs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            Text(
                stringResource(R.string.integration_connected_activity, activityLabel, relative),
                color = InRange,
                fontSize = 12.sp
            )
        }
        is IntegrationStatus.Error -> {
            Text(status.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
}
```

String resources needed (all locales: en, sv, de, es, fr):
- `integration_connecting`: "Connecting…"
- `integration_connected_activity`: "Connected · %1$s: %2$s" (label + relative time)

### UI: DataSourceSettings

**Nightscout section** (below secret field):
- Show `IntegrationStatusRow(pushStatus, "Last push")` for NightscoutPusher status
- `Idle` renders nothing, so no special empty-state handling needed

**Nightscout Follower section** (visible when NIGHTSCOUT_FOLLOWER selected, below poll slider):
- Show `IntegrationStatusRow(followerStatus, "Last reading")` for NightscoutFollower status

**LibreLinkUp section** (visible when LIBRELINKUP selected, below password field):
- Show `IntegrationStatusRow(lluStatus, "Last reading")` for LibreLinkUpFollower status

New parameters needed on `DataSourceSettings`: `pushStatus`, `followerStatus`, `lluStatus` (all `IntegrationStatus`).

### UI: TreatmentsSettings

Below the sync toggle (when `treatmentsSyncEnabled`):
- Show `IntegrationStatusRow(treatmentSyncStatus, "Last sync")` for TreatmentSyncer status

New parameter needed on `TreatmentsSettings`: `treatmentSyncStatus: IntegrationStatus`.

### UI: MainScreen

Remove the follower status banner (lines ~439-457 in MainScreen.kt showing "Connecting…" / "Disconnected X min ago"). Remove `followerStatus` parameter from `MainScreen` and `BgHeroSection` composables.

Remove `main_follower_connecting`, `main_follower_lost`, `main_follower_lost_minutes` string resources from all locale files (en, sv, de, es, fr). These become unused.

Note: removing the main screen banner is safe because:
1. Staleness is already signaled by the timestamp ("5 min ago", "10 min ago") in the BG subtitle
2. The stale data alert fires after the configured threshold
3. Connection status now lives in settings where the user configures the integration

### ViewModel wiring

MainViewModel already has:
- `followerStatus` combining `nightscoutFollower.status` and `libreLinkUpFollower.status` — keep this, rename type to IntegrationStatus, expose for settings

Add:
- `pushStatus: StateFlow<IntegrationStatus>` from `nightscoutPusher.status`
- `treatmentSyncStatus: StateFlow<IntegrationStatus>` from `treatmentSyncer.status`

Remove: `followerStatus` from MainScreen's parameter list (but keep it in ViewModel for settings screens).

Split follower status into two separate flows for settings (since DataSourceSettings needs them individually):
- `nsFollowerStatus: StateFlow<IntegrationStatus>` from `nightscoutFollower.status`
- `lluFollowerStatus: StateFlow<IntegrationStatus>` from `libreLinkUpFollower.status`

Pass all four statuses to their respective settings screens via MainActivity navigation.

### Files touched

**New files:**
- `network/IntegrationStatus.kt` — sealed class
- `ui/settings/IntegrationStatusRow.kt` — shared composable

**Modified files:**
- `network/NightscoutFollower.kt` — FollowerStatus → IntegrationStatus, Disconnected → Error(message)
- `network/LibreLinkUpFollower.kt` — FollowerStatus → IntegrationStatus, Disconnected → Error(message)
- `network/NightscoutPusher.kt` — add status StateFlow
- `network/TreatmentSyncer.kt` — add status StateFlow
- `ui/MainScreen.kt` — remove follower banner + parameter
- `ui/MainViewModel.kt` — add push/treatment status, split follower status, rename types
- `ui/MainActivity.kt` — wire statuses to settings screens
- `ui/settings/DataSourceSettings.kt` — add status rows
- `ui/settings/TreatmentsSettings.kt` — add status row
- `res/values/strings.xml` (+ all locales) — add integration_* strings, remove main_follower_* strings

## Testing

- **NightscoutPusher:** Test status transitions: Idle when no URL, Connected after successful push, Error after PushFailureTracker fires, back to Connected on recovery
- **TreatmentSyncer:** Test status transitions: Idle when no URL, Connected after successful sync, Error on sync failure, back to Connected on recovery
- **Existing tests:** NightscoutFollowerTest, LibreLinkUpFollowerTest — update FollowerStatus references to IntegrationStatus mechanically
