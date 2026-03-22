# Nightscout API Compliance

Strimma is a fully compliant Nightscout client. This page documents the exact API interactions for developers and server administrators.

---

## Design Principle

Strimma follows the Nightscout API specification exactly. If a Nightscout server can't handle Strimma's requests, the server needs to be fixed — Strimma will never weaken its compliance to work around server bugs.

---

## Endpoints

### Push Readings

Note: POST endpoints do **not** use the `.json` suffix — this is standard Nightscout behavior. Only GET endpoints use `.json`.

```
POST /api/v1/entries
Content-Type: application/json
api-secret: <sha1-hash>

[
  {
    "sgv": 108,
    "date": 1711029600000,
    "dateString": "2025-03-21T14:00:00.000Z",
    "direction": "Flat",
    "delta": -2.3,
    "type": "sgv",
    "device": "Strimma"
  }
]
```

- Body is a JSON array of entry objects
- `sgv` is an integer in mg/dL
- `date` is Unix timestamp in milliseconds
- `dateString` is ISO 8601 UTC
- `direction` uses Nightscout standard names: DoubleDown, SingleDown, FortyFiveDown, Flat, FortyFiveUp, SingleUp, DoubleUp, NONE
- `delta` is the smoothed SGV change in mg/dL over a ~5 minute window (see [Extension Fields](#extension-fields))
- `device` is always "Strimma"

### Fetch Readings

```
GET /api/v1/entries.json?find[date][$gt]=<timestamp>&count=<n>
api-secret: <sha1-hash>
```

- `.json` suffix is required (Nightscout convention)
- Query parameters use MongoDB-style operators
- `find[date][$gt]` filters entries newer than the timestamp (milliseconds)
- `count` limits the number of results (default 2016)

### Fetch Treatments

```
GET /api/v1/treatments.json?find[created_at][$gte]=<iso-timestamp>&count=100
api-secret: <sha1-hash>
```

- `.json` suffix is required
- `find[created_at][$gte]` filters treatments created at or after the ISO 8601 timestamp
- `count` is fixed at 100 per request
- Returns 404 gracefully (empty list) if the server doesn't support treatments

---

## Authentication

All requests include the `api-secret` header with a **SHA-1 hash** of the plain-text API secret:

```
api-secret: da39a3ee5e6b4b0d3255bfef95601890afd80709
```

This matches the standard Nightscout authentication method. The plain-text secret is never sent over the network.

---

## Treatment Data Model

Strimma reads these fields from treatment objects:

| Field | Type | Description |
|-------|------|-------------|
| `_id` | String | Unique identifier (UUID). If missing, Strimma generates a SHA-1 hash from `created_at\|eventType\|insulin\|carbs` |
| `eventType` | String | Treatment type: "bolus", "carb", "basal", etc. |
| `created_at` | String | ISO 8601 timestamp when the treatment was administered |
| `insulin` | Double? | Insulin dose in units (null if not applicable) |
| `carbs` | Double? | Carbohydrates in grams (null if not applicable) |
| `absolute` | Double? | Basal rate in U/h (null if not applicable) |
| `duration` | Int? | Duration in minutes for basal/temp basal (null otherwise) |
| `enteredBy` | String? | Source of the entry (e.g., "CamAPS FX") |

---

## Entry Data Model

Strimma reads these fields from entry objects:

| Field | Type | Description |
|-------|------|-------------|
| `sgv` | Int | Sensor Glucose Value in mg/dL |
| `date` | Long | Unix timestamp in milliseconds |
| `type` | String | Must be "sgv" (other types ignored) |
| `direction` | String? | Trend direction (optional, Strimma recomputes locally) |

---

## Validation Rules

- Entry `type` must be "sgv" (non-SGV entries are ignored)
- SGV must be in range **18–900 mg/dL**
- Entries outside this range are rejected
- Timestamp parsing attempts `OffsetDateTime.parse()` first, falls back to ISO 8601 format

---

## Deduplication

When receiving entries (from pull or follower mode):

- Readings within **3 seconds** of an existing reading are treated as duplicates
- A **15-minute lookback window** is used for duplicate detection

---

## Extension Fields

Strimma includes one field beyond the standard Nightscout entry schema:

| Field | Type | Description |
|-------|------|-------------|
| `delta` | Double? | Smoothed SGV change in mg/dL over a ~5 minute window. Computed from 3-point averaged SGV values using EASD/ISPAD thresholds. Null when insufficient history is available (e.g., first reading after a sensor start). |

Standard Nightscout servers ignore unknown fields, so this does not break compatibility. The `delta` field is included because direction and delta express the same underlying trend — direction as a categorical label, delta as the numeric value. Both are derived from the same computation, so shipping both avoids forcing consumers to reconstruct delta from raw SGV values.

Servers that understand `delta` can store and return it directly. Servers that don't will silently drop it.

---

## Compatibility

Strimma has been tested with:

- Standard Nightscout (cgm-remote-monitor)
- Custom Nightscout-compatible servers
- Springa (Nightscout-compatible endpoint)

Any server implementing the Nightscout entries and treatments API should work with Strimma.
