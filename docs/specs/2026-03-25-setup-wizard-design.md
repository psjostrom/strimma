# Setup Wizard — Design Spec

**Date:** 2026-03-25
**Status:** Draft

## Summary

First-time setup wizard that guides new users through the minimum configuration needed before Strimma is useful. No bullshit welcome screens — every step requires a meaningful decision.

## Trigger

- New `SETUP_COMPLETED` boolean in DataStore (default `false`)
- `MainActivity` checks on launch: if `false`, navigate to `SetupScreen` instead of `MainScreen`
- Wizard sets `SETUP_COMPLETED = true` on final step
- Settings remain editable after setup — the wizard just front-loads the critical ones

## Flow

### Step 1: Units

**What:** mmol/L or mg/dL toggle.

**Why first:** Every subsequent screen shows glucose values. Wrong unit = immediate confusion.

**UI:** Two large tappable cards, one pre-selected based on device locale (mmol/L for most of Europe/Australia, mg/dL for US/Japan). User taps to confirm or switch. Single "Next" button.

**Writes:** `glucose_unit` to DataStore.

### Step 2: Data Source

**What:** How does Strimma get glucose data?

**Options (radio cards with icon + description):**

1. **CGM App Notifications** (COMPANION) — "Reads glucose from your CGM app's notifications. Works with Dexcom, Libre, CamAPS, Juggluco, xDrip+, and 50+ more."
2. **xDrip Broadcast** (XDRIP_BROADCAST) — "Receives glucose via xDrip-compatible broadcast intent. Use if your CGM app supports xDrip broadcasting."
3. **Nightscout Follower** (NIGHTSCOUT_FOLLOWER) — "Follows a remote Nightscout server. Use to monitor someone else's glucose."

**On selection of COMPANION:** Check `GlucoseNotificationListener.isEnabled()`. If not granted, show inline prompt explaining why notification access is needed ("Strimma needs notification access to read glucose values from your CGM app") with a button that calls `GlucoseNotificationListener.openSettings()`. Show green checkmark when permission is granted (re-check on resume). Block "Next" until granted.

**On selection of NIGHTSCOUT_FOLLOWER:** Show inline URL + secret fields (same as step 3's Nightscout config, but for the follower endpoint). Test connection before allowing "Next".

**Writes:** `glucose_source` to DataStore + sync prefs.

### Step 3: Nightscout Push (Optional)

**What:** Push readings to a Nightscout server?

**UI:** Toggle "Push to Nightscout" at top. Default off. When enabled, show:
- URL text field (with `https://` hint)
- API Secret field (password masked)
- "Test Connection" button

**Test Connection:** POST a test entry (or GET `/api/v1/status.json`) to validate URL + secret. Show result inline:
- Green: "Connected to [server name]"
- Red: "Connection failed: [reason]" (timeout, 401 unauthorized, DNS failure, etc.)

Block "Next" while toggle is on but connection untested or failed. Allow "Next" immediately if toggle is off (skip).

**Writes:** `nightscout_url`, encrypted `nightscout_secret`, push enabled flag.

### Step 4: Alerts

**What:** Review alert thresholds. All enabled by default with clinically reasonable values.

**UI:** Compact list showing all 5 alert types with enable toggle + threshold value:

| Alert | Default | |
|-------|---------|---|
| Urgent Low | 3.0 mmol/L / 54 mg/dL | toggle |
| Low | 4.0 mmol/L / 72 mg/dL | toggle |
| High | 10.0 mmol/L / 180 mg/dL | toggle |
| Urgent High | 13.0 mmol/L / 234 mg/dL | toggle |
| Stale Data | 10 min | toggle |

Thresholds shown in the unit selected in step 1. Tapping a threshold opens an inline editor (same as AlertsSettings).

Predictive alerts (Low Soon, High Soon) shown as secondary toggles below, defaults on.

**Footer text:** "You can adjust these anytime in Settings > Alerts."

**Writes:** Alert enable flags + thresholds to DataStore.

### Step 5: Permissions & Done

**What:** Request remaining system permissions + start the service.

**UI:** Checklist of requirements with status indicators:

- [ ] Notification permission (POST_NOTIFICATIONS) — needed for BG display + alerts
- [ ] Battery optimization exemption — needed for reliable background operation
- [ ] Notification access (if COMPANION) — already granted in step 2, shown as checkmark

Each unchecked item has a "Grant" button. Items already granted show green checkmark.

When all critical permissions are granted, show a prominent "Start Strimma" button that:
1. Sets `SETUP_COMPLETED = true`
2. Starts `StrimmaService`
3. Navigates to `MainScreen`

If user skips optional permissions, still allow completion but show a warning ("Strimma may not work reliably without battery optimization exemption").

## Design

### Navigation

- Horizontal pager with step indicators (dots or numbered pills at top)
- Back button returns to previous step (except step 1 which shows nothing)
- No skipping ahead — steps are sequential because later steps depend on earlier choices (units affect threshold display, data source affects which permissions are needed)
- System back from step 1 exits the app (no main screen to fall back to yet)

### Visual Style

- Same Material 3 theme as the rest of the app, follows system default during setup (user hasn't chosen yet)
- Card-based selection (like DataSourceSettings radio cards)
- Step title at top (e.g., "Units", "Data Source"), no subtitle fluff
- Consistent "Next" / "Back" button placement at bottom

### State Persistence

- Each step writes to DataStore immediately on "Next" (not batched at end)
- If the app is killed mid-wizard, it resumes from the last incomplete step
- Track progress via `setup_step` integer in DataStore (0-4). On launch, if `SETUP_COMPLETED == false`, navigate to step `setup_step`.

## Architecture

### New Files

- `ui/setup/SetupScreen.kt` — HorizontalPager host with step indicators
- `ui/setup/SetupUnitsStep.kt` — Step 1
- `ui/setup/SetupDataSourceStep.kt` — Step 2
- `ui/setup/SetupNightscoutStep.kt` — Step 3
- `ui/setup/SetupAlertsStep.kt` — Step 4
- `ui/setup/SetupPermissionsStep.kt` — Step 5
- `ui/setup/SetupViewModel.kt` — Shared ViewModel for wizard state

### Modified Files

- `data/SettingsRepository.kt` — Add `SETUP_COMPLETED` and `SETUP_STEP` keys
- `ui/MainActivity.kt` — Add setup route, gate navigation on `SETUP_COMPLETED`
- `network/NightscoutClient.kt` — Add `testConnection()` method (GET `/api/v1/status.json`)

### ViewModel

`SetupViewModel` holds:
- Current step (observed from DataStore)
- Validation state per step (can proceed?)
- Permission check results (observed via lifecycle)

Does NOT duplicate settings state — reads/writes directly through `SettingsRepository`. The wizard steps are thin UI over the same settings infrastructure that `SettingsScreen` uses.

## Edge Cases

- **Reinstall / clear data:** `SETUP_COMPLETED` resets to false, wizard runs again. Previous Nightscout data on server is unaffected.
- **App update (existing user):** DataStore migration unconditionally sets `SETUP_COMPLETED = true`. Only fresh installs (no existing DataStore) see the wizard.
- **Notification access revoked after setup:** Not the wizard's problem. `DataSourceSettings` already shows the status. Could add a MainScreen banner later.
- **Locale-based unit detection:** Best-effort. Map `Locale.getDefault().country` to unit preference. US/JP/AG/... → mg/dL, everything else → mmol/L. User can override in step 1.

## Not In Scope

- Feature tour / contextual hints (future, separate feature)
- Onboarding analytics
- Treatment setup (requires Nightscout to be working first)
- Exercise / Health Connect setup (niche, not first-launch critical)
- Theme selection (system default is fine for setup, configurable in Display settings after)
