# Strimma

**Open-source Android CGM companion app for people with diabetes.**

Strimma displays your glucose with a real-time graph, alerts you when you're going low or high, and pushes your data to Nightscout. It receives glucose from your existing CGM app, from xDrip-compatible broadcasts, or by following a remote Nightscout server.

Strimma stands on the shoulders of the incredible [xDrip+](https://github.com/NightscoutFoundation/xDrip) project and the broader DIY diabetes community that proved open-source CGM tools save lives. It uses the same broadcast format, the same Nightscout protocol, and shares the same philosophy: **your data, your choice.**

---

<div class="grid" style="display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin: 1.5rem 0;" markdown>

![Main screen](screenshots/main-screen.png){ width="100%" }

![Expanded notification](screenshots/notification-expanded.png){ width="100%" }

![Statistics](screenshots/statistics.png){ width="100%" }

</div>

<div class="grid" style="display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin: 1.5rem 0;" markdown>

![Exercise graph](screenshots/exercise-graph.png){ width="100%" }

![Exercise history](screenshots/exercise-history.png){ width="100%" }

![Exercise detail](screenshots/exercise-detail.png){ width="100%" }

</div>

<div class="grid" style="display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin: 1.5rem 0;" markdown>

![Pre-activity guidance](screenshots/pre-activity-guidance.png){ width="100%" }

![AGP report](screenshots/agp-report.png){ width="100%" }

![Meal analysis](screenshots/meal-analysis-lunch.png){ width="100%" }

</div>

## What Strimma Does

- **Receives glucose four ways** — reads notifications from 50+ CGM app variants (Dexcom, Libre, CamAPS FX, etc.), receives xDrip-compatible broadcasts (from xDrip+, Juggluco, AAPS), follows a remote Nightscout server, or reads from Abbott's LibreLinkUp cloud. See [Data Sources](data-sources/overview.md).
- **Shows your BG at a glance** — large, color-coded number with direction arrow, delta, and trend graph in your notification bar.
- **Configurable alerts** — eight alert types (urgent low, low, high, urgent high, low soon, high soon, stale data, push failed), each with its own notification channel. Urgent alerts bypass Do Not Disturb by default; any alert can be configured to bypass DND via Android's notification settings. Alerts can be paused by category (Low or High) for a custom duration.
- **Predicts where you're heading** — shows "Low in X min" or "High in X min" warnings before you cross your thresholds.
- **Pushes to Nightscout** — automatic, immediate upload to any Nightscout-compatible server. Offline-resilient — readings queue and retry.
- **Tracks treatments and IOB** — fetches bolus and carb data from Nightscout (30-day retention), computes insulin on board with your insulin type's curve.
- **Per-meal postprandial analysis** — analyzes glucose response to each meal: TIR, peak excursion, recovery time, IOB at meal. Aggregate postprandial profile with AGP-style percentile bands. Configurable meal time slots.
- **Follows a remote Nightscout** — for caregivers, partners, or parents who need to see someone else's glucose remotely.
- **Works with watches and other apps** — broadcasts xDrip-compatible intents, runs a local web server, integrates with Garmin watchfaces.
- **Exercise-BG analysis** — reads exercise sessions from Health Connect (Garmin, Samsung Health, etc.), overlays exercise bands on the glucose graph, and shows before/during/after BG breakdown with post-exercise hypo detection.
- **Exercise stats** — aggregate BG patterns across sessions grouped by activity type (Running, Cycling, Strength, etc.) and metabolic profile (Aerobic, Resistance, High-Intensity). Hypo rate, average entry BG, drop rate, recovery patterns.
- **Pre-activity guidance** — readiness card before scheduled workouts. Evaluates current BG, trend, IOB, and 30-min forecast. Generates carb recommendations adjusted for IOB and time until workout. Compound risk detection (falling BG + low-ish starting point + upcoming exercise).
- **Workout schedule** — reads planned workouts from your Android calendar. Shows upcoming sessions with pre-activity status so you can prepare.

---

## How It Works

Strimma supports four data sources. Most users use **Companion mode**, which reads glucose from your CGM app's notification:

```mermaid
graph LR
    A[CGM Sensor] -->|Bluetooth| B[CGM App]
    B -->|Notification| C[Strimma]
    C -->|Display| D[Phone Screen]
    C -->|Push| E[Nightscout]
    C -->|Alert| F[Sound/Vibration]
    C -->|Broadcast| G[Watches & Apps]
```

You can also receive glucose via **xDrip Broadcast** (from xDrip+, Juggluco, AAPS, or GlucoDataHandler), **Nightscout Follower** mode (for remote monitoring), or **LibreLinkUp** (Abbott's cloud for Libre 3 users). See [Data Sources](data-sources/overview.md) for all options.

---

## Who Is Strimma For?

- **People with Type 1 or Type 2 diabetes** who want an open-source glucose display with configurable alerts and Nightscout integration.
- **Parents and caregivers** who want to follow a loved one's glucose remotely via Nightscout follower mode.
- **DIY diabetes tech users** who want an open-source, hackable CGM display that respects the Nightscout protocol.
- **Closed-loop users** (CamAPS FX, AndroidAPS) who need a parallel display without interfering with their loop.

---

## Quick Start

1. **[Install Strimma](getting-started/install.md)** — download from GitHub Releases
2. **[Set up permissions](getting-started/setup.md)** — choose your data source and grant required permissions
3. **[See your first reading](getting-started/first-reading.md)** — watch the data flow

---

## Requirements

- Android 13 or newer (API 33+)
- A glucose data source — one of:
    - A CGM app that shows glucose in notifications (see [Supported Apps](data-sources/supported-apps.md))
    - An app that sends xDrip-compatible broadcasts (xDrip+, Juggluco, AAPS)
    - A remote Nightscout server to follow
    - A LibreLinkUp account (for Libre 3 users)
- For Nightscout push: a Nightscout server URL and API secret

---

## Open Source

Strimma is free, open-source software licensed under [GPLv3](https://github.com/psjostrom/strimma/blob/main/LICENSE). No ads, no tracking, no data collection. Your glucose data stays on your device and your Nightscout server.

[View on GitHub :fontawesome-brands-github:](https://github.com/psjostrom/strimma){ .md-button }

---

!!! warning "Not a medical device"
    Strimma is an open-source display tool, not a medical device. It is not FDA or CE approved. Do not use Strimma as the sole basis for medical decisions. Always follow the guidance of your healthcare team and your official CGM app.
