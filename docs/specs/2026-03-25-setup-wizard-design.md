# Setup Wizard — Design Spec

**Date:** 2026-03-25
**Status:** Implemented

## Summary

First-time setup wizard that guides new users through the minimum configuration needed before Strimma is useful. A brief welcome step orients the user, then every subsequent step requires a meaningful decision.

Nightscout push configuration is optional and complex enough to belong in Settings rather than the first-run wizard. Users who need it can configure it in Settings > Nightscout after completing setup.

## Trigger

- New `SETUP_COMPLETED` boolean in DataStore (default `false`)
- `MainActivity` checks on launch: if `false`, navigate to `SetupScreen` instead of `MainScreen`
- Wizard sets `SETUP_COMPLETED = true` on final step
- Settings remain editable after setup — the wizard just front-loads the critical ones

## Flow

### Step 0: Welcome

**What:** Brief orientation showing what the wizard will configure.

**UI:** Short body text followed by a list of items with icons (ShowChart, Sync, Notifications, Settings) describing what's ahead: data source, Nightscout sync, alerts, and permissions. No decisions required — purely informational.

**Writes:** Nothing.

### Step 1: Units

**What:** mmol/L or mg/dL toggle.

**Why first:** Every subsequent screen shows glucose values. Wrong unit = immediate confusion.

**UI:** Two large tappable cards with radio buttons, one pre-selected based on device locale (mmol/L for most of Europe/Australia, mg/dL for US/Japan). Each card shows the unit label and a description of where it's used. User taps to confirm or switch. Single "Next" button.

**Writes:** `glucose_unit` to DataStore.

### Step 2: Data Source

**What:** How does Strimma get glucose data?

**Options (radio cards with description):**

1. **CGM App Notifications** (COMPANION) — "Reads glucose from your CGM app's notifications. Works with Dexcom, Libre, CamAPS, Juggluco, xDrip+, and many more."
2. **xDrip Broadcast** (XDRIP_BROADCAST) — "Receives glucose via xDrip-compatible broadcast intent. Use if your CGM app supports xDrip broadcasting."
3. **Nightscout Follower** (NIGHTSCOUT_FOLLOWER) — "Follows a remote Nightscout server. Use to monitor someone else's glucose."
4. **LibreLinkUp** (LIBRELINKUP) — Polls Abbott's LibreLinkUp API. Shows inline email + password fields when selected.

**On selection of COMPANION:** Show a 3-step guided notification access flow in an inline card:
1. "Open Notification Access" button — user attempts to enable (will be blocked by Android's restricted settings on sideloaded apps).
2. "Open App Info" button — user taps "Allow restricted settings" on the app info page.
3. "Open Notification Access" button again — now the toggle works.

Shows a green checkmark with "Notification access granted" when permission is confirmed. A "Learn more" link opens the docs page explaining the process. Block "Next" until notification access is granted.

**On selection of LIBRELINKUP:** Show inline email and password fields for LibreLinkUp credentials, stored in encrypted storage.

**On selection of NIGHTSCOUT_FOLLOWER or XDRIP_BROADCAST:** No additional inline config — "Next" is immediately available.

**Writes:** `glucose_source` to DataStore (+ LLU credentials to encrypted storage if applicable).

### Step 3: Alerts

**What:** Review alert thresholds. All enabled by default with clinically reasonable values.

**UI:** Two Surface cards. The first contains all 5 alert types in a compact list with enable toggle + inline threshold editor (for glucose alerts):

| Alert | Default | |
|-------|---------|---|
| Urgent Low | 3.0 mmol/L / 54 mg/dL | toggle + threshold |
| Low | 4.0 mmol/L / 72 mg/dL | toggle + threshold |
| High | 10.0 mmol/L / 180 mg/dL | toggle + threshold |
| Urgent High | 13.0 mmol/L / 234 mg/dL | toggle + threshold |
| Stale Data | — | toggle only |

Thresholds shown in the unit selected in step 1. When enabled, an inline `OutlinedTextField` (80.dp wide, decimal keyboard) allows editing the threshold value.

The second card contains predictive alerts (Low Soon, High Soon) as simple toggles, defaults on.

**Footer text:** "You can adjust these anytime in Settings > Alerts."

**Writes:** Alert enable flags + thresholds to DataStore.

### Step 4: Permissions & Done

**What:** Request remaining system permissions + start the service.

**UI:** Checklist of requirements with status indicators:

- [ ] Notification permission (POST_NOTIFICATIONS) — needed for BG display + alerts
- [ ] Battery optimization exemption — needed for reliable background operation
- [ ] Notification access (if COMPANION) — already granted in step 2, shown as checkmark

Each unchecked item has a "Grant" button. Items already granted show green checkmark with "Granted" label.

Critical permissions: notification permission + notification access (if COMPANION mode). Battery optimization is optional.

When all critical permissions are granted, show a prominent full-width "Start Strimma" button that:
1. Sets `SETUP_COMPLETED = true`
2. Starts `StrimmaService`
3. Navigates to `MainScreen`

If battery optimization exemption is not granted, show a warning text ("Strimma may not work reliably without battery optimization exemption"). The "Start Strimma" button is still enabled as long as critical permissions are met.

## Design

### Navigation

- Horizontal pager (HorizontalPager, `userScrollEnabled = false`) with dot indicators at top (active dot slightly larger, InRange color, completed dots at 40% alpha)
- Back button returns to previous step (except step 0 which shows nothing — a spacer fills the left side)
- No skipping ahead — steps are sequential because later steps depend on earlier choices (units affect threshold display, data source affects which permissions are needed)
- System back from step 0 exits the app (no main screen to fall back to yet)
- The final step (Permissions) has no Next/Back buttons — it uses its own full-width "Start Strimma" button instead

### Visual Style

- Same Material 3 theme as the rest of the app, follows system default during setup (user hasn't chosen yet)
- Card-based selection (like DataSourceSettings radio cards)
- Step title at top (e.g., "Units", "Data Source"), no subtitle fluff
- Consistent "Next" / "Back" button placement at bottom

### State Persistence

- Each step writes to DataStore immediately on "Next" (not batched at end)
- If the app is killed mid-wizard, it resumes from the last incomplete step
- Track progress via `setup_step` integer in DataStore (0-4, matching the 5 steps). On launch, if `SETUP_COMPLETED == false`, navigate to step `setup_step`.

## Architecture

### New Files

- `ui/setup/SetupScreen.kt` — HorizontalPager host with dot indicators and navigation buttons
- `ui/setup/SetupWelcomeStep.kt` — Step 0 (welcome/orientation)
- `ui/setup/SetupUnitsStep.kt` — Step 1 (unit selection + locale-based default via `defaultUnitForLocale()`)
- `ui/setup/SetupDataSourceStep.kt` — Step 2 (data source selection + inline COMPANION notification access guide + inline LLU credentials)
- `ui/setup/SetupAlertsStep.kt` — Step 3 (alert thresholds + predictive toggles)
- `ui/setup/SetupPermissionsStep.kt` — Step 4 (permission checklist + "Start Strimma" button)
- `ui/setup/SetupViewModel.kt` — Shared ViewModel for wizard state (delegates to SettingsRepository)

### Modified Files

- `data/SettingsRepository.kt` — Add `SETUP_COMPLETED` and `SETUP_STEP` keys
- `ui/MainActivity.kt` — Add setup route, gate navigation on `SETUP_COMPLETED`

### ViewModel

`SetupViewModel` holds:
- `glucoseUnit` — StateFlow from SettingsRepository
- `glucoseSource` — StateFlow from SettingsRepository
- `setupStep` — StateFlow (int, 0-4) from SettingsRepository
- `lluEmail` / `lluPassword` — LibreLinkUp credentials (read/write via SettingsRepository encrypted storage)

Alerts state is handled by a separate `AlertsViewModel` (hiltViewModel) instantiated in `SetupScreen`.

Does NOT duplicate settings state — reads/writes directly through `SettingsRepository`. The wizard steps are thin UI over the same settings infrastructure that `SettingsScreen` uses. Permission states are passed in from `MainActivity` (not observed in the ViewModel).

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
