# Direction Arrows

Strimma computes glucose direction (trend) locally using clinically-validated thresholds from the EASD/ISPAD 2020 consensus.

---

## Direction Arrows

| Arrow | Name | Rate of Change | Meaning |
|-------|------|----------------|---------|
| ⇈ | DoubleUp | > +3.0 mg/dL/min | Rising very fast |
| ↑ | SingleUp | +2.0 to +3.0 | Rising fast |
| ↗ | FortyFiveUp | +1.1 to +2.0 | Rising |
| → | Flat | -1.1 to +1.1 | Stable |
| ↘ | FortyFiveDown | -2.0 to -1.1 | Falling |
| ↓ | SingleDown | -3.0 to -2.0 | Falling fast |
| ⇊ | DoubleDown | ≤ -3.0 | Falling very fast |
| ? | NONE | — | Insufficient data |

---

## How Direction Is Computed

Strimma computes direction from your recent glucose readings rather than trusting the direction from your CGM app's notification. Here's why:

- CGM apps report direction inconsistently (different terminology, missing values)
- Local computation gives consistent, clinically-validated results
- Works across all data sources (Companion, xDrip Broadcast, Follower)

### Algorithm

1. **Find a reference point** — the stored reading closest to 5 minutes before the current one, at least 2.5 and at most 15 minutes old
2. **Compute delta** — the change between the two readings, scaled to 5 minutes:
   ```
   delta = (current - past) × 5 / minutes_between
   ```
3. **Map to direction** — divide the delta by 5 to get mg/dL per minute, and compare it against the EASD/ISPAD thresholds listed above

Scaling by the real time between the readings makes the delta independent of your sensor's interval. A Libre with 1-minute readings and a Dexcom with 5-minute readings show the same delta for the same rise. AAPS computes its default delta the same way, without smoothing.

### Edge Cases

- **Insufficient data:** If no reading is between 2.5 and 15 minutes old, direction is "?" (NONE) and the delta is hidden
- **Missed reading:** If the reading 5 minutes ago is missing, the reading 10 minutes ago is used and the delta is still scaled to 5 minutes

---

## Why Not Use the CGM App's Direction?

Testing against real CamAPS FX data showed a **31% mismatch rate** between the CGM app's reported direction and the clinically correct direction computed from the actual readings. Common issues:

- CGM apps sometimes report "Flat" when glucose is clearly rising or falling
- Some apps use proprietary smoothing that doesn't match clinical thresholds
- Not all apps include direction in their notifications

By computing direction locally with EASD/ISPAD thresholds, Strimma provides reliable, consistent trend information regardless of the CGM app.

---

## Clinical References

The direction thresholds (±1.1, ±2.0, ±3.0 mg/dL/min) come from:

- **EASD/ISPAD** (2020): International consensus on CGM interpretation for clinical practice
- These thresholds are widely used in diabetes technology and are the standard in most CGM systems
