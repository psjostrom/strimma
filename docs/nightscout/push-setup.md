# Nightscout Server Setup

Configure the shared Nightscout server connection Strimma uses for uploads, manual pulls, treatment sync, and Nightscout Follower mode.

---

## What You Need

- A working Nightscout server URL (e.g., `https://my-nightscout.fly.dev`)
- Your Nightscout API secret

---

## Configuration

1. Open Strimma and go to **Settings > Data Source**
2. In the **Nightscout** section:
   - **URL** — enter your Nightscout base URL (e.g., `https://my-nightscout.fly.dev`)
   - **API Secret** — enter your Nightscout API secret

Strimma reuses this same Nightscout URL and API secret for every Nightscout feature:

- Uploading readings in Companion, xDrip Broadcast, and LibreLinkUp modes
- Manual Nightscout history pulls
- Treatment sync
- Nightscout Follower mode when selected as the data source

!!! warning "URL format"
    Enter only the base URL. Do **not** include `/api/v1/entries` or any path. Strimma adds the correct API paths automatically.

---

## How Uploads Work

When Strimma receives a new glucose reading:

1. The reading is stored in the local database with `pushed = 0`
2. Strimma immediately sends a `POST /api/v1/entries` request to your Nightscout server
3. On success, the reading is marked `pushed = 1`
4. On failure, the reading stays `pushed = 0` for later retry

### Retry Logic

If a push fails (network error, server down, etc.):

- Strimma retries up to **12 times** with linear backoff
- Delays: 5s, 10s, 15s, 20s, ... up to 60s max
- If all 12 attempts fail, the reading is left as unpushed

### Offline Resilience

Unpushed readings survive app restarts. Every 5 minutes, Strimma checks for unpushed readings and attempts to push them in a batch. This means:

- If you lose connectivity, readings accumulate locally
- When connectivity returns, all queued readings are pushed
- No data is lost during network outages

---

## Verifying the Connection

After entering your URL and secret:

1. Wait for your CGM to produce a new reading
2. Check your Nightscout web dashboard — the reading should appear within seconds
3. If it doesn't appear, check the **Debug Log** (Settings > Debug Log) for error messages

Common errors:

| Error | Cause | Fix |
|-------|-------|-----|
| 401 Unauthorized | Wrong API secret | Double-check the secret in settings |
| Connection refused | Wrong URL or server down | Verify the URL opens in a browser |
| Timeout | Network issue | Check your internet connection |

---

## Data Format

Strimma pushes readings in standard Nightscout format:

```json
[
  {
    "sgv": 108,
    "date": 1711029600000,
    "dateString": "2025-03-21T14:00:00.000Z",
    "direction": "Flat",
    "type": "sgv",
    "device": "Strimma"
  }
]
```

The `device` field is always `"Strimma"`, which lets you identify Strimma uploads on your Nightscout dashboard.

---

## Authentication

Strimma authenticates with Nightscout using the `api-secret` HTTP header. The secret is hashed with SHA-1 before sending, matching the standard Nightscout authentication method.

Your API secret is stored locally in Android's EncryptedSharedPreferences — encrypted at rest on your device.
