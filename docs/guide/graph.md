# The Graph

Strimma's glucose graph is fully interactive — you can zoom, pan, scrub, and inspect individual readings.

---

## What the Graph Shows

### Glucose Readings

Each reading is drawn as a dot connected by lines. Colors indicate range:

- **Cyan** — in range (between your low and high thresholds)
- **Amber** — above your high threshold
- **Red** — below your low threshold or at critical levels

### Threshold Lines

Dashed horizontal lines show your configured thresholds:

- **Low threshold** — your configured low (default 4.0 mmol/L / 72 mg/dL)
- **High threshold** — your configured high (default 10.0 mmol/L / 180 mg/dL)
- **Critical low** — fixed at 3.0 mmol/L (54 mg/dL), the international urgent low standard
- **Critical high** — fixed at 13.0 mmol/L (234 mg/dL)

### In-Range Zone

A subtle cyan band fills the area between your low and high thresholds, making it easy to see at a glance how much time you're spending in range.

### Prediction Curve

A dashed line extending from your most recent reading into the future, showing where Strimma predicts your glucose is heading. The prediction uses the last 12 minutes of data with a dampened velocity model — see [Prediction Algorithm](../reference/prediction-algorithm.md) for the math.

### Treatment Markers

If treatment sync is enabled:

- **Blue triangles** (pointing down, at the bottom) — bolus insulin doses. Size scales with the dose. Labeled with units (e.g., "2.5U").
- **Green triangles** (pointing up, at the top) — carbohydrate entries. Size scales with grams. Labeled (e.g., "15g").

---

## Touch Gestures

### Tap to Inspect

Tap on or near a data point to select it. A crosshair appears with a tooltip showing:

- The exact glucose value and direction arrow
- The timestamp and delta

Tap elsewhere or wait to dismiss.

### Scrub

Press and drag your finger horizontally across the graph. The selection follows your finger, snapping to the nearest data point. This lets you quickly scan through your history and see exact values.

!!! note
    If you start scrubbing and then add a second finger, scrub mode cancels to allow pinch-to-zoom.

### Pinch to Zoom

Use two fingers to pinch in or out. Zooming ranges from 1x to 5x the base window. At 1x with the default 4-hour window, you see 4 hours of data. At 5x zoom, you see about 48 minutes.

### Pan

After zooming in, drag with one finger to move the viewport left (earlier) or right (later). The graph clamps to your data range — you can't pan beyond your oldest reading or past the prediction horizon.

### Auto-Tracking

When the graph is showing the current time (right edge near "now"), it automatically advances to keep the latest reading visible. If you pan or zoom away from the current time, auto-tracking pauses. When you pan back to the current time, it resumes.

---

## Minimap

The 24-hour minimap below the main graph shows your entire day at a glance.

- **Dots** — each glucose reading as a small color-coded dot
- **Threshold dashes** — subtle dashed lines at your low and high thresholds
- **Viewport rectangle** — highlights the portion of the day currently shown in the main graph

**Tap or drag** on the minimap to jump the main graph to that time.

---

## Axis Labels

- **X-axis (bottom):** Time labels. Shown every 15 minutes when zoomed to 1 hour or less, every 30 minutes otherwise.
- **Y-axis (left):** Glucose values. Step size depends on your unit:
    - **mg/dL:** every 25 or 50 mg/dL depending on the visible range
    - **mmol/L:** every 1.0 or 2.0 mmol/L depending on the visible range

---

## Graph Settings

You can configure the graph in **Settings > Display**:

| Setting | Options | Default |
|---------|---------|---------|
| Graph window | 1–8 hours | 4 hours |
| Units | mmol/L or mg/dL | mmol/L |
| Low threshold | Any value | 4.0 mmol/L (72 mg/dL) |
| High threshold | Any value | 10.0 mmol/L (180 mg/dL) |

The prediction window is set in **Settings > Notifications > Prediction** (Off / 15 min / 30 min).
