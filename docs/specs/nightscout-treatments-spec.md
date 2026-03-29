# Nightscout Treatments Integration

Spec for adding treatment data (IOB, boluses, carbs, basal) to Strimma and Springa.

## Context

Strimma is an open-source CGM app. Its users have Nightscout instances. Strimma speaks Nightscout protocol — that's the contract. Users point Strimma at their NS server and it just works.

For Per's setup specifically, Springa acts as the NS server. It already handles `POST /api/v1/entries` (glucose push from Strimma) and `GET /api/sgv` (Garmin watches). It gets treatment data from mylife Cloud via a web scraper. It needs to expose that data via NS-compatible endpoints so Strimma can consume it like any other NS instance.

## Architecture

```
Any user:
  Strimma ──push entries──→ their Nightscout instance
  Strimma ←─read treatments── their Nightscout instance

Per's setup:
  CamAPS FX → mylife Cloud → Springa (scraper) → Turso DB
  Strimma ──push entries──→ Springa /api/v1/entries (existing)
  Strimma ←─read treatments── Springa /api/v1/treatments (new)
```

Strimma's code is identical in both cases. It talks to a Nightscout URL.

## Part 1: Strimma (Android app)

### 1.1 What to fetch

Poll `GET /api/v1/treatments.json` from the configured Nightscout URL. Same URL and auth (api-secret header) already used for pushing entries.

Query: `?find[created_at][$gte]=<ISO8601>&count=<N>`

Where `$gte` is 30 days ago and count scales with the lookback period (300 per day to accommodate looping systems that generate frequent temp basals).

### 1.2 Nightscout treatment JSON format

Treatments are free-form JSON objects. The fields Strimma cares about:

```json
{
  "eventType": "Meal Bolus",
  "created_at": "2026-03-19T08:42:00+01:00",
  "insulin": 5.0,
  "carbs": 60,
  "notes": "",
  "enteredBy": "CamAPS"
}
```

Relevant `eventType` values:

| eventType | Fields used | What it means |
|-----------|-------------|---------------|
| `Meal Bolus` | insulin, carbs | Meal bolus (may have both insulin + carbs) |
| `Correction Bolus` | insulin | Correction-only bolus |
| `Snack Bolus` | insulin, carbs | Small meal/snack |
| `Carb Correction` | carbs | Carbs without insulin (hypo treatment) |
| `Combo Bolus` | insulin, carbs | Extended/dual wave bolus |
| `Temp Basal` | absolute, duration | Temporary basal rate (U/h, minutes) |

Strimma should accept ANY eventType but only extract `insulin`, `carbs`, `absolute`, `duration`, and `created_at`. Unknown event types with insulin or carb values are still useful.

### 1.3 Data model (Room)

```kotlin
@Entity(tableName = "treatments")
data class Treatment(
    @PrimaryKey
    val id: String,               // NS _id or hash of (created_at + eventType + insulin + carbs)
    val createdAt: Long,          // timestamp ms
    val eventType: String,        // raw NS eventType
    val insulin: Double?,         // units, null if no insulin
    val carbs: Double?,           // grams, null if no carbs
    val basalRate: Double?,       // U/h for Temp Basal
    val duration: Int?,           // minutes for Temp Basal
    val enteredBy: String?,       // source identifier
    val fetchedAt: Long           // when Strimma fetched this (for cache management)
)
```

DAO queries needed:
- `since(timestamp)` — all treatments after a time (for graph overlay)
- `insulinSince(timestamp)` — treatments with insulin != null (for IOB computation)
- `deleteOlderThan(timestamp)` — prune (keep 30 days)

### 1.4 IOB computation

Compute locally in Strimma. Simple exponential decay, same model used in Springa and Nightscout:

```
IOB(t) = dose * (1 + t/tau) * exp(-t/tau)
```

Where `tau` is configurable (default 55 min for Fiasp, 75 min for NovoRapid/Humalog). Sum over all insulin treatments in the last `5 * tau` minutes.

Settings needed:
- **Insulin type** — enum: Fiasp (tau=55), Lyumjev (tau=50), NovoRapid/Humalog (tau=75), custom
- **DIA** — auto-derived from tau (5h for Fiasp, 6.5h for NovoRapid) but user-overridable

### 1.5 Polling strategy

- Poll every **5 minutes** (treatments don't change fast, and NS rate limits matter)
- On first connect: backfill 30 days
- Store in Room with `fetchedAt` timestamp
- Deduplicate by `id` (INSERT OR IGNORE)
- Prune treatments older than 30 days on each poll

### 1.6 UI: graph overlay

On the main glucose graph (Compose Canvas):

- **Bolus markers**: small downward triangle at the timestamp, sized proportional to dose. Label: "4U" below. Color: blue (#5B8DEF).
- **Carb markers**: small upward triangle at the timestamp, sized proportional to grams. Label: "60g" above. Color: green (#4CAF50).
- **Combined meal bolus**: both markers at the same x position.
- **IOB pill**: in the BG header area, next to delta. Shows current IOB rounded to 0.1U. Example: "IOB 1.6U". Fades to secondary text color when IOB < 0.3U (negligible).
- **No basal rendering on graph** — too noisy for a CGM display. Basal is implicit in IOB.

### 1.7 UI: treatment details

When scrubbing the graph timeline and the scrub position is near a treatment timestamp (within 5 min), show a tooltip with:
- Event type (human-readable)
- Insulin amount (if present)
- Carbs amount (if present)
- Time

### 1.8 Settings

In the Nightscout settings group (existing):
- **Treatments sync** — toggle (default: off). When enabled, polls treatments from the configured NS URL.
- **Insulin type** — dropdown (Fiasp / Lyumjev / NovoRapid-Humalog / Custom)
- **Custom DIA** — number input, only visible when insulin type = Custom

### 1.9 Feature gate

Treatments sync is off by default. The feature requires:
1. Nightscout URL configured (already required for push)
2. Treatments sync toggle enabled
3. Nightscout server supports `GET /api/v1/treatments.json` (real NS always does; Springa needs Part 2)

If the server returns 404 or error, log it and disable polling until next app restart. Don't spam a server that doesn't support it.

---

## Part 2: Springa (Next.js / Vercel)

### 2.1 Turso table

```sql
CREATE TABLE IF NOT EXISTS treatments (
  email TEXT NOT NULL,
  id TEXT NOT NULL,
  created_at TEXT NOT NULL,       -- ISO 8601
  event_type TEXT NOT NULL,
  insulin REAL,
  carbs REAL,
  basal_rate REAL,                -- U/h for Temp Basal
  duration INTEGER,               -- minutes for Temp Basal
  entered_by TEXT,
  ts INTEGER NOT NULL,            -- created_at as ms (for efficient range queries)
  PRIMARY KEY (email, id)
);

CREATE INDEX IF NOT EXISTS idx_treatments_ts ON treatments(email, ts);
```

### 2.2 Data pipeline: mylife Cloud → treatments table

Springa already fetches mylife Cloud data on-demand via `fetchMyLifeData()`. The pipeline:

1. `fetchMyLifeData()` returns `MyLifeEvent[]` (bolus, carbs, basal rate, boost, ease-off)
2. Map to Nightscout treatment format:

| MyLife event type | NS eventType | Fields |
|-------------------|-------------|--------|
| Bolus | Meal Bolus (if carbs within 15min) or Correction Bolus | insulin |
| Carbohydrates | Carb Correction | carbs |
| Hypo Carbohydrates | Carb Correction | carbs, notes: "Hypo treatment" |
| Basal rate | Temp Basal | absolute (=rate), duration (=time to next entry) |
| Boost | Temporary Target | duration (=value in hours), notes: "CamAPS Boost" |
| Ease-off | Temporary Target | duration (=value in hours), notes: "CamAPS Ease-off" |

3. Upsert into `treatments` table
4. Run on a schedule or on-demand when treatments endpoint is hit (with cache)

### 2.3 Sync strategy

Two options, recommend (a):

**(a) Lazy sync on read** — When `GET /api/v1/treatments` is called, check if last sync was >5 min ago. If so, fetch mylife Cloud, upsert treatments, then serve from DB. Pros: no cron job needed, data is fresh when requested. Cons: first request after gap is slow (~2-3s for mylife scrape).

**(b) Cron sync** — Add a Vercel cron job that syncs mylife Cloud → treatments table every 5 min. Pros: reads are always fast. Cons: burns mylife Cloud sessions, more moving parts.

### 2.4 API endpoint: GET /api/v1/treatments

Path: `app/api/v1/treatments/route.ts`

Auth: `api-secret` header (same as entries endpoint).

Query params (Nightscout-compatible subset):
- `count` — max results (default 10, max 500)
- `find[created_at][$gte]` — ISO 8601 or ms timestamp, lower bound
- `find[created_at][$lte]` — upper bound
- `find[eventType]` — filter by event type

Response: JSON array of treatment objects:

```json
[
  {
    "_id": "abc123",
    "eventType": "Meal Bolus",
    "created_at": "2026-03-19T08:42:00+01:00",
    "insulin": 5.0,
    "carbs": 60,
    "enteredBy": "mylife/CamAPS",
    "utcOffset": 60
  },
  {
    "_id": "def456",
    "eventType": "Temp Basal",
    "created_at": "2026-03-19T09:00:00+01:00",
    "absolute": 0.8,
    "duration": 10,
    "enteredBy": "mylife/CamAPS"
  }
]
```

Sort: descending by `created_at` (newest first), matching NS behavior.

### 2.5 API endpoint: POST /api/v1/treatments

Not needed for the Strimma use case (Strimma only reads treatments). But for full NS compatibility, accept and store treatments posted by other clients. Low priority — implement only if needed.

---

## Implementation Order

### Phase A: Springa (server-side, do first)
1. Add `treatments` table migration
2. Add `lib/treatmentsDb.ts` — CRUD for treatments table
3. Add `lib/mylifeToNightscout.ts` — maps MyLifeEvent[] → NS treatment format
4. Add `app/api/v1/treatments/route.ts` — GET endpoint with lazy sync
5. Test: curl against local dev, verify JSON matches NS format

### Phase B: Strimma (Android, after Springa is deployed)
1. Add `Treatment` entity + DAO to Room
2. Add `NightscoutClient.fetchTreatments()` — GET with query params
3. Add `TreatmentSyncer` — polls every 5 min, upserts to Room
4. Add `IOBComputer` — sums insulin treatments with exponential decay
5. Add graph overlay — bolus/carb markers on Canvas
6. Add IOB pill to BG header
7. Add settings (toggle + insulin type)
8. Test: verify against real NS instance AND Springa

---

## What This Does NOT Cover

- **COB (Carbs on Board)** — more complex (requires carb absorption model). IOB is sufficient for now. Can add later.
- **Basal visualization** — too noisy for a phone CGM display. IOB implicitly captures basal effect.
- **Treatment entry from Strimma** — Strimma is read-only for treatments. Entry happens in CamAPS FX.
- **Real-time companion data** — still blocked by CamAPS cert pinning. This spec uses the delayed mylife Cloud path.

## Resolved Questions

1. **mylife Cloud latency** — ~2 hours. Same ballpark as Glooko. Treatment data is contextual ("you bolused 5U two hours ago"), not real-time. Acceptable for IOB display and graph annotations.
2. **Notification graph** — IOB as text in the notification, no treatment markers on the 1h notification graph. Too tight on space.

## Open Questions

1. **mylife Cloud reliability** — the ASP.NET scraper is fragile. If mylife changes their UI, it breaks. Should we also support Glooko as a fallback source?
