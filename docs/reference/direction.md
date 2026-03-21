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

1. **Collect recent readings** — the last few minutes of stored glucose values
2. **Find a reference point** — locate the reading closest to 5 minutes before the current reading
3. **Apply 3-point averaging** — for each timepoint, average the reading with its immediate neighbors. This smooths out single-reading noise.
4. **Calculate velocity** — compute the rate of change in mg/dL per minute:
   ```
   velocity = (averaged_now - averaged_past) / minutes_between
   ```
5. **Map to direction** — compare the velocity against the EASD/ISPAD thresholds listed above
6. **Compute delta** — the total change in mg/dL (not per-minute) is stored alongside the direction

### Edge Cases

- **Insufficient data:** If no reading exists within 10 minutes before the current one, direction is set to "?" (NONE)
- **Gap tolerance:** The algorithm looks for a reading within 10 minutes of the 5-minute lookback target
- **3-point averaging:** At the edges of the reading list (first/last reading), the average uses fewer points (clamped to valid indices)

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
