# Treatments & IOB

Strimma can fetch treatment data (bolus, carbs, basal) from Nightscout and compute your insulin on board (IOB).

---

## What Are Treatments?

In diabetes management, "treatments" are actions you take that affect glucose:

- **Bolus** — a dose of rapid-acting insulin (e.g., 3.5 units of Fiasp before a meal)
- **Carbs** — carbohydrates consumed (e.g., 45g from a meal)
- **Basal** — background insulin rate (e.g., 0.8 U/h)

Strimma fetches this data from your Nightscout server. The data typically comes from your insulin pump or closed-loop system (e.g., CamAPS FX via mylife Cloud → Nightscout).

---

## Enable Treatment Sync

1. Go to **Settings > Treatments**
2. Toggle **Fetch bolus, carb, and basal data** on
3. Make sure you have a working Nightscout connection (either push or follower)

Strimma polls for treatments every 5 minutes, fetching the last 100 days of data.

---

## What You See

### On the Graph

Treatment markers appear on the glucose graph:

- **Bolus** — blue downward triangles at the bottom of the graph, labeled with units (e.g., "2.5U"). Larger triangles = larger doses.
- **Carbs** — green upward triangles at the top of the graph, labeled with grams (e.g., "45g"). Larger triangles = more carbs.

### In the BG Header

When you have active insulin, an **IOB pill** appears showing your current insulin on board (e.g., "IOB 2.3U").

Tap the IOB pill to open a detail dialog listing:

- Each recent bolus — time, original dose, and remaining IOB
- Total IOB at the bottom
- Your configured insulin type and DIA

---

## IOB Calculation

IOB (Insulin on Board) represents how much rapid-acting insulin is still active in your body. Strimma models insulin activity as an exponential decay curve — insulin is most active right after injection and gradually fades over several hours.

The speed of this decay depends on your insulin type. Faster insulins (Fiasp, Lyumjev) decay quicker than standard rapid-acting insulins (NovoRapid, Humalog).

Strimma sums the remaining activity from all recent boluses to give you a single IOB number.

For the full mathematical model, see [IOB Calculation](../reference/iob-calculation.md).

---

## Insulin Types

Choose your insulin type in **Settings > Treatments**:

| Insulin | Tau (τ) | Lookback Window | Peak Activity |
|---------|---------|----------------|---------------|
| **Fiasp** | 55 min | 4.6 hours | ~55 min |
| **Lyumjev** | 50 min | 4.2 hours | ~50 min |
| **NovoRapid / Humalog** | 75 min | 6.3 hours | ~75 min |
| **Custom** | Configurable via DIA | DIA setting | DIA / 5 |

### Custom DIA

If you select **Custom**, you can set the Duration of Insulin Action (DIA) in hours (2–10). The tau is computed as:

```
τ = DIA (hours) × 60 / 5 = DIA × 12 minutes
```

For example, a 5-hour DIA gives τ = 60 minutes.

---

## Data Source

Treatments come from your Nightscout server's `/api/v1/treatments.json` endpoint. Strimma doesn't create or modify treatments — it only reads them.

Common treatment sources:

- **CamAPS FX** → mylife Cloud → Nightscout
- **AndroidAPS** → Nightscout (direct upload)
- **Loop** → Nightscout (direct upload)
- **Manual entries** in Nightscout (Careportal)

---

## Data Retention

- Strimma fetches up to **100 days** of treatments on each sync
- Treatments older than **100 days** are pruned from the local database
- Nightscout remains the long-term store for treatment history
- You can manually backfill treatments in **Settings > Treatments > Pull treatments**
