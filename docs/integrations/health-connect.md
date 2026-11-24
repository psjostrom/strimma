# Health Connect

Strimma integrates with [Android Health Connect](https://developer.android.com/health-and-fitness/health-connect) for bidirectional health data exchange.

## Read

Strimma reads exercise sessions from Health Connect, including heart rate, steps, and active calories. Any app that writes to Health Connect works — Garmin Connect, Samsung Health, Google Fit, Strava, etc.

See [Exercise & Health Connect](../guide/exercise.md) for setup and details.

## Write

Strimma can write CGM glucose readings to Health Connect as blood glucose records (specimen source: interstitial fluid). This makes your glucose data visible to any Health Connect-compatible app.

Enable in **Settings > Exercise > Write glucose to Health Connect**.

Each time a reading arrives, Strimma writes up to 1000 readings from the last 7 days that Health Connect does not have yet, oldest first. Readings from a follower catching up after an outage, and readings stored while writing was off or permission was missing, reach Health Connect this way over the next readings. Readings stored before this version of Strimma, and readings from a manual Nightscout pull, are not written. Writing a reading again updates its record instead of adding a duplicate.

## Requirements

- **Android 14+** — Health Connect is built into the system
- **Android 13** — install Health Connect from Google Play
