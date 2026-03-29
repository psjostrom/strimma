# v1.0.0 Release Notes — DRAFT

> Fact-checked against source code 2026-03-29. Update before release.

**Strimma v1.0.0 — first public release.**

Open-source Android CGM companion app. Reads glucose from 50+ CGM apps, displays with interactive graph and alerts, pushes to Nightscout.

**Data sources:**
- Companion Mode — reads glucose from your CGM app's notification (Dexcom, Libre, CamAPS FX, Juggluco, xDrip+, Medtronic, Eversense, and more)
- xDrip Broadcast — receives from xDrip-compatible apps (AAPS, Juggluco, xDrip+)
- Nightscout Follower — follows a remote Nightscout server for caregivers
- LibreLinkUp — reads from Abbott's cloud for Libre 3 users

**Display & alerts:**
- Interactive graph with pinch-zoom, pan, scrub-to-inspect, and 24h minimap
- Glucose prediction (configurable: off, 15 min, or 30 min) with "Low in X min" / "High in X min" warnings
- Persistent notification with mini graph
- Home screen widget (Glance) with configurable opacity, graph window, light/dark mode, and color-coded BG
- 8 alert types (urgent low, low, high, urgent high, low soon, high soon, stale, push failed), each with its own notification channel, pauseable by category

**Exercise & training** (via Health Connect):
- Exercise bands on glucose graph with per-session BG analysis
- Exercise stats grouped by activity type and metabolic profile (Aerobic / Resistance / High-Intensity)
- Pre-activity guidance — readiness assessment with IOB-aware carb recommendations and compound risk detection
- Workout schedule from Android calendar with intensity detection

**Stats & analysis:**
- TIR, GMI, CV%, standard deviation, coverage, CSV export (readings)
- Ambulatory Glucose Profile (AGP) — ADA-endorsed 14-day composite with percentile bands
- Per-meal postprandial analysis: TIR, peak excursion, time-to-peak, recovery, IOB at meal, sparklines
- Aggregate postprandial profile with configurable time slots

**Integration:**
- Nightscout push with retry and offline resilience
- Treatment sync with 30-day retention — bolus/carb markers, IOB with configurable insulin curves (Fiasp, Lyumjev, NovoRapid, custom DIA)
- xDrip-compatible BG broadcast
- Local web server (port 17580) for Garmin watchfaces

**Other:**
- Setup wizard with 6-step guided configuration
- mmol/L and mg/dL with configurable thresholds
- Dark / Light / System theme
- 5 languages (English, Swedish, German, French, Spanish)
- Settings and readings export
- First-run empty state for new users

Requires Android 13+. Licensed GPLv3.

---

## Fact-check notes

- **52 packages** in CGM_PACKAGES allowlist (say "50+" not "60+")
- **Prediction is configurable:** Off / 15 min / 30 min (default 15 min)
- **8 alert types:** urgent low, low, high, urgent high, low soon, high soon, stale, push failed
- **2 pause categories:** LOW (urgent low + low + low soon), HIGH (urgent high + high + high soon)
- **3 metabolic profiles:** Aerobic, Resistance, High-Intensity (correct assignments verified)
- **CSV export:** readings only (timestamp, datetime, sgv, direction, delta_mgdl)
- **Treatment retention:** 30 days (LOOKBACK_DAYS=30, PRUNE_DAYS=30)
- **Minimap:** 24h (hardcoded)
- **Retry:** linear backoff, max 12 attempts, capped at 60s
- **Setup wizard:** 6 steps (welcome, units, data source, nightscout, alerts, permissions)

