# Nightscout Follower Setup

Follow a remote Nightscout server to monitor someone else's glucose.

---

## Setup

1. Go to **Settings > Data Source**
2. Select **Nightscout Follower** as the data source
3. Enter the **Follower URL** — the Nightscout server to follow
4. Enter the **Follower API Secret** — the API secret for that server
5. Set the **Poll Interval** — how often to check (default: 60 seconds)

Strimma begins polling immediately.

---

## What Happens Next

1. **Backfill** — Strimma fetches up to 7 days of history from the server
2. **Polling** — Strimma checks for new readings at your configured interval
3. **Alerts** — all alerts work in follower mode (low, high, stale, prediction)
4. **Treatments** — if treatment sync is enabled, treatments are fetched from the followed server

---

## Connection Status

The main screen shows the follower connection state:

- **Following · 15s ago** — connected, last successful poll was 15 seconds ago
- **Following · connection lost 2m** — the server hasn't responded for 2 minutes (shown in red)

---

## Tips

!!! tip "Poll interval"
    If you're following someone using a Libre 3 (1-minute readings), polling every 30–60 seconds makes sense. For Dexcom (5-minute readings), polling every 60–120 seconds is sufficient.

!!! tip "Multiple followers"
    Multiple people can follow the same Nightscout server simultaneously. Each follower runs independently.

!!! tip "Alerts for caregivers"
    Configure alerts in Strimma just like you would for your own glucose. Parents often set wider alert thresholds than the person wearing the sensor, to avoid alert fatigue while still catching serious events.

---

## Differences from Other Modes

In follower mode:

- Strimma does **not** push readings to Nightscout (they're already there)
- The Nightscout Push URL settings are hidden
- Direction and delta are computed locally from the received data
- Treatment sync (if enabled) fetches from the follower URL, not a separate push URL
