# Local Web Server — Design Spec

Strimma local web server: Nightscout-compatible HTTP API serving glucose data, treatments, and status to Garmin watchfaces and other local clients.

## Context

xDrip+ includes a local web server (`127.0.0.1:17580`) that third-party apps — watchfaces, widgets, automation tools — fetch BG data from. For Garmin watchfaces, the HTTP requests are proxied by the Garmin Connect Mobile app running on the same phone, so they hit localhost.

Strimma replaces xDrip+ as the CGM data source. This feature makes Strimma a drop-in replacement for xDrip's local web server, so existing third-party apps and Nightscout-compatible tools work without modification.

## Server

- **Engine:** Ktor Server with CIO engine (same Ktor version 3.0.3 already used for the HTTP client)
- **Port:** 17580 (xDrip-compatible)
- **Binding:** `0.0.0.0` (all interfaces)
- **Protocol:** HTTP only (no HTTPS — self-signed certs cause trust issues with Garmin's HTTP proxy, and no existing consumer uses xDrip's HTTPS port)

### Dependencies

Add to `libs.versions.toml` (all using existing `ktor = "3.0.3"`):
- `ktor-server-core`
- `ktor-server-cio`
- `ktor-server-content-negotiation`

## Authentication

Per-connection check based on source address:

| Source | Auth |
|--------|------|
| `127.0.0.1` or `::1` (loopback) | None required |
| Any other address | `api-secret` header required |

The `api-secret` header value must be the SHA-1 hex hash of the configured secret (same scheme as Nightscout/xDrip). Reuses the existing `hashSecret()` logic from `NightscoutClient`.

If no secret is configured and a non-loopback request arrives, reject with 403 (prevents accidental open access).

All responses include `Access-Control-Allow-Origin: *`.

## Endpoints

### GET `/sgv.json` and `/api/v1/entries/sgv.json`

Serves glucose readings as a Nightscout-compatible JSON array, newest first. Values always in mg/dL.

**Query parameters:**

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `count` | int | 24 | Number of readings (1-1000) |
| `brief_mode` | flag | N | Omit verbose fields |
| `all_data` | flag | N | Accepted, no-op (Strimma has one sensor session) |
| `steps` | int | — | Accepted, discarded. Returns `steps_result: 200` in first entry |
| `heart` | int | — | Accepted, discarded. Returns `heart_result: 200` in first entry |

**Response (all entries):**

```json
{
  "date": 1715263007650,
  "sgv": 168,
  "delta": 3.2,
  "direction": "FortyFiveUp"
}
```

**Non-brief mode adds:**

```json
{
  "_id": "1715263007650",
  "device": "Strimma",
  "dateString": "2026-03-20T14:30:07.650Z",
  "sysTime": "2026-03-20T14:30:07.650Z",
  "type": "sgv"
}
```

**First entry adds:**

```json
{
  "units_hint": "mmol",
  "iob": 2.45,
  "steps_result": 200,
  "heart_result": 200
}
```

- `units_hint` — always present, from user's glucose unit setting ("mmol" or "mgdl")
- `iob` — computed via existing `IOBComputer` when treatments sync is enabled, omitted otherwise
- `steps_result` / `heart_result` — only present when corresponding query param was sent

**Field mapping from Strimma data:**

| JSON field | Source |
|-----------|--------|
| `date` | `reading.ts` |
| `sgv` | `reading.sgv` (already mg/dL) |
| `delta` | `reading.deltaMmol * 18.0182` (convert mmol/L delta to mg/dL) |
| `direction` | `reading.direction` (already Nightscout direction names) |
| `_id` | `reading.ts` as string |
| `iob` | `IOBComputer.computeIOB()` using synced treatments |

No `filtered`, `unfiltered`, `rssi`, or `noise` fields — Strimma receives calibrated values, not raw sensor signals.

### GET `/status.json`

```json
{
  "settings": {
    "units": "mmol",
    "thresholds": {
      "bgHigh": 180,
      "bgLow": 72
    }
  }
}
```

- `units` — "mmol" or "mgdl" from user setting
- Thresholds always in mg/dL (Nightscout convention), converted from mmol/L setting

### GET `/treatments.json` and `/api/v1/treatments.json`

**Query parameters:**

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `count` | int | 24 | Number of treatments (1-100) |

**Response:**

```json
[
  {
    "_id": "abc123",
    "created_at": "2026-03-20T14:30:07.650Z",
    "eventType": "Meal Bolus",
    "enteredBy": "CamAPS",
    "insulin": 2.5,
    "carbs": 45.0
  }
]
```

Note: `created_at` is an ISO 8601 string (Nightscout spec). xDrip's local server uses a long here, which is a deviation from the spec.

### GET `/heart/set/{bpm}/{accuracy}`

Accepts and discards. Returns `200 OK` with text body `"OK"`.

### GET `/steps/set/{value}`

Accepts and discards. Returns `200 OK` with text body `"OK"`.

### Not implemented

| Endpoint | Reason |
|----------|--------|
| `/pebble` | No consumers — Garmin watchfaces use `/sgv.json` |
| `/tasker/*` | Not used |
| `/sync/*` | xDrip peer-to-peer protocol, not relevant |
| `/Libre2ConnectCode.json` | Strimma doesn't do direct BLE |

## Settings

New settings in `SettingsRepository`:

| Setting | Type | Storage | Default |
|---------|------|---------|---------|
| `webServerEnabled` | Boolean | DataStore | `false` |
| `webServerSecret` | String | EncryptedSharedPreferences | `""` |

UI: New section in settings (Integration group) with:
- "Local Web Server" toggle
- "Web Server Secret" text field (visible when server is enabled)

## Lifecycle

The server is managed by `StrimmaService`, following the same pattern as follower mode and treatment sync:

1. Collect `webServerEnabled` flow
2. When `true` → start Ktor server
3. When `false` → stop server
4. `onDestroy()` → stop server

## Package Structure

New package: `webserver/`

```
webserver/
  LocalWebServer.kt     — Ktor server setup, start/stop, auth plugin
  SgvRoute.kt           — /sgv.json endpoint
  StatusRoute.kt        — /status.json endpoint
  TreatmentsRoute.kt    — /treatments.json endpoint
  HealthRoute.kt        — /heart/set and /steps/set (accept + discard)
```

## Future Work

- **COB (Carbs on Board):** Treatment carb entries exist in Room. Computing COB requires an absorption model (linear decay as a starting point). Would populate a `cob` field in the `/sgv.json` first entry.
- **TBR (Temp Basal Rate):** Treatment data includes temp basal events (`basalRate`, `duration`). Displaying as a percentage requires the user's basal profile, which Strimma doesn't have. Could show absolute rate.
- **HR/Steps storage:** Currently accepted and discarded at `/heart/set`, `/steps/set`, and via `?heart=`/`?steps=` query params. Add Room tables and stats integration when there's a consumer for this data.
