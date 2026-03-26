# Strimma — Ideas

**Updated:** 2026-03-25

---

## Next

### AGP Report (Ambulatory Glucose Profile)

ADA-endorsed standard for presenting CGM data to clinicians. 14-day composite glucose profile showing median + percentile bands (25th/75th, 5th/95th) by time of day, plus standardized metrics block (TIR breakdown, GMI, CV%, mean glucose, sensor active %). PDF export for sharing with your endo.

Strimma already computes TIR, GMI, CV%, and coverage. AGP adds the composite profile chart that clinicians know how to read.

### Exercise-BG Context

For each exercise session from Health Connect, compute the complete BG arc. Inspired by Springa's `runBGContext` analysis, all local.

- **Pre-activity:** 30-min BG trend (linear regression), entry BG, entry stability
- **During:** min BG, max drop rate, drop per 10-min bucket
- **Post-activity:** nadir BG within 2h, time to stable, post-activity hypo flag

Exercise markers on graph (colored bands at activity start/end, tap for detail card). Activity history list with BG context summaries.

### Workout Schedule — Google Calendar Integration

Read upcoming workout events from Google Calendar (OAuth). Source-agnostic — events can come from Intervals.icu, Garmin Connect, or manual entry. Strimma doesn't care about the source, only the time and type.

Used as the trigger for Pre-activity Guidance (see below). A scheduled workout within a configurable lookahead window (e.g. 3 hours) activates the guidance card.

### Pre-activity Guidance

Current BG + trend + time until next scheduled workout → readiness assessment and carbohydrate guidance card.

Surfaced on the main screen and optionally in the persistent notification when a workout is approaching.

Example:

```
🏃 Run in 2h 15min
Target BG at start: 8–9 mmol/L
Current: 7.2 mmol/L ↘

→ Consider 20g carbs ~30 min before start.
→ Check trend again 1h before.
```

Logic inputs:

- Current BG and trend (already available)
- Time until workout (from Google Calendar integration)
- Workout type if available from event title/description

**Requires:** Workout Schedule (Google Calendar) integration.

**Future with pump API:** proactive basal reduction triggered from this same data. Not in scope for Strimma as an open-source app — but the decision logic is the same.

---

## Parked

Deferred until there's demand or hardware.

- **Per-category exercise stats** — group activities by type, show average drop rate / typical nadir by starting BG band and entry slope. Needs enough activity data to be meaningful. Requires Exercise-BG Context first.
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

- Health Connect integration — read exercise sessions + heart rate from any fitness app (Garmin, Samsung Health, Fitbit, Strava, etc.); write glucose readings so other health apps can see them
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

- **Pump integration** - Very interesting, but not possible with the available APIs at the moment
- **Tizen/Pebble watch support** — low demand
- **Speech recognition** — niche
- **QR code settings export** — JSON export is the modern equivalent
- **InfoContentProvider** — broadcast intents provide the same data with better security
- **LibreView upload** — Abbott's walled garden
- **Meal photo AI** — requires cloud ML, doesn't fit local-first approach
- **Gamification** — doesn't match Strimma's tone
- **GVI/PGS/GVP variability metrics** — trace-length-based metrics (Thomas 2012, Peyser 2018) are fundamentally sensor-dependent. Reference values were established on Dexcom G4 data with heavy proprietary smoothing. Libre 3 (1-min, less smoothed) produces GVP ~100% for well-controlled T1D (CV 33%, TIR 83%) — misleading when the reference "T1D mean" is 45%. Resampling to 5-min and bucket averaging reduce noise but can't compensate for the smoothing difference. The old PGS (multiplicative formula) is mathematically dubious; Dexcom themselves abandoned these metrics. ATTD international consensus recommends CV as the primary variability metric — Strimma already has it.
