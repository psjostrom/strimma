# Your First Reading

Once Strimma is set up, here's what happens when your CGM app posts a glucose notification.

---

## What You'll See

### The Notification

A persistent notification appears in your notification bar showing:

- **BG value** — your current glucose, large and readable
- **Direction arrow** — which way your glucose is trending (e.g., ↗ rising slowly)
- **Delta** — how much your glucose changed since the last reading (e.g., +0.3)
- **Graph** — a 1-hour glucose history, right in the notification

Pull down the notification to see the expanded view with a larger graph.

### The Main Screen

Open Strimma to see the full display:

- **Hero BG** — your glucose in large, bold text, color-coded:
    - **Cyan** — in range
    - **Amber** — above your high threshold
    - **Red** — below your low threshold
    - **Gray** — stale (no recent reading)
- **Direction arrow** — next to the BG value
- **Delta and time** — how much it changed and how long ago (e.g., "+0.3 · 2 min ago")
- **Interactive graph** — 4 hours of history by default (pinch to zoom, drag to pan, tap a dot to inspect)
- **Minimap** — 24-hour overview below the graph, tap to jump to any time

---

## If Nothing Appears

If Strimma doesn't show a reading after your CGM app posts a notification:

1. **Check notification access** — go to Android Settings > Apps > Special app access > Notification access and make sure Strimma is enabled
2. **Check your CGM app** — make sure it's posting notifications (some apps let you disable them)
3. **Check the data source** — in Strimma Settings > Data Source, make sure **Companion** is selected
4. **Check the debug log** — in Strimma Settings > Debug Log, look for messages about received or rejected notifications

!!! tip "Force a reading"
    Open your CGM app and wait for its next reading cycle. Most CGMs report every 1 minute (Libre 3) or every 5 minutes (Dexcom). Strimma processes the notification within seconds.

---

## Understanding the Colors

Strimma uses consistent colors everywhere — the BG display, the graph, the notification, and the widget:

| Color | Meaning | Default threshold |
|-------|---------|-------------------|
| :material-circle:{ style="color: #56CCF2" } Cyan | In range | 4.0–10.0 mmol/L (72–180 mg/dL) |
| :material-circle:{ style="color: #FFB800" } Amber | Above high | > 10.0 mmol/L (180 mg/dL) |
| :material-circle:{ style="color: #FF4D6A" } Red | Below low | < 4.0 mmol/L (72 mg/dL) |
| :material-circle:{ style="color: #6A5F80" } Gray | Stale data | No reading for 10+ minutes |

You can change the low and high thresholds in **Settings > Display**.

---

## Next Steps

- **[Configure alerts](../guide/alerts.md)** — set up sound and vibration for highs and lows
- **[Explore the graph](../guide/graph.md)** — learn the touch gestures
- **[Set up Nightscout](../nightscout/push-setup.md)** — push your data to the cloud
- **[Add a widget](../guide/widget.md)** — see your glucose on your home screen
