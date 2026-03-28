# Strimma — Ideas

**Updated:** 2026-03-27

---

## Next

### Per-Category Exercise Stats

Aggregate BG patterns across exercise sessions grouped by activity type and optional intensity band. Spec: `docs/specs/exercise-stats-spec.md`.

---

### Insulin Sensitivity & I:C Ratio Analysis

Strimma has per-meal carbs, insulin dose, and resulting BG curve — enough to infer insulin-to-carb ratios and insulin sensitivity from historical data. Currently not computed. Could enable:
- **I:C ratio estimation** — observe actual BG response per gram of carb given the insulin dose. Identify meals where the ratio was off.
- **Insulin sensitivity factor (ISF)** — how much 1U of insulin drops BG, derived from correction boluses.
- **Expected vs actual excursion** — compare predicted BG rise (from carbs/insulin/ISF) against observed. Flag outliers.

Note: Strimma doesn't do insulin dosing — these would be observational insights, not recommendations. Useful for the user to bring to their endo or adjust pump settings.

### Basal IOB & Temp Basal Utilization

Strimma already fetches and stores temp basal treatments from Nightscout (CamAPS FX generates ~288/day). Currently unused — IOB is bolus-only. Could use temp basals for:
- **Accurate IOB** — integrate basal rate deviations (actual vs profile) into IOB model. Current bolus-only IOB underestimates insulin on board for pump users.
- **Insulin stats** — TDD split (basal vs bolus), insulin-to-carb ratios in meal analysis.
- **Basal visualization** — rate changes on main graph or meal sparklines.

Requires a reference "profile basal" rate to compute deviations, or treating each temp basal as absolute delivery.

---

## Parked

Deferred until there's demand or hardware.
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
- AGP Report (14-day composite glucose profile with percentile bands, standardized metrics, ADA-endorsed format)
- Exercise-BG Context (pre/during/post BG arc analysis for Health Connect exercise sessions, sparklines, detail sheets)
- Workout Schedule — Calendar integration (Android CalendarProvider, calendar picker, planned workouts list with pull-to-refresh)
- Pre-activity Guidance (readiness assessment card with IOB-aware carb recommendations, compound risk detection, forecast integration)

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
