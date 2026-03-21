# Prediction

Strimma predicts where your glucose is heading and warns you before you cross a threshold.

---

## How It Works

Strimma looks at your last 12 minutes of glucose readings, computes the rate of change (with more weight on recent readings), and projects the trend forward using a dampened velocity model.

The prediction assumes glucose trends slow down over time (mean reversion) — a glucose that's rising at 2 mg/dL per minute right now won't keep rising at that rate forever. This produces more realistic predictions than a simple straight-line projection.

For the full mathematical details, see [Prediction Algorithm](../reference/prediction-algorithm.md).

---

## What You See

### On the Graph

A **dashed line** extends from your most recent reading into the future, showing the predicted glucose trajectory. The prediction extends for your configured prediction window (15 or 30 minutes).

The dashed line is drawn in the same color scheme as regular readings — if the prediction enters the high or low zone, that portion of the line reflects it.

### In the BG Header

When Strimma predicts you'll cross a threshold, a **warning pill** appears:

- **Low in X min** — red-tinted pill, your glucose is predicted to drop below your low threshold
- **High in X min** — amber-tinted pill, your glucose is predicted to rise above your high threshold

The pill shows the time until the predicted crossing.

### In the Notification

The notification subtitle includes a compact prediction warning (e.g., "Low 10m") when applicable.

---

## When Predictions Appear

Predictions only appear under these conditions:

1. **At least 2 readings** in the last 12 minutes — Strimma needs data to compute a trend
2. **Currently in range** — if you're already low or high, the prediction pill is hidden
3. **Crossing predicted within the window** — if the trend doesn't reach a threshold within 15 or 30 minutes, no warning is shown
4. **At least 4 minutes until crossing** — very near-term crossings (< 4 min) are filtered out to reduce noise

---

## Prediction Settings

Configure prediction in **Settings > Notifications > Prediction**:

| Setting | Options |
|---------|---------|
| Lookahead window | Off, 15 min, 30 min |

- **Off** — no prediction line on the graph, no prediction warnings
- **15 min** — shorter window, fewer false warnings, less advance notice
- **30 min** — longer window, more advance warning, may trigger on trends that reverse

---

## Prediction Accuracy

The dampened velocity model is designed to minimize false alarms:

- **V-recovery protection** — when your glucose drops sharply and then bounces back (common after treating a low), the model doesn't predict a false high from the upward bounce
- **Weighted recent data** — the last 4 minutes carry ~84% of the weight, so the prediction responds quickly to trend changes
- **Velocity dampening** — the prediction naturally decelerates, avoiding straight-line over-predictions
- **Artifact rejection** — readings that imply glucose is changing faster than 9 mg/dL per minute are rejected as sensor noise

!!! info "No prediction is perfect"
    Prediction is a tool to give you advance warning, not a guarantee. Sensor lag, meal absorption, insulin action, exercise, and many other factors affect real glucose trajectories. Use predictions as one signal among many.

---

## Works with Any Sensor

The prediction algorithm works with any CGM reading interval:

- **Libre 3** — readings every 1 minute
- **Dexcom G6/G7** — readings every 5 minutes
- **Any other CGM** — adapts to whatever interval the readings arrive at

The weighted regression naturally handles irregular intervals.
