# Local Web Server

Strimma can run a local HTTP server on port 17580, serving glucose data to other apps and devices.

---

## What This Does

When enabled, Strimma starts an HTTP server accessible at `http://<phone-ip>:17580`. Apps and devices on the same network can query it for current and historical glucose data. Garmin watchfaces and other apps on the same phone can access it via `http://127.0.0.1:17580`.

---

## Setup

1. Go to **Settings > Sharing**
2. Toggle **Local Web Server** on
3. Set an **API Secret** if you want devices on your local network to access it

The server starts immediately and runs as long as Strimma's foreground service is active.

---

## Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /sgv.json` | Glucose readings (newest first, mg/dL). Query params: `count` (default 24), `brief_mode` |
| `GET /api/v1/entries/sgv.json` | Same as above (Nightscout-compatible path) |
| `GET /status.json` | Unit settings and BG thresholds (bgLow, bgHigh) |
| `GET /treatments.json` | Bolus, carb, and basal treatment history (48h) |
| `GET /api/v1/treatments.json` | Same as above (Nightscout-compatible path) |

---

## Authentication

- **From the phone itself** (localhost / 127.0.0.1): no authentication required. This is how Garmin watchfaces access it.
- **From other devices on the network**: requires the `api-secret` HTTP header with a SHA-1 hash of your configured secret (same format as Nightscout). Without a secret configured, non-localhost requests are rejected with 403 Forbidden.

---

## Use Cases

- **Garmin watchfaces** like [SugarWave](https://github.com/psjostrom/SugarWave) that read from `/sgv.json` directly
- **Home automation** systems (Home Assistant, etc.) that need glucose data
- **Custom dashboards** displaying glucose on a tablet or screen
- **Development and debugging** — inspect Strimma's data via HTTP

---

## Security

!!! warning "Local network only"
    The web server is accessible to any device on your local network. Do not expose port 17580 to the internet. If you need remote access, use Nightscout instead.

---

## Port

The server always runs on port **17580**. This port is commonly used in the CGM DIY community (xDrip+ uses the same port for its web service).
