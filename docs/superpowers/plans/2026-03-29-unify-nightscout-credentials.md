# Unify Nightscout Credentials Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the two separate Nightscout URL/secret pairs (`nightscoutUrl`/`nightscoutSecret` and `followerUrl`/`followerSecret`) with a single pair used by all consumers. The data source selection determines behavior (push vs. follow), not the server identity.

**Architecture:** One `nightscoutUrl` + `nightscoutSecret` in `SettingsRepository`. All consumers (`NightscoutPusher`, `NightscoutFollower`, `NightscoutPuller`, `TreatmentSyncer`) read from the same pair. The `resolveUrlAndSecret()` pattern in `NightscoutPuller` and `TreatmentSyncer` is removed — they just read `nightscoutUrl`/`nightscoutSecret` directly. The follower-specific poll interval (`followerPollSeconds`) remains since it's a source behavior setting, not a server identity. No DataStore migration needed — single user, fresh install.

**Tech Stack:** Kotlin, Jetpack Compose, DataStore, EncryptedSharedPreferences, Hilt, Robolectric

---

### Task 1: Remove `followerUrl`/`followerSecret` from SettingsRepository

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/data/SettingsRepository.kt`

- [ ] **Step 1: Remove follower URL/secret fields and methods**

Delete:
- `KEY_FOLLOWER_URL` (line 123)
- `KEY_FOLLOWER_SECRET` (line 125)
- `followerUrl` flow (line 274)
- `setFollowerUrl()` (line 275)
- `getFollowerSecret()` (line 280)
- `setFollowerSecret()` (lines 281-283)

Keep `KEY_FOLLOWER_POLL_SECONDS`, `followerPollSeconds`, and `setFollowerPollSeconds` — those are source behavior settings, not server identity.

- [ ] **Step 2: Update `exportToJson()` to remove follower_url**

In `exportToJson()` (around line 473), remove:
```kotlin
put("follower_url", prefs[KEY_FOLLOWER_URL] ?: "")
```

In the secrets section (around line 486), remove:
```kotlin
put("follower_secret", getFollowerSecret())
```

- [ ] **Step 3: Update `importFromJson()` for backward compatibility**

In `importFromJson()`, change the follower_url import (around line 537) to migrate into nightscout_url:

```kotlin
// Legacy: if importing an old export that had follower_url, adopt it as nightscout_url
// if nightscout_url is empty
if (settings.has("follower_url") && !settings.has("nightscout_url")) {
    prefs[KEY_NIGHTSCOUT_URL] = settings.getString("follower_url")
}
```

Change the follower_secret import (around line 560) similarly:
```kotlin
if (secrets.has("follower_secret") && !secrets.has("nightscout_secret")) {
    setNightscoutSecret(secrets.getString("follower_secret"))
}
```

Remove the standalone `follower_url` DataStore write and `follower_secret` encrypted prefs write.

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew assembleDebug`
Expected: Compilation errors in consumers that reference `followerUrl`/`getFollowerSecret`. This is expected — we'll fix them in subsequent tasks.

- [ ] **Step 5: Commit (WIP — won't compile yet)**

```bash
git add app/src/main/java/com/psjostrom/strimma/data/SettingsRepository.kt
git commit -m "refactor: remove followerUrl/followerSecret from SettingsRepository"
```

---

### Task 2: Update NightscoutFollower to use unified credentials

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/network/NightscoutFollower.kt`

- [ ] **Step 1: Replace followerUrl/followerSecret with nightscoutUrl/nightscoutSecret**

In `start()` method (lines 88-89), change:
```kotlin
val url = settings.followerUrl.first()
val secret = settings.getFollowerSecret()
```
to:
```kotlin
val url = settings.nightscoutUrl.first()
val secret = settings.getNightscoutSecret()
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/network/NightscoutFollower.kt
git commit -m "refactor: NightscoutFollower uses unified nightscout credentials"
```

---

### Task 3: Simplify NightscoutPuller — remove resolveUrlAndSecret

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/network/NightscoutPuller.kt`

- [ ] **Step 1: Remove `resolveUrlAndSecret()` and inline the single URL/secret**

Replace `resolveUrlAndSecret()` (lines 59-65) — delete the entire method.

In `pullHistory()` (line 30), change:
```kotlin
val (url, secret) = resolveUrlAndSecret()
```
to:
```kotlin
val url = settings.nightscoutUrl.first()
val secret = settings.getNightscoutSecret()
```

In `pullIfEmpty()` (lines 43-47), the check for `NIGHTSCOUT_FOLLOWER` and the separate URL read are no longer needed. The follower mode now uses the same URL. Change lines 39-47:
```kotlin
suspend fun pullIfEmpty() {
    val latest = dao.latestOnce()
    if (latest != null) return

    if (settings.glucoseSource.first() == GlucoseSource.NIGHTSCOUT_FOLLOWER) return

    val url = settings.nightscoutUrl.first()
    val secret = settings.getNightscoutSecret()
    if (url.isBlank() || secret.isBlank()) return

    DebugLog.log(message = "Pull: DB empty, pulling $AUTO_PULL_DAYS days from Nightscout")
    val since = System.currentTimeMillis() - AUTO_PULL_DAYS * MS_PER_DAY
    val result = pullSince(url, secret, since)
    result.onSuccess { count ->
        DebugLog.log(message = "Pull: auto-pull complete, $count readings")
    }.onFailure { e ->
        DebugLog.log(message = "Pull: auto-pull failed: ${e.message?.take(MAX_ERROR_LENGTH)}")
    }
}
```

Note: `pullIfEmpty()` is unchanged in behavior — the follower skip is still correct because the follower does its own backfill. The only change is the URL/secret no longer branches.

Remove the `GlucoseSource` import if it was only used by `resolveUrlAndSecret` — but `pullIfEmpty` still uses it, so keep it.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/network/NightscoutPuller.kt
git commit -m "refactor: NightscoutPuller uses unified nightscout credentials"
```

---

### Task 4: Simplify TreatmentSyncer — remove resolveUrlAndSecret

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/network/TreatmentSyncer.kt`

- [ ] **Step 1: Remove `resolveUrlAndSecret()` and use direct reads**

Delete `resolveUrlAndSecret()` (lines 69-75).

In `pullHistory()` (line 46), change:
```kotlin
val (url, secret) = resolveUrlAndSecret()
```
to:
```kotlin
val url = settings.nightscoutUrl.first()
val secret = settings.getNightscoutSecret()
```

In `sync()` (line 78), change:
```kotlin
val (url, secret) = resolveUrlAndSecret()
```
to:
```kotlin
val url = settings.nightscoutUrl.first()
val secret = settings.getNightscoutSecret()
```

Remove the `GlucoseSource` import (now unused — verify with IDE/compiler).

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/network/TreatmentSyncer.kt
git commit -m "refactor: TreatmentSyncer uses unified nightscout credentials"
```

---

### Task 5: Update DataSourceSettings UI — unified Nightscout section

The settings screen currently shows "Nightscout Push" (with URL/secret) when not in follower mode, and "Following" (with a different URL/secret + poll slider) in follower mode. After unification, there's one "Nightscout" section always visible (URL + secret), plus follower-specific settings (poll interval) shown conditionally.

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/settings/DataSourceSettings.kt`

- [ ] **Step 1: Remove follower URL/secret params, simplify the composable signature**

Change the `DataSourceSettings` composable parameters. Remove:
- `followerUrl: String`
- `followerSecret: String`
- `onFollowerUrlChange: (String) -> Unit`
- `onFollowerSecretChange: (String) -> Unit`

Keep:
- `followerPollSeconds: Int`
- `onFollowerPollSecondsChange: (Int) -> Unit`

- [ ] **Step 2: Replace the split push/following sections with a unified Nightscout section**

Replace the entire block from line 147 to line 220 (the `if (glucoseSource != NIGHTSCOUT_FOLLOWER) { ... } else { ... }`) with:

```kotlin
SettingsSection(stringResource(R.string.settings_source_nightscout)) {
    var urlText by remember(nightscoutUrl) { mutableStateOf(nightscoutUrl) }
    OutlinedTextField(
        value = urlText,
        onValueChange = {
            urlText = it
            onNightscoutUrlChange(it)
        },
        label = { Text(stringResource(R.string.settings_source_nightscout_url)) },
        placeholder = { Text(stringResource(R.string.settings_source_url_placeholder)) },
        supportingText = { Text(stringResource(R.string.settings_source_url_hint)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    var secretText by remember(nightscoutSecret) { mutableStateOf(nightscoutSecret) }
    OutlinedTextField(
        value = secretText,
        onValueChange = {
            secretText = it
            onNightscoutSecretChange(it)
        },
        label = { Text(stringResource(R.string.settings_source_api_secret)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

if (glucoseSource == GlucoseSource.NIGHTSCOUT_FOLLOWER) {
    SettingsSection(stringResource(R.string.settings_source_poll_settings)) {
        Text(
            stringResource(R.string.settings_source_poll_interval, followerPollSeconds),
            color = onBg,
            fontSize = 14.sp
        )
        Text(
            stringResource(R.string.settings_source_poll_explanation),
            color = outline,
            fontSize = 12.sp
        )
        Slider(
            value = followerPollSeconds.toFloat(),
            onValueChange = { onFollowerPollSecondsChange(it.toInt()) },
            valueRange = 30f..300f,
            steps = 8
        )
    }
}
```

- [ ] **Step 3: Add missing string resource**

In `app/src/main/res/values/strings.xml`, add:
```xml
<string name="settings_source_nightscout">Nightscout</string>
<string name="settings_source_poll_settings">Follower Settings</string>
```

Add equivalent translations in `values-sv/strings.xml`:
```xml
<string name="settings_source_nightscout">Nightscout</string>
<string name="settings_source_poll_settings">Följarinställningar</string>
```

Add equivalent translations in `values-de/strings.xml`:
```xml
<string name="settings_source_nightscout">Nightscout</string>
<string name="settings_source_poll_settings">Follower-Einstellungen</string>
```

Add equivalent translations in `values-es/strings.xml`:
```xml
<string name="settings_source_nightscout">Nightscout</string>
<string name="settings_source_poll_settings">Configuración de seguimiento</string>
```

Add equivalent translations in `values-fr/strings.xml`:
```xml
<string name="settings_source_nightscout">Nightscout</string>
<string name="settings_source_poll_settings">Paramètres de suivi</string>
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/settings/DataSourceSettings.kt
git add app/src/main/res/values*/strings.xml
git commit -m "refactor: unified Nightscout section in DataSourceSettings"
```

---

### Task 6: Update MainViewModel and MainActivity — remove follower URL/secret

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt`

- [ ] **Step 1: Remove follower URL/secret from MainViewModel**

Delete from `MainViewModel`:
- `followerUrl` StateFlow (line 273)
- `setFollowerUrl()` (line 275)
- `followerSecret` getter (line 277)
- `setFollowerSecret()` (line 278)

- [ ] **Step 2: Update MainActivity DataSourceSettings call site**

In `MainActivity.kt` (around line 286-317), remove:
- `followerUrl = followerUrl` — delete this param
- `followerSecret = viewModel.followerSecret` — delete this param
- `onFollowerUrlChange = viewModel::setFollowerUrl` — delete this param
- `onFollowerSecretChange = viewModel::setFollowerSecret` — delete this param

Also remove the `followerUrl` `collectAsState` near line 162.

- [ ] **Step 3: Build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/MainViewModel.kt
git add app/src/main/java/com/psjostrom/strimma/ui/MainActivity.kt
git commit -m "refactor: remove follower URL/secret from MainViewModel and MainActivity"
```

---

### Task 7: Update Setup wizard — unified Nightscout step

The setup wizard currently has: step 2 (data source, with follower URL inline) and step 3 (Nightscout push, optional). After unification, the Nightscout URL is configured in step 3 regardless of source, and the follower inline config in step 2 only shows the connection test (using the URL from step 3 if already entered).

Actually, simpler: step 2 picks the source (no URL fields for follower — that's in step 3). Step 3 becomes "Nightscout Server" (not "Nightscout Push") and is shown for all sources.

**Files:**
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/setup/SetupViewModel.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/setup/SetupScreen.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/setup/SetupDataSourceStep.kt`
- Modify: `app/src/main/java/com/psjostrom/strimma/ui/setup/SetupNightscoutStep.kt`
- Modify: `app/src/main/res/values/strings.xml` (and translations)

- [ ] **Step 1: Remove follower URL/secret from SetupViewModel**

Delete from `SetupViewModel`:
- `followerUrl` StateFlow (line 47-48)
- `followerSecret` getter (line 50)
- `_followerTestState` and `followerTestState` (lines 52-53)
- `setFollowerUrl()` (line 103)
- `setFollowerSecret()` (line 104)
- `testFollowerConnection()` (lines 118-128)

- [ ] **Step 2: Change `setPushEnabled(false)` to not clear credentials**

In `SetupViewModel.setPushEnabled()` (lines 93-101), when push is disabled, it currently clears `nightscoutUrl` and `nightscoutSecret`. Since the URL is now shared, clearing it would break follower mode too. Change:

```kotlin
fun setPushEnabled(enabled: Boolean) {
    _pushEnabled.value = enabled
    if (!enabled) {
        _connectionTestState.value = ConnectionTestState.Idle
    }
}
```

The push toggle in the wizard now just controls whether the pusher is active, but doesn't wipe credentials. (The NightscoutPusher already handles empty URL by skipping push.)

Wait — actually the `_pushEnabled` state is wizard-local (not persisted). The real "push enabled" behavior comes from whether `nightscoutUrl` is non-empty. Since we're now unifying, the "push" toggle concept should change: the URL is always the same, and push happens when the source is NOT follower mode (because you're generating data). Let me reconsider.

The setup wizard step 3 should become: "Nightscout Server" — always shown, not behind a toggle. The URL/secret is entered here. The `pushEnabled` toggle should be removed from the wizard entirely — push behavior is implicit from the data source selection (non-follower sources push, follower doesn't).

Updated step:

```kotlin
fun SetupNightscoutStep(
    nightscoutUrl: String,
    nightscoutSecret: String,
    onUrlChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    connectionTestState: ConnectionTestState,
    onTestConnection: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.setup_nightscout_desc),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.outline,
            lineHeight = 18.sp
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                var urlText by remember(nightscoutUrl) { mutableStateOf(nightscoutUrl) }
                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        onUrlChange(it)
                    },
                    label = { Text(stringResource(R.string.settings_source_nightscout_url)) },
                    placeholder = { Text(stringResource(R.string.settings_source_url_placeholder)) },
                    supportingText = { Text(stringResource(R.string.settings_source_url_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                var secretText by remember(nightscoutSecret) { mutableStateOf(nightscoutSecret) }
                OutlinedTextField(
                    value = secretText,
                    onValueChange = {
                        secretText = it
                        onSecretChange(it)
                    },
                    label = { Text(stringResource(R.string.settings_source_api_secret)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                ConnectionTestButton(
                    testState = connectionTestState,
                    onTest = onTestConnection,
                    hasCredentials = urlText.isNotBlank() && secretText.isNotBlank()
                )
            }
        }
    }
}
```

- [ ] **Step 3: Remove follower config from SetupDataSourceStep**

Remove from `SetupDataSourceStep` parameters:
- `followerUrl`, `followerSecret`, `followerTestState`
- `onFollowerUrlChange`, `onFollowerSecretChange`, `onTestFollowerConnection`

Remove the `FollowerConfigBlock` rendering (lines 81-89).

Delete the `FollowerConfigBlock` composable (lines 260-309).

- [ ] **Step 4: Update SetupScreen wiring**

In `SetupScreen.kt`:

Remove `followerUrl`, `followerTestState` state collections (lines 48-49).

Update `SetupDataSourceStep` call (lines 146-162) — remove follower params.

Update `canAdvance` for step 2 (line 67): follower no longer needs a connection test at this step — the URL is entered in step 3. Change:
```kotlin
GlucoseSource.NIGHTSCOUT_FOLLOWER -> followerTestState is ConnectionTestState.Success
```
to:
```kotlin
GlucoseSource.NIGHTSCOUT_FOLLOWER -> true
```

Update `canAdvance` for step 3 (line 71): the Nightscout step is now always visible and the connection test is always the gate:
```kotlin
3 -> connectionTestState is ConnectionTestState.Success ||
     (nightscoutUrl.isBlank()) // Allow skipping if no NS server
```

Update `SetupNightscoutStep` call (lines 163-172) — remove `pushEnabled` and `onPushEnabledChange`.

Remove `pushEnabled` state collection (line 46).

- [ ] **Step 5: Remove `setPushEnabled` and `_pushEnabled` from SetupViewModel**

Delete `_pushEnabled` (line 40), `pushEnabled` (line 41), and `setPushEnabled()` (lines 93-101).

- [ ] **Step 6: Update testConnection to use unified credentials**

`testConnection()` already uses `nightscoutUrl` and `getNightscoutSecret()` (line 109) — no change needed.

- [ ] **Step 7: Add/update string resources**

In `values/strings.xml`, change:
```xml
<string name="setup_nightscout_toggle">Push readings to Nightscout</string>
```
to:
```xml
<string name="setup_nightscout_desc">Connect to your Nightscout server for data sync, treatments, and history</string>
```

Add equivalent translations in sv/de/es/fr.

- [ ] **Step 8: Build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/psjostrom/strimma/ui/setup/
git add app/src/main/res/values*/strings.xml
git commit -m "refactor: unify Nightscout credentials in setup wizard"
```

---

### Task 8: Update DataSourceSettingsTest

**Files:**
- Modify: `app/src/test/java/com/psjostrom/strimma/ui/settings/DataSourceSettingsTest.kt`

- [ ] **Step 1: Update `render()` helper — remove follower params**

```kotlin
private fun render(
    glucoseSource: GlucoseSource = GlucoseSource.COMPANION,
    onGlucoseSourceChange: (GlucoseSource) -> Unit = {},
    onBack: () -> Unit = {}
) {
    composeRule.setContent {
        DataSourceSettings(
            glucoseSource = glucoseSource,
            nightscoutUrl = "https://ns.example.com",
            nightscoutSecret = "secret123",
            followerPollSeconds = 60,
            lluEmail = "",
            lluPassword = "",
            onGlucoseSourceChange = onGlucoseSourceChange,
            onNightscoutUrlChange = {},
            onNightscoutSecretChange = {},
            onFollowerPollSecondsChange = {},
            onLluEmailChange = {},
            onLluPasswordChange = {},
            isNotificationAccessGranted = true,
            onOpenNotificationAccess = {},
            onPullFromNightscout = {},
            onBack = onBack
        )
    }
}
```

- [ ] **Step 2: Update test assertions for unified Nightscout section**

The test `companion mode shows nightscout push section` should be renamed and updated:
```kotlin
@Test
fun `shows nightscout section for all modes`() {
    render(glucoseSource = GlucoseSource.COMPANION)
    composeRule.onNodeWithText("Nightscout URL").assertExists()
    composeRule.onNodeWithText("API Secret").assertExists()
}
```

The test `follower mode hides nightscout push section` should change — now the URL section is always visible:
```kotlin
@Test
fun `follower mode shows nightscout section and poll settings`() {
    render(glucoseSource = GlucoseSource.NIGHTSCOUT_FOLLOWER)
    composeRule.onNodeWithText("Nightscout URL").assertExists()
    composeRule.onNodeWithText("API Secret").assertExists()
    composeRule.onNodeWithText("Poll Interval: 60s").assertExists()
}
```

The test `follower mode shows following section with poll interval` can be merged into the above. Delete the original.

The test `librelinkup mode shows nightscout push section` should be renamed:
```kotlin
@Test
fun `librelinkup mode shows nightscout section`() {
    render(glucoseSource = GlucoseSource.LIBRELINKUP)
    composeRule.onNodeWithText("Nightscout URL").assertExists()
    composeRule.onNodeWithText("API Secret").assertExists()
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew testDebugUnitTest --tests "*.DataSourceSettingsTest"`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/psjostrom/strimma/ui/settings/DataSourceSettingsTest.kt
git commit -m "test: update DataSourceSettingsTest for unified Nightscout credentials"
```

---

### Task 9: Clean up dead string resources

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-sv/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`

- [ ] **Step 1: Remove unused string resources**

Remove from all `strings.xml` files:
- `settings_source_nightscout_push` — replaced by `settings_source_nightscout`
- `settings_source_following` — replaced by `settings_source_poll_settings`
- `settings_source_follower_url_placeholder` — URL now uses the unified placeholder
- `settings_source_follower_url_hint` — URL now uses the unified hint
- `setup_nightscout_toggle` — replaced by `setup_nightscout_desc`

- [ ] **Step 2: Verify no references remain**

Run: `grep -r "settings_source_nightscout_push\|settings_source_following\|settings_source_follower_url_placeholder\|settings_source_follower_url_hint\|setup_nightscout_toggle" app/src/main/`
Expected: No matches

- [ ] **Step 3: Full build and test**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values*/strings.xml
git commit -m "chore: remove dead string resources from follower/push split"
```

---

### Task 10: Final verification

- [ ] **Step 1: Run full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 2: Run lint**

Run: `./gradlew lintDebug`
Expected: No new warnings (check for unused resource warnings)

- [ ] **Step 3: Install and verify on device**

Run: `./gradlew installRelease`

Verify Settings screen:
1. Open Settings > Data Source
2. Confirm single "Nightscout" section with URL + secret fields (visible for all source modes)
3. Switch to Nightscout Follower — confirm poll interval slider appears below the Nightscout section
4. Switch back to Companion — confirm poll interval hides, Nightscout section remains

- [ ] **Step 4: Verify Setup wizard**

Clear app data to trigger the wizard (`adb shell pm clear com.psjostrom.strimma`), then relaunch.

Verify wizard flow:
1. Step 2 (Data Source): select Companion — no URL fields shown, can advance
2. Step 2: select Nightscout Follower — no URL fields shown (moved to step 3), can advance
3. Step 3 (Nightscout): URL + secret fields visible, connection test button, no push toggle
4. Step 3: leave URL blank — can advance (skip NS)
5. Step 3: enter URL + secret, test connection — can advance after success
6. Complete wizard, verify app starts correctly with the configured NS server
