# Health Connect

Strimma integrates with [Android Health Connect](https://developer.android.com/health-and-fitness/health-connect) for bidirectional health data exchange.

## Read

Strimma reads exercise sessions from Health Connect, including heart rate, steps, and active calories. Any app that writes to Health Connect works — Garmin Connect, Samsung Health, Google Fit, Strava, etc.

See [Exercise & Health Connect](../guide/exercise.md) for setup and details.

## Write

Strimma can write CGM glucose readings to Health Connect as blood glucose records (specimen source: interstitial fluid). This makes your glucose data visible to any Health Connect-compatible app.

Enable in **Settings > Exercise > Write glucose to Health Connect**.

## Requirements

- **Android 14+** — Health Connect is built into the system
- **Android 13** — install Health Connect from Google Play
