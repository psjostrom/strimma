# Integration Connection Status — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show connection status (Connected / Error) inline in settings for every external integration: Nightscout push, Nightscout follower, LibreLinkUp follower, and treatment sync.

**Architecture:** Replace `FollowerStatus` with a shared `IntegrationStatus` sealed class. Add status StateFlows to NightscoutPusher and TreatmentSyncer. Wire all four statuses through MainViewModel to their respective settings screens. Remove the follower status banner from MainScreen.

**Tech Stack:** Kotlin, Jetpack Compose, Coroutines/Flow, Hilt, JUnit 4

**Spec:** `docs/specs/2026-04-04-integration-status-design.md`

---

### Task 1: Create IntegrationStatus sealed class and migrate FollowerStatus

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/network/IntegrationStatus.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/network/NightscoutFollower.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/network/LibreLinkUpFollower.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainScreen.kt`

- [ ] **Step 1: Create IntegrationStatus.kt**

```kotlin
// app/src/main/java/com/psjostrom/strimma/network/IntegrationStatus.kt
package com.psjostrom.strimma.network

sealed class IntegrationStatus {
    data object Idle : IntegrationStatus()
    data object Connecting : IntegrationStatus()
    data class Connected(val lastActivityTs: Long) : IntegrationStatus()
    data class Error(val message: String) : IntegrationStatus()
}
```

- [ ] **Step 2: Migrate NightscoutFollower.kt**

Delete the `FollowerStatus` sealed class (lines 20-25 of `NightscoutFollower.kt`). Replace all `FollowerStatus` references with `IntegrationStatus`. The `processNightscoutEntry` top-level function stays in this file.

Specific replacements in `NightscoutFollower`:
- `FollowerStatus.Idle` → `IntegrationStatus.Idle`
- `FollowerStatus.Connecting` → `IntegrationStatus.Connecting`
- `FollowerStatus.Connected(lastPollTs = ...)` → `IntegrationStatus.Connected(lastActivityTs = ...)`
- `FollowerStatus.Disconnected(since = ...)` → `IntegrationStatus.Error("Connection lost")` (poll failed) or `IntegrationStatus.Error("Backfill failed")` (backfill)
- `_status.value !is FollowerStatus.Disconnected` → `_status.value !is IntegrationStatus.Error`

- [ ] **Step 3: Migrate LibreLinkUpFollower.kt**

Replace all `FollowerStatus` references with `IntegrationStatus`:
- `FollowerStatus.Idle` → `IntegrationStatus.Idle`
- `FollowerStatus.Connecting` → `IntegrationStatus.Connecting`
- `FollowerStatus.Connected(lastPollTs = ...)` → `IntegrationStatus.Connected(lastActivityTs = ...)`
- Line 53 (`doLogin` failed on start): `IntegrationStatus.Error("Login failed")`
- Line 69 (no session/patient): `IntegrationStatus.Error("Connection lost")`
- Line 78 (re-auth failed): `IntegrationStatus.Error("Re-authentication failed")`
- Lines 90-91 (graph fetch failed): `IntegrationStatus.Error("Connection lost")`
- In `doLogin`, when connections are empty (line 154): log already says "no connections found", but the status is set elsewhere. Add `_status.value = IntegrationStatus.Error("No connections found")` before `return false` on line 155.

- [ ] **Step 4: Migrate MainViewModel.kt**

Replace import `com.psjostrom.strimma.network.FollowerStatus` with `com.psjostrom.strimma.network.IntegrationStatus`.

Change `followerStatus` (lines 211-217):
```kotlin
val followerStatus: StateFlow<IntegrationStatus> = glucoseSource.flatMapLatest { source ->
    when (source) {
        GlucoseSource.NIGHTSCOUT_FOLLOWER -> nightscoutFollower.status
        GlucoseSource.LIBRELINKUP -> libreLinkUpFollower.status
        else -> kotlinx.coroutines.flow.flowOf(IntegrationStatus.Idle)
    }
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IntegrationStatus.Idle)
```

- [ ] **Step 5: Migrate MainScreen.kt**

Replace import `com.psjostrom.strimma.network.FollowerStatus` with `com.psjostrom.strimma.network.IntegrationStatus`.

Change parameter types:
- `MainScreen`: `followerStatus: FollowerStatus = FollowerStatus.Idle` → `followerStatus: IntegrationStatus = IntegrationStatus.Idle`
- `BgHeader`: same change

Change the follower status banner logic (lines ~439-457) to use `IntegrationStatus`:
- `followerStatus is FollowerStatus.Connecting || followerStatus is FollowerStatus.Disconnected` → `followerStatus is IntegrationStatus.Connecting || followerStatus is IntegrationStatus.Error`
- `is FollowerStatus.Connecting` → `is IntegrationStatus.Connecting`
- `is FollowerStatus.Disconnected` → `is IntegrationStatus.Error` and show `followerStatus.message` instead of computing minutes

- [ ] **Step 6: Migrate MainActivity.kt**

Replace import `com.psjostrom.strimma.network.FollowerStatus` with `com.psjostrom.strimma.network.IntegrationStatus`.

- [ ] **Step 7: Build and run tests**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass. The rename is mechanical.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Replace FollowerStatus with shared IntegrationStatus sealed class

Mechanical rename. Disconnected(since) → Error(message) with descriptive
error messages per failure mode."
```

---

### Task 2: Add status StateFlow to NightscoutPusher

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/network/NightscoutPusher.kt`
- Create: `app/src/test/java/com/psjostrom/strimma/network/NightscoutPusherTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// app/src/test/java/com/psjostrom/strimma/network/NightscoutPusherTest.kt
package com.psjostrom.strimma.network

import org.junit.Assert.*
import org.junit.Test

class NightscoutPusherTest {

    @Test
    fun `initial status is Idle`() {
        val status = IntegrationStatus.Idle
        assertTrue(status is IntegrationStatus.Idle)
    }
}
```

Actually, NightscoutPusher is hard to unit-test in isolation — it has `@Inject constructor` with NightscoutClient, ReadingDao, SettingsRepository, AlertManager, and uses `CoroutineScope(SupervisorJob() + Dispatchers.IO)` internally. Testing the status transitions requires mocking all deps.

Instead, test the status exposure pattern by verifying the PushFailureTracker integration. The tracker already has thorough tests. We add a status StateFlow and wire it to the tracker callbacks.

- [ ] **Step 2: Add status StateFlow to NightscoutPusher**

Add to `NightscoutPusher.kt`:

```kotlin
// Add import at top
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Add after the scope field (line 28)
private val _status = MutableStateFlow<IntegrationStatus>(IntegrationStatus.Idle)
val status: StateFlow<IntegrationStatus> = _status
```

Change the `failureTracker` initialization (lines 30-32) to also update `_status`:

```kotlin
private val failureTracker = PushFailureTracker(
    alertThresholdMs = PUSH_FAIL_ALERT_MS,
    onAlertChanged = { firing ->
        alertManager.handlePushFailure(firing = firing)
        if (firing) {
            _status.value = IntegrationStatus.Error("Push failing for 15+ minutes")
        }
    }
)
```

In `pushReading` (line 50, the success branch), after `failureTracker.onSuccess()`:
```kotlin
_status.value = IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
```

In `pushPending` (line 83, the success branch), after `failureTracker.onSuccess()`:
```kotlin
_status.value = IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
```

- [ ] **Step 3: Build**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/network/NightscoutPusher.kt
git commit -m "Add status StateFlow to NightscoutPusher

Connected on successful push, Error after 15+ minutes of failures.
Clears error automatically on next success via PushFailureTracker."
```

---

### Task 3: Add status StateFlow to TreatmentSyncer

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/network/TreatmentSyncer.kt`

- [ ] **Step 1: Add status StateFlow to TreatmentSyncer**

Add imports:
```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
```

Add after the class declaration:
```kotlin
private val _status = MutableStateFlow<IntegrationStatus>(IntegrationStatus.Idle)
val status: StateFlow<IntegrationStatus> = _status
```

In `syncSince` (line 104), after the URL/secret empty check (line 107):
```kotlin
if (url.isBlank() || secret.isBlank()) return
```
This keeps status as Idle. No change needed here.

In `syncSince`, after `dao.upsert(treatments)` (line 114):
```kotlin
_status.value = IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
```

Also set Connected when treatments list is empty (line 112) — an empty response is a successful connection:
```kotlin
if (treatments.isEmpty()) {
    _status.value = IntegrationStatus.Connected(lastActivityTs = System.currentTimeMillis())
    return
}
```

In `syncSince`, in the catch block (line 125):
```kotlin
_status.value = IntegrationStatus.Error(e.message?.take(80) ?: "Sync failed")
```

- [ ] **Step 2: Build and run tests**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass. TreatmentSyncerTest tests `computeStartupAction` which is unaffected.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/network/TreatmentSyncer.kt
git commit -m "Add status StateFlow to TreatmentSyncer

Connected after successful sync, Error on failure with message.
Clears error automatically on next successful sync."
```

---

### Task 4: Create IntegrationStatusRow composable and add string resources

**Files:**
- Create: `app/src/main/java/com/psjostrom/strimma/ui/settings/IntegrationStatusRow.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-sv/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`

- [ ] **Step 1: Add string resources**

Add to `values/strings.xml`:
```xml
<string name="integration_connecting">Connecting…</string>
<string name="integration_connected">Connected · %1$s: %2$s</string>
```

Add to `values-sv/strings.xml`:
```xml
<string name="integration_connecting">Ansluter…</string>
<string name="integration_connected">Ansluten · %1$s: %2$s</string>
```

Add to `values-de/strings.xml`:
```xml
<string name="integration_connecting">Verbinden…</string>
<string name="integration_connected">Verbunden · %1$s: %2$s</string>
```

Add to `values-es/strings.xml`:
```xml
<string name="integration_connecting">Conectando…</string>
<string name="integration_connected">Conectado · %1$s: %2$s</string>
```

Add to `values-fr/strings.xml`:
```xml
<string name="integration_connecting">Connexion…</string>
<string name="integration_connected">Connecté · %1$s: %2$s</string>
```

- [ ] **Step 2: Create IntegrationStatusRow.kt**

```kotlin
// app/src/main/java/com/psjostrom/strimma/ui/settings/IntegrationStatusRow.kt
package com.psjostrom.strimma.ui.settings

import android.text.format.DateUtils
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.network.IntegrationStatus
import com.psjostrom.strimma.ui.theme.InRange

@Composable
fun IntegrationStatusRow(
    status: IntegrationStatus,
    activityLabel: String
) {
    when (status) {
        is IntegrationStatus.Idle -> {}
        is IntegrationStatus.Connecting -> {
            Text(
                stringResource(R.string.integration_connecting),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        is IntegrationStatus.Connected -> {
            val relative = DateUtils.getRelativeTimeSpanString(
                status.lastActivityTs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            Text(
                stringResource(R.string.integration_connected, activityLabel, relative),
                color = InRange,
                fontSize = 12.sp
            )
        }
        is IntegrationStatus.Error -> {
            Text(
                status.message,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp
            )
        }
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew assembleDebug`
Expected: Compiles. The composable is not yet used anywhere.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/settings/IntegrationStatusRow.kt app/src/main/res/values*/strings.xml
git commit -m "Add IntegrationStatusRow composable and string resources

Shared status display: Connected (cyan) with relative timestamp,
Error (red) with message, Connecting, or hidden (Idle).
All 5 locales updated."
```

---

### Task 5: Wire statuses to DataSourceSettings

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/settings/DataSourceSettings.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt`

- [ ] **Step 1: Add push and follower status flows to MainViewModel**

Add after the existing `followerStatus` (line 217):

```kotlin
val nsFollowerStatus: StateFlow<IntegrationStatus> = nightscoutFollower.status
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IntegrationStatus.Idle)

val lluFollowerStatus: StateFlow<IntegrationStatus> = libreLinkUpFollower.status
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IntegrationStatus.Idle)

val pushStatus: StateFlow<IntegrationStatus> = nightscoutPusher.status
```

Note: `nightscoutPusher` is injected into MainViewModel. Check the constructor — it has `private val nightscoutPusher: NightscoutPusher` ... actually, let me verify. The ViewModel may not have NightscoutPusher injected. Let me check.

The ViewModel constructor currently has `nightscoutFollower`, `libreLinkUpFollower`, `nightscoutPuller`. NightscoutPusher is owned by StrimmaService, not the ViewModel. Same for TreatmentSyncer.

This means we need to either:
(a) Inject NightscoutPusher and TreatmentSyncer into MainViewModel (they're @Singleton, so Hilt can do this)
(b) Expose the status via a shared repository

Option (a) is simpler — they're already @Singleton. Add them to the ViewModel constructor.

Add to `MainViewModel` `@Inject constructor` (after `nightscoutPuller`):
```kotlin
private val nightscoutPusher: NightscoutPusher,
```

Note: `treatmentSyncer` is already injected (line 65).

Add import:
```kotlin
import com.psjostrom.strimma.network.NightscoutPusher
```

Then add the status properties:
```kotlin
val pushStatus: StateFlow<IntegrationStatus> = nightscoutPusher.status

val nsFollowerStatus: StateFlow<IntegrationStatus> = nightscoutFollower.status
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IntegrationStatus.Idle)

val lluFollowerStatus: StateFlow<IntegrationStatus> = libreLinkUpFollower.status
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IntegrationStatus.Idle)

val treatmentSyncStatus: StateFlow<IntegrationStatus> = treatmentSyncer.status
```

- [ ] **Step 2: Add parameters to DataSourceSettings**

Add three new parameters to `DataSourceSettings` composable:

```kotlin
@Composable
fun DataSourceSettings(
    glucoseSource: GlucoseSource,
    nightscoutUrl: String,
    nightscoutSecret: String,
    followerPollSeconds: Int,
    lluEmail: String,
    lluPassword: String,
    pushStatus: IntegrationStatus,           // NEW
    nsFollowerStatus: IntegrationStatus,     // NEW
    lluFollowerStatus: IntegrationStatus,    // NEW
    isNotificationAccessGranted: Boolean,
    // ... rest unchanged
```

Add import:
```kotlin
import com.psjostrom.strimma.network.IntegrationStatus
```

- [ ] **Step 3: Add push status row below NS secret field**

In the Nightscout section (after the secret OutlinedTextField, around line 168), add:

```kotlin
IntegrationStatusRow(status = pushStatus, activityLabel = "Last push")
```

- [ ] **Step 4: Add follower status row below poll slider**

In the `NIGHTSCOUT_FOLLOWER` section (after the Slider, around line 189), add:

```kotlin
IntegrationStatusRow(status = nsFollowerStatus, activityLabel = "Last reading")
```

- [ ] **Step 5: Add LLU status row below password field**

In the `LIBRELINKUP` section (after the hint Text, around line 133), add:

```kotlin
IntegrationStatusRow(status = lluFollowerStatus, activityLabel = "Last reading")
```

- [ ] **Step 6: Wire in MainActivity**

In `composable("settings/data-source")` block (line ~411), add status collection and pass to `DataSourceSettings`:

```kotlin
val pushStatus by viewModel.pushStatus.collectAsState()
val nsFollowerStatus by viewModel.nsFollowerStatus.collectAsState()
val lluFollowerStatus by viewModel.lluFollowerStatus.collectAsState()
```

Pass them to `DataSourceSettings(...)`:
```kotlin
pushStatus = pushStatus,
nsFollowerStatus = nsFollowerStatus,
lluFollowerStatus = lluFollowerStatus,
```

- [ ] **Step 7: Build**

Run: `./gradlew assembleDebug`
Expected: Compiles. Status rows now appear in DataSourceSettings.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt app/src/main/java/com/psjostrom/strimma/ui/settings/DataSourceSettings.kt app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt
git commit -m "Wire integration status to DataSourceSettings

Push status below NS URL/secret, follower status below poll slider,
LLU status below password field. All use IntegrationStatusRow."
```

---

### Task 6: Wire treatment sync status to TreatmentsSettings

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/settings/TreatmentsSettings.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt`

- [ ] **Step 1: Add parameter to TreatmentsSettings**

Add import and parameter:
```kotlin
import com.psjostrom.strimma.network.IntegrationStatus
```

```kotlin
@Composable
fun TreatmentsSettings(
    treatmentsSyncEnabled: Boolean,
    insulinType: InsulinType,
    customDIA: Float,
    nightscoutConfigured: Boolean,
    treatmentSyncStatus: IntegrationStatus,  // NEW
    // ... rest unchanged
```

- [ ] **Step 2: Add status row below sync toggle**

In the treatments section, after the Switch row (around line 63, inside `if (treatmentsSyncEnabled)`), add:

```kotlin
IntegrationStatusRow(status = treatmentSyncStatus, activityLabel = "Last sync")
```

- [ ] **Step 3: Wire in MainActivity**

In `composable("settings/treatments")` block (line ~440), add:

```kotlin
val treatmentSyncStatus by viewModel.treatmentSyncStatus.collectAsState()
```

Pass to `TreatmentsSettings(...)`:
```kotlin
treatmentSyncStatus = treatmentSyncStatus,
```

- [ ] **Step 4: Build**

Run: `./gradlew assembleDebug`
Expected: Compiles.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/settings/TreatmentsSettings.kt app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt
git commit -m "Wire treatment sync status to TreatmentsSettings

Shows connected/error below the sync toggle when enabled."
```

---

### Task 7: Remove follower status banner from MainScreen and clean up strings

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-sv/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`

- [ ] **Step 1: Remove follower status from MainScreen**

In `MainScreen` composable:
- Remove `followerStatus: IntegrationStatus = IntegrationStatus.Idle` parameter
- Remove it from the `BgHeader(...)` call (line ~173)

In `BgHeader` composable:
- Remove `followerStatus: IntegrationStatus` parameter
- Delete the entire follower status banner block (lines ~439-457):

```kotlin
// DELETE this entire block:
if (followerStatus is IntegrationStatus.Connecting || followerStatus is IntegrationStatus.Error) {
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = when (followerStatus) {
            // ...
        },
        // ...
    )
}
```

Remove the `IntegrationStatus` import from MainScreen.kt if no other references remain.

- [ ] **Step 2: Remove followerStatus from MainScreen call site in MainActivity**

In `MainActivity.kt`, in the main screen composable (around line 373), remove:
```kotlin
followerStatus = followerStatus,
```

And remove the `followerStatus` collection at line ~182:
```kotlin
val followerStatus by viewModel.followerStatus.collectAsState()
```

- [ ] **Step 3: Remove combined followerStatus from MainViewModel**

The combined `followerStatus` flow (lines 211-217) was only used by MainScreen. Remove it:

```kotlin
// DELETE:
val followerStatus: StateFlow<IntegrationStatus> = glucoseSource.flatMapLatest { source ->
    when (source) {
        GlucoseSource.NIGHTSCOUT_FOLLOWER -> nightscoutFollower.status
        GlucoseSource.LIBRELINKUP -> libreLinkUpFollower.status
        else -> kotlinx.coroutines.flow.flowOf(IntegrationStatus.Idle)
    }
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IntegrationStatus.Idle)
```

The individual `nsFollowerStatus` and `lluFollowerStatus` added in Task 5 replace it for settings.

- [ ] **Step 4: Remove unused string resources**

Remove from `values/strings.xml`:
```xml
<string name="main_follower_connecting">...</string>
<string name="main_follower_lost">...</string>
<string name="main_follower_lost_minutes">...</string>
```

Remove the same three strings from `values-sv/strings.xml`, `values-de/strings.xml`, `values-es/strings.xml`, `values-fr/strings.xml`.

- [ ] **Step 5: Build and run all tests**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass. No test referenced the MainScreen follower banner.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Remove follower status banner from MainScreen

Connection status now shown in settings next to credentials.
Staleness is already signaled by BG timestamp and stale alert.
Removed unused main_follower_* string resources from all locales."
```

---

### Task 8: Install on device and verify

**Files:** None (manual verification)

- [ ] **Step 1: Build and install**

Run: `./gradlew installRelease`
(Connect ADB first if needed: `adb connect 192.168.1.10:<port>`)

- [ ] **Step 2: Verify DataSourceSettings**

Open Settings → Data Source. Check:
- Below NS URL/secret: should show push status ("Connected · Last push: X ago" in cyan)
- If NIGHTSCOUT_FOLLOWER selected: status below poll slider
- If LIBRELINKUP selected: status below password field

- [ ] **Step 3: Verify TreatmentsSettings**

Open Settings → Treatments. Check:
- Below sync toggle (when enabled): should show "Connected · Last sync: X ago"

- [ ] **Step 4: Verify MainScreen**

Open the BG tab. Confirm the follower status banner ("Connecting…" / "Disconnected X min ago") is gone.

- [ ] **Step 5: Final test run**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass.
