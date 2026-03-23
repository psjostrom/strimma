# All Settings

Complete reference for every Strimma setting.

![Settings screen](../screenshots/settings.png){ width="300" }

---

## Settings Menu

The settings screen is organized into two groups:

**Group 1:** Data Source, Treatments, Display, Notifications, Alerts

**Group 2:** Data, Debug Log

---

## Data Source

Choose how Strimma receives glucose data and configure Nightscout.

| Setting | Description | Default |
|---------|-------------|---------|
| **Source** | How glucose data arrives — Companion, xDrip Broadcast, or Nightscout Follower | Companion |
| **Nightscout URL** | Base URL for pushing readings (e.g., `https://my-ns.fly.dev`) | Empty |
| **API Secret** | Nightscout API secret (stored encrypted) | Empty |
| **Follower URL** | Nightscout URL to follow (only in Follower mode) | Empty |
| **Follower Secret** | API secret for the followed server | Empty |
| **Poll Interval** | How often to check for new readings in Follower mode (30–300 seconds) | 60s |

See [Data Sources](../data-sources/overview.md) for details on each mode.

---

## Treatments

Configure treatment sync and insulin parameters for IOB calculation.

| Setting | Description | Default |
|---------|-------------|---------|
| **Treatment sync** | Fetch bolus, carb, and basal data from Nightscout | Off |
| **Insulin type** | Insulin curve for IOB calculation | Fiasp |
| **Custom DIA** | Duration of Insulin Action in hours (only for Custom insulin) | 5.0h |

See [Treatments & IOB](treatments.md) for details.

---

## Display

Configure units, graph window, thresholds, and theme.

| Setting | Description | Default |
|---------|-------------|---------|
| **Unit** | Display unit — mmol/L or mg/dL | mmol/L |
| **Graph window** | Main graph time range (1–8 hours) | 4 hours |
| **BG Low** | Low threshold for graph coloring and TIR | 4.0 mmol/L (72 mg/dL) |
| **BG High** | High threshold for graph coloring and TIR | 10.0 mmol/L (180 mg/dL) |
| **Theme** | App appearance — Light, Dark, or System | System |

---

## Notifications

Configure the notification graph and prediction window.

| Setting | Description | Default |
|---------|-------------|---------|
| **Graph time range** | Time shown in notification graph (30m, 1h, 2h, 3h) | 1 hour |
| **Prediction window** | How far ahead to predict (Off, 15 min, 30 min) | 15 min |

---

## Alerts

Configure glucose alerts. Each alert has an enable toggle and a threshold value.

| Setting | Description | Default |
|---------|-------------|---------|
| **Urgent Low** | Alert for dangerously low glucose | Enabled, 3.0 mmol/L (54 mg/dL) |
| **Low** | Alert for low glucose | Enabled, 4.0 mmol/L (72 mg/dL) |
| **High** | Alert for high glucose | Enabled, 10.0 mmol/L (180 mg/dL) |
| **Urgent High** | Alert for dangerously high glucose | Enabled, 13.0 mmol/L (234 mg/dL) |
| **Low Soon** | Alert when predicted to go low | Enabled |
| **High Soon** | Alert when predicted to go high | Enabled |
| **Stale Data** | Alert when no reading for 10+ minutes | Enabled |

Each alert also has a **Sound** button that opens the Android notification channel settings for customization.

See [Alerts](alerts.md) for full details.

---

## Data

Statistics, Nightscout backfill, integrations, and backup.

### Views

| Setting | Description |
|---------|-------------|
| **Statistics** | Opens the statistics screen |

### Nightscout Pull

| Action | Description |
|--------|-------------|
| **Pull 7 days** | Backfill 7 days of readings from Nightscout |
| **Pull 14 days** | Backfill 14 days |
| **Pull 30 days** | Backfill 30 days |

!!! info "Auto-pull"
    When Strimma's database is empty (first install), it automatically pulls 30 days of history from Nightscout if a URL and secret are configured.

### Integration

| Setting | Description | Default |
|---------|-------------|---------|
| **BG Broadcast** | Send xDrip-compatible broadcast intent | Off |
| **Local Web Server** | Serve BG on port 17580 for watches and apps | Off |
| **Web Server API Secret** | Secret for authenticating web server requests | Empty |

### Backup

| Action | Description |
|--------|-------------|
| **Export Settings** | Save all settings to a JSON file |
| **Import Settings** | Restore settings from a previously exported file |

!!! warning "Export contains secrets"
    The exported settings file includes your Nightscout URL and API secret in plain text. Handle the file securely.

---

## Debug Log

Opens the [Debug Screen](../troubleshooting.md#debug-log) showing real-time and file-based logs. Useful for troubleshooting issues with data reception.
