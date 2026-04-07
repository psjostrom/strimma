# Tidepool Upload

Spec for uploading CGM glucose data to Tidepool's cloud platform. Tidepool is a nonprofit, vendor-neutral diabetes data platform used by clinics and endocrinologists to view standardized CGM/pump reports.

## Context

Strimma stores glucose readings locally and pushes to Nightscout. Tidepool upload adds a second cloud destination — useful for users whose clinics use Tidepool instead of (or alongside) LibreView/Dexcom Clarity.

This is a one-way upload. Strimma sends data to Tidepool, never reads from it.

## Sources

- Tidepool OpenAPI specs: `github.com/tidepool-org/TidepoolApi/reference/` (`auth.v2.yaml`, `data.v1.yaml`)
- Tidepool CBG data model: `developer.tidepool.org/data-model/device-data/types/cbg.html`
- xDrip implementation: `com.eveningoutpost.dexdrip.tidepool/` (GPLv3)
- xDrip OIDC migration issue: `github.com/NightscoutFoundation/xDrip/issues/2597`
- Tidepool lead dev (@toddkazakov) provided client IDs for xDrip and AAPS in Jan 2023

## Architecture

```
Room DB (GlucoseReading)
  -> TidepoolUploader (triggered on new reading, rate-limited)
    -> Auth: OIDC via AppAuth-Android (one-time browser login)
    -> POST /v1/datasets/{dataSetId}/data (cbg records)
      -> Tidepool cloud
```

This runs independently of NightscoutPusher. Both can be enabled simultaneously.

## Authentication

### Why OIDC, not legacy

Tidepool's legacy `POST /auth/login` is deprecated (marked for removal "by end of 2023" in their OpenAPI spec). It still works in 2026, but Tidepool actively wants apps to migrate. xDrip completed OIDC migration in Sep 2023. Building on deprecated auth is technical debt from day one.

### OIDC flow (Authorization Code with PKCE)

Uses AppAuth-Android (`net.openid.appauth`) — the same library xDrip and AAPS use.

**Prerequisites:** Contact Tidepool (support@tidepool.org or via GitHub) to register a client:
- Client ID: `strimma` (requested)
- Redirect URI: `strimma://callback/tidepool`
- Grant type: `authorization_code`

**Endpoints:**

| Auth base | API base |
|-----------|----------|
| `https://auth.tidepool.org/realms/tidepool` | `https://api.tidepool.org` |

OIDC discovery at `/.well-known/openid-configuration`.

**Flow:**

1. **Initial login** (one-time, in-app browser tab):
   ```
   GET {auth_base}/protocol/openid-connect/auth
     ?client_id=strimma
     &response_type=code
     &redirect_uri=strimma://callback/tidepool
     &scope=openid email offline_access
     &code_challenge={PKCE_challenge}
     &code_challenge_method=S256
   ```
   User enters Tidepool credentials in browser. Tidepool redirects to `strimma://callback/tidepool?code={authorization_code}`.

2. **Exchange code for tokens**:
   ```
   POST {auth_base}/protocol/openid-connect/token
   Content-Type: application/x-www-form-urlencoded

   grant_type=authorization_code
   &code={authorization_code}
   &redirect_uri=strimma://callback/tidepool
   &client_id=strimma
   &code_verifier={PKCE_verifier}
   ```
   Response: `{ "access_token": "...", "refresh_token": "...", "expires_in": 300 }`

3. **Get user ID**:
   ```
   GET {auth_base}/protocol/openid-connect/userinfo
   Authorization: Bearer {access_token}
   ```
   Response: `{ "sub": "{tidepool_user_id}", ... }`

4. **Refresh tokens** (background, before access token expires):
   ```
   POST {auth_base}/protocol/openid-connect/token
   Content-Type: application/x-www-form-urlencoded

   grant_type=refresh_token
   &refresh_token={refresh_token}
   &client_id=strimma
   ```
   Response: new access token + new refresh token. Store the new refresh token immediately.

### Token lifecycle

| Token | Lifetime | Storage |
|-------|----------|---------|
| Access token | 1-10 minutes (server-controlled) | In-memory only |
| Refresh token | ~2 weeks | EncryptedSharedPreferences |
| User ID (`sub`) | Permanent per user | DataStore |

- Refresh the access token proactively before it expires (use `expires_in` from the token response).
- Each refresh returns a new refresh token — always store the latest one.
- If refresh fails (`invalid_grant`), clear stored tokens and prompt for browser login again.
- In normal use (uploading at least once every 2 weeks), the user never needs to re-authenticate.

### Re-authentication triggers

- Refresh token expired (2+ weeks offline)
- User changed Tidepool password
- User revoked access server-side
- Server-side session invalidation

When re-auth is needed, show a notification: "Tidepool login required — tap to reconnect." Tapping opens the in-app browser flow.

## Data API

All data API calls use the access token:
```
x-tidepool-session-token: {access_token}
```

### Step 1: Get or create dataset

Check for existing open dataset:
```
GET /v1/users/{userId}/data_sets
  ?client.name=com.psjostrom.strimma
  &size=1
x-tidepool-session-token: {access_token}
```

If none returned, create one:
```
POST /v1/users/{userId}/data_sets
x-tidepool-session-token: {access_token}
Content-Type: application/json

{
  "type": "upload",
  "dataSetType": "continuous",
  "client": {
    "name": "com.psjostrom.strimma",
    "version": "{app_version}"
  },
  "deduplicator": {
    "name": "org.tidepool.deduplicator.dataset.delete.origin"
  },
  "deviceManufacturers": ["Abbott"],
  "deviceModel": "Libre 3",
  "deviceTags": ["cgm"],
  "time": "{ISO8601_UTC}",
  "computerTime": "{ISO8601_local_no_zone}",
  "timezoneOffset": {offset_minutes},
  "timezone": "{IANA_timezone}",
  "timeProcessing": "none",
  "version": "{app_version}"
}
```

Notes:
- `dataSetType: "continuous"` — keeps the dataset open for appending. No close step needed.
- `deviceManufacturers` and `deviceModel` — use the actual sensor manufacturer/model. For now, hardcode to the CGM source. In the future, could be derived from `GlucoseSource`.
- `deviceTags: ["cgm"]` — Strimma is CGM-only (xDrip uses `["bgm", "cgm", "insulin-pump"]`).
- `deduplicator` — uses `origin.id` on each datum for dedup. Safe to retry failed uploads.

Response includes `uploadId` — persist this in DataStore for reuse.

### Step 2: Upload glucose data

```
POST /v1/datasets/{dataSetId}/data
x-tidepool-session-token: {access_token}
Content-Type: application/json

[
  {
    "type": "cbg",
    "units": "mg/dL",
    "value": 142,
    "time": "2026-03-23T14:30:00.0000000Z",
    "deviceTime": "2026-03-23T15:30:00",
    "timezoneOffset": 60,
    "origin": {
      "id": "strimma-cbg-1711234567890"
    }
  },
  ...
]
```

### CBG record format

| Field | Type | Required | Source |
|-------|------|----------|--------|
| `type` | String | Yes | Always `"cbg"` |
| `units` | String | Yes | Always `"mg/dL"` (Tidepool converts internally to mmol/L) |
| `value` | Int | Yes | `GlucoseReading.sgv` (already mg/dL) |
| `time` | String | Yes | UTC ISO 8601: `yyyy-MM-dd'T'HH:mm:ss.SSS'0000Z'` |
| `deviceTime` | String | Optional | Local time without zone: `yyyy-MM-dd'T'HH:mm:ss` |
| `timezoneOffset` | Int | Yes | Minutes from UTC (e.g., `60` for CET, `120` for CEST) |
| `origin.id` | String | Yes | Deterministic ID for dedup: `"strimma-cbg-{timestamp}"` |

### Validation

Tidepool rejects the **entire batch** if any single record is invalid. Filter before uploading:
- Glucose value: 39-500 mg/dL (xDrip uses this range, Tidepool spec allows 0-1000 but values outside 39-500 are sensor errors)
- Timestamp: must not be in the future
- Timestamp: must not be before 2020

### Deduplication

Use deterministic `origin.id` values: `"strimma-cbg-{reading.date}"` where `date` is the Unix timestamp in millis. This ensures:
- Safe retry on network failure (same records get the same IDs)
- No duplicates if the user reinstalls or re-syncs

## Upload scheduling

### Trigger

On each new glucose reading arriving in Room DB, check if Tidepool upload is enabled. Rate-limit to at most once per 20 minutes (xDrip uses `pratelimit("tidepool-new-data-upload", 1200)`).

### Chunking

Upload readings in time-windowed chunks:
- Max chunk window: **7 days** (Tidepool's limit per request)
- Track `lastUploadEnd` timestamp in DataStore
- Each upload: query Room for readings between `lastUploadEnd` and `now - 15 min` (15-min buffer to let readings settle)
- On success, advance `lastUploadEnd` to the end of the uploaded window
- On first enable or empty history: look back at most 2 months

### Conditions (optional, configurable)

| Setting | Default | Effect |
|---------|---------|--------|
| Only while charging | Off | Skip upload if not plugged in |
| Only on Wi-Fi | Off | Skip upload if on metered connection |

These are user preferences, not hard requirements. Glucose data is tiny (~100 bytes per reading, ~30KB/day).

## Integration with Strimma

### New files

```
tidepool/
  TidepoolUploader.kt      — Upload orchestration (auth, dataset, upload, chunking)
  TidepoolModels.kt         — Data classes (CBG record, dataset request/response, token response)
  TidepoolDateUtil.kt       — ISO 8601 UTC/local formatting, timezone offset
```

### New dependency

```kotlin
// build.gradle.kts
implementation("net.openid:appauth:0.11.1")
```

### Modified files

```
ui/SettingsScreen.kt        — Tidepool settings section
service/StrimmaService.kt   — Trigger TidepoolUploader.newData() on new reading
AndroidManifest.xml          — Add intent filter for strimma://callback/tidepool redirect
```

### Settings (DataStore + EncryptedSharedPreferences)

| Key | Type | Storage | Default |
|-----|------|---------|---------|
| `tidepool_enabled` | Boolean | DataStore | false |
| `tidepool_only_while_charging` | Boolean | DataStore | false |
| `tidepool_only_while_wifi` | Boolean | DataStore | false |
| `tidepool_access_token` | String | Memory only | "" |
| `tidepool_refresh_token` | String | EncryptedSharedPreferences | "" |
| `tidepool_user_id` | String | DataStore | "" |
| `tidepool_dataset_id` | String | DataStore | "" |
| `tidepool_last_upload_end` | Long | DataStore | 0 |

## Settings UI

New section in Settings: **Tidepool** (collapsible group).

- **Enable Tidepool upload** (toggle)
- **Login** (button) — opens in-app browser for OIDC auth. Shows "Connected as {email}" or "Not connected" based on whether refresh token exists.
- **Disconnect** (button, visible when connected) — clears stored tokens, revokes refresh token at Tidepool's revocation endpoint.
- **Environment** (Production / Integration toggle) — visible only in developer settings.
- **Only while charging** (toggle)
- **Only on Wi-Fi** (toggle)
- **Status** — last upload time, or error message. Updated after each upload attempt.

## Manifest changes

```xml
<!-- Handle OIDC redirect -->
<activity
    android:name=".tidepool.TidepoolAuthActivity"
    android:exported="true"
    android:launchMode="singleTask">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data
            android:scheme="strimma"
            android:host="callback"
            android:path="/tidepool" />
    </intent-filter>
</activity>
```

## Error handling

| Scenario | Behavior |
|----------|----------|
| Network unavailable | Skip, retry on next trigger (20-min rate limit) |
| Access token expired | Refresh using stored refresh token |
| Refresh token expired | Show notification prompting re-login |
| 400 on data upload | Log the error, skip this batch (likely invalid record — check validation) |
| 401/403 on any call | Invalidate tokens, prompt re-login |
| 500 from Tidepool | Retry on next trigger |
| Empty batch (no new readings) | Advance `lastUploadEnd`, no API call |

## Client Registration

Client ID `strimma` is registered on Tidepool's production Keycloak realm by @toddkazakov.
- Client ID: `strimma`
- Redirect URI: `strimma://callback/tidepool`
- Scopes: `openid email offline_access`

## Not in scope

- Treatment uploads (bolus, carbs, basal) — Strimma is CGM-only for Tidepool. Treatments are tracked in AAPS/CamAPS/mylife.
- Blood glucose meter readings — not a data source Strimma handles.
- Reading data from Tidepool — this is upload-only.
- Legacy `POST /auth/login` — deprecated, not worth building on.
