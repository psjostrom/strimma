# Strimma — Ideas

**Updated:** 2026-03-23

---

## Next

### AGP Report (Ambulatory Glucose Profile)

ADA-endorsed standard for presenting CGM data to clinicians. 14-day composite glucose profile showing median + percentile bands (25th/75th, 5th/95th) by time of day, plus standardized metrics block (TIR breakdown, GMI, CV%, mean glucose, sensor active %). PDF export for sharing with your endo.

Strimma already computes TIR, GMI, CV%, and coverage. AGP adds the composite profile chart that clinicians know how to read.

### GVI/PGS Variability Metrics

Two research-validated composite metrics (Thomas et al., Dexcom, 2016):

- **GVI (Glycemic Variability Index)** — ratio of total glucose trace length to ideal straight line. GVI 1.0 = flat, >2.0 = roller coaster. Captures *how* you got your TIR — flat 6.0 all day and bouncing between 4.0 and 10.0 both give ~100% TIR, but very different GVI.
- **PGS (Patient Glycemic Status)** — composite combining GVI, mean glucose, TIR, and hypo frequency. Single number, lower = better. <35 excellent, 35-100 needs work, >100 poor.

Add to existing Statistics screen. ~200 lines on top of existing infrastructure.

### Health Connect Integration

Read exercise sessions + heart rate from any fitness app (Garmin, Samsung Health, Fitbit, Strava, etc.). Write glucose readings so other health apps can see them. Framework module on Android 14+, needs Health Connect app from Play Store on Android 13. Foundation for exercise-BG features.

### Exercise-BG Context

For each exercise session from Health Connect, compute the complete BG arc. Inspired by Springa's `runBGContext` analysis, all local.

- **Pre-activity:** 30-min BG trend (linear regression), entry BG, entry stability
- **During:** min BG, max drop rate, drop per 10-min bucket
- **Post-activity:** nadir BG within 2h, time to stable, post-activity hypo flag

Exercise markers on graph (colored bands at activity start/end, tap for detail card). Activity history list with BG context summaries.

---

## Parked

Deferred until there's demand or hardware.

- **Per-category exercise stats** — group activities by type, show average drop rate / typical nadir by starting BG band and entry slope. Needs enough activity data to be meaningful.
- **Pre-activity guidance** — current BG + trend → readiness assessment. Needs exercise-BG context first.
- **Direct BLE: Dexcom G7/ONE+** — direct connection without official app. Complex, well-documented protocol.
- **Direct BLE: Libre 2/2+** — requires out-of-process algorithm (OOP2).
- **Direct BLE: Libre 3** — most complex, sensor bonds exclusively to one app.
- **Wear OS complications** — glucose on watch faces. Needs Wear OS hardware.
- **Wear OS standalone app** — full watch app with graph. Complications cover 80% of the use case.
- **Multi-OEM testing** — Pixel, Samsung, OnePlus. Different OEMs handle background services differently.
- **Tidepool upload** — cloud sync for clinic reports.
- **Android Auto** — BG display while driving.
- **Lock screen / AOD display** — BG visible without unlocking.
- **Floating widget** — always-visible BG overlay over other apps.
- **Voice readout** — spoken glucose values, hands-free.
- **Calibration** — for sensors that need it (Libre 1/2 without factory calibration). Not needed for G7 or Libre 3.
- **Contribution guide** — architecture overview, how to add a data source, how to run tests.
- **F-Droid listing** — GitHub releases covers distribution for now.

---

## Completed

- Glucose alerts (urgent low, low, high, urgent high, stale) with per-alarm notification channels
- Predictive alerts (low soon, high soon) with threshold crossing detection
- 30-minute prediction (dampened velocity extrapolation)
- Home screen widget (Glance, with graph and configurable opacity)
- Statistics screen (TIR, GMI, CV%, coverage, CSV export)
- mmol/L + mg/dL unit support
- Dark / Light / System theme
- Interactive graph (pinch zoom, pan, scrub-to-inspect, minimap)
- Notification parsing for 60+ CGM apps (Dexcom, Libre, Juggluco, CamAPS, etc.)
- xDrip broadcast receiver (data source)
- Nightscout follower mode (data source)
- Nightscout upload with retry and offline resilience
- Nightscout history backfill
- Treatment sync from Nightscout (bolus/carb markers, IOB)
- xDrip-compatible BG broadcast (verified with GDH)
- Data source picker (Companion / xDrip Broadcast / Nightscout Follower)
- File-based debug logging with 7-day rotation + share
- i18n (English, Swedish, Spanish, French, German)
- GPL v3 license, GitHub releases with signed APKs, README

---

## Rejected

- **Pump integration** (630G/640G/670G) — AAPS handles closed-loop control
- **Tizen/Pebble watch support** — low demand
- **Speech recognition** — niche
- **QR code settings export** — JSON export is the modern equivalent
- **InfoContentProvider** — broadcast intents provide the same data with better security
- **LibreView upload** — Abbott's walled garden
- **Meal photo AI** — requires cloud ML, doesn't fit local-first approach
- **Gamification** — doesn't match Strimma's tone
