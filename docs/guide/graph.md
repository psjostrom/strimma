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

### Probable Compression Lows

Lying on the sensor can squeeze the tissue around it, and the sensor then reports a false low. Strimma marks these episodes with a light grey band, and the readings inside the band are drawn faded. Tap a faded reading to see **Probable compression low · not in stats** in the tooltip.

Strimma marks an episode only after the rebound, and only when the lowest reading falls between 23:00 and 09:00, when you are likely asleep. During the day a low treated with sweets looks the same on the graph, so Strimma leaves daytime lows alone. It looks for a low where glucose falls at least 30 mg/dL (1.7 mmol/L) into the low at 3 mg/dL (0.17 mmol/L) per minute or faster, stays below 3.9 mmol/L (70 mg/dL) for 30 minutes or less, and climbs back out at 3 mg/dL per minute or faster to recover at least 60% of the drop. The low may sit flat on the sensor's lowest value before it climbs back out. A real low rarely falls and rebounds that fast. Strimma never marks a low with carbs logged within 20 minutes of the lowest reading, because a treated low was real to you. It also never marks a low during an exercise session. Alerts still fire for every low, because nobody can know whether a low is real until it rebounds.

The readings in the band are not counted in [Statistics](statistics.md#probable-compression-lows), the Monthly Story or recurring patterns. To turn this off, go to **Settings > Display** and switch off **Detect compression lows**. Strimma then shows no bands and counts every low. Nightscout, Tidepool, Health Connect and the CSV export still receive every reading as the sensor reported it.

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

Release your finger to dismiss.

### Scrub

Press and drag your finger horizontally across the graph. The selection follows your finger, snapping to the nearest data point with subtle haptic feedback as you move between readings. This lets you quickly scan through your history and see exact values.

!!! note
    If you start scrubbing and then add a second finger, scrub mode cancels to allow pinch-to-zoom.

### Pinch to Zoom

Use two fingers to pinch in or out. Zooming ranges from 1x to 5x the base window. At 1x with the default 4-hour window, you see 4 hours of data. At 5x zoom, you see about 48 minutes.

### Pan

After zooming in, drag with one finger to move the viewport left (earlier) or right (later). The graph clamps to your data range — you can't pan beyond your oldest reading or past the prediction horizon.

### Auto-Tracking

When the graph is showing the current time (right edge near "now"), it automatically advances to keep the latest reading visible. If you pan or zoom away from the current time, auto-tracking pauses. When you pan back to the current time, it resumes.

### Double-Tap to Reset

Double-tap anywhere on the graph to reset zoom to 1x and jump the viewport to the current time, resuming auto-tracking.

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
