# Statistics

Strimma computes standard diabetes metrics from your glucose data.

![Statistics screen](../screenshots/statistics.png){ width="300" }

---

## Viewing Statistics

Open statistics from:

- The **bar chart icon** in the main screen top bar
- **Settings > Data > Statistics**

---

## Time Periods

Select the time period at the top:

- **24 hours** — last day
- **7 days** — last week
- **14 days** — last two weeks
- **30 days** — last month

---

## Metrics

### Time in Range (TIR)

A horizontal bar chart showing the percentage of readings in each zone:

| Zone | Color | Meaning |
|------|-------|---------|
| Below range | Red | % of readings below your low threshold |
| In range | Cyan | % of readings between your low and high thresholds |
| Above range | Amber | % of readings above your high threshold |

!!! info "TIR targets"
    The international consensus target (ATTD) for most people with Type 1 diabetes is >70% time in range (3.9–10.0 mmol/L / 70–180 mg/dL), <4% time below range, and <25% time above range. Note: Strimma calculates TIR using your configured thresholds (default 4.0–10.0 mmol/L), which may differ slightly from the ATTD reference range.

### Average Glucose

Your mean glucose over the selected period, shown in your configured unit (mmol/L or mg/dL).

### GMI (Glucose Management Indicator)

An estimated HbA1c percentage derived from your average glucose:

```
GMI = 3.31 + (0.02392 × average mg/dL)
```

This is the ATTD consensus formula. GMI gives you a continuous estimate of what your lab HbA1c might be, based on CGM data alone.

!!! note
    GMI is an estimate, not a lab result. Your actual HbA1c may differ due to individual red blood cell lifespan and other factors.

### CV (Coefficient of Variation)

A measure of glucose variability:

```
CV = (standard deviation / average) × 100%
```

| CV | Interpretation |
|----|----------------|
| < 36% | Stable (target for most people) |
| 36–50% | Moderate variability |
| > 50% | High variability |

Lower CV means more stable glucose with fewer spikes and dips.

### Standard Deviation

The spread of your glucose values around the average, in your configured unit. Lower is better — it means less glucose variability.

### Reading Count

The total number of glucose readings in the selected period. This helps you assess data completeness.

---

## Export to CSV

Tap the **share icon** in the top bar to export your glucose data as a CSV file. The export includes:

| Column | Description |
|--------|-------------|
| `ts` | Unix timestamp (milliseconds) |
| `datetime` | Human-readable date and time |
| `sgv` | Glucose value in mg/dL |
| `direction` | Trend arrow |
| `delta_mgdl` | Change since previous reading in mg/dL |

The CSV covers the currently selected time period. You can share it via email, messaging, or save it to files.

---

## Range Info

The bottom of the statistics screen shows your configured range (e.g., "Range: 4.0–10.0 mmol/L") so you know what thresholds the TIR calculation uses.
