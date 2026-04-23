# Nightscout Follower Setup

Follow a remote Nightscout server to monitor someone else's glucose.

---

## Setup

1. Go to **Settings > Data Source**
2. Select **Nightscout Follower** as the data source
3. In the **Nightscout** section, enter the **Nightscout URL** — the server to follow
4. Enter the **API Secret** for that server
5. Set the **Poll Interval** — how often to check (default: 60 seconds)

Strimma begins polling immediately. The same Nightscout URL and API secret are also used for manual pulls and treatment sync.

---

## What Happens Next

1. **Backfill** — Strimma fetches up to 7 days of history from the server
2. **Polling** — Strimma checks for new readings at your configured interval
3. **Alerts** — all alerts work in follower mode (low, high, stale, prediction)
4. **Treatments** — if treatment sync is enabled, treatments are fetched from the followed server

---

## Connection Status

Connection status is shown inline in **Settings > Data Source**, below the poll interval slider:

- **Connected · Last reading: 15s ago** — connected, shown in cyan
- **Connection lost** — the server hasn't responded, shown in red

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
- The Nightscout URL and API secret stay visible because the Nightscout server configuration is shared
- Direction and delta are computed locally from the received data
- Treatment sync (if enabled) fetches from the configured Nightscout server
