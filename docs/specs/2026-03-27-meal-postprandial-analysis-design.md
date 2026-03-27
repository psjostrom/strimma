# Per-Meal Postprandial Analysis — Design Spec

**Date:** 2026-03-27

## Overview

A "Meals" tab on the Stats screen that analyzes how each meal affects glucose. Every carb event from Nightscout treatments becomes an analyzable meal with a postprandial BG profile, per-meal metrics, and aggregate patterns grouped by time-of-day and carb size.

No consumer CGM app does this well. Undermyfork is the closest (2h fixed window, TIR-only). This implementation uses clinically validated metrics from the CGM-HYPE study and Glucose360 research over an adaptive window.

## Data Pipeline

### Retention Change

Treatment retention extended from 48h → 14 days in `TreatmentSyncer.PRUNE_MS`. BG readings already kept 30 days. No backfill mechanism needed.

### Analysis Flow

1. Query treatments with `carbs > 0` for the selected period (14d default)
2. For each carb event, define the postprandial window (see Adaptive Window below)
3. Query `readingsInRange(mealTime, windowEnd)` for the BG arc
4. Compute per-meal metrics
5. Group by time-of-day with carb-size breakdown within each

### Adaptive Window

- **Default:** 3 hours after carb event timestamp
- **Extension:** Extend to 4 hours if BG has not returned to baseline at 3h (baseline = average BG in 15 minutes preceding the carb event)
- **Early cutoff:** If another carb event occurs before the window ends, the window ends at the next carb event's timestamp. The next meal gets its own window.

## Per-Meal Metrics

### Collapsed Card (list view)

- Carb amount (g) + timestamp
- TIR pill: ≥80% green, 50–79% amber, <50% red
- Pre-meal BG → Peak BG (display units)
- Sparkline: zone-colored dots + threshold dashed lines + excursion fill above baseline

### Expanded Card (tap to reveal)

- **Peak (Cmax):** Maximum BG in the postprandial window
- **Excursion:** Peak − baseline (how far above starting BG)
- **Time to peak (Tmax):** Minutes from meal to Cmax
- **Recovery time (GRTB):** Minutes from peak to return to baseline. If BG doesn't return within the window, show the window duration with a ">" prefix (e.g., ">180 min")
- **IOB at meal time:** Insulin on board at the carb event timestamp, computed via `IOBComputer`
- **iAUC:** Incremental area under the curve above baseline (trapezoidal approximation, mg/dL·min internally, converted for display)
- Larger sparkline with peak value annotation

## Aggregate View

### Grouping: Time-of-Day Primary

Tabs or chips: **Breakfast / Lunch / Dinner / Snack / All**

Time boundaries:
- Breakfast: 06:00–10:00
- Lunch: 11:30–14:30
- Dinner: 17:00–21:00
- Snack: everything else (10:00–11:30, 14:30–17:00, 21:00–06:00)

### Grouping: Carb Size Secondary

Within each time-of-day group, break down by carb amount:
- Small: <20g
- Medium: 20–50g
- Large: >50g

### Aggregate Metrics Per Group

- Number of meals
- Average TIR (%)
- Average excursion (mmol/L or mg/dL)
- Average recovery time (min)

## Architecture

Follows the exercise stats pattern (`ExerciseBGAnalyzer` / `CategoryStatsCalculator`).

### New Files

- `data/meal/MealAnalyzer.kt` — Computes `MealPostprandialResult` for a single carb event. Takes a Treatment + list of GlucoseReading + bgLow/bgHigh + list of other treatments (for window cutoff) + insulin settings (for IOB).
- `data/meal/MealPostprandialResult.kt` — Data class: baseline, peak, excursion, timeToPeak, recoveryMinutes, tir, iAuc, iobAtMeal, readings (for sparkline), windowMinutes, carbGrams, mealTime.
- `data/meal/MealStatsCalculator.kt` — Groups results by time-of-day and carb size, computes aggregate metrics.
- `data/meal/MealTimeSlot.kt` — Enum: BREAKFAST, LUNCH, DINNER, SNACK with hour boundaries and display labels.
- `data/meal/CarbSizeBucket.kt` — Enum: SMALL, MEDIUM, LARGE with gram thresholds and display labels.
- `ui/MealStatsTab.kt` — Composable for the Meals tab content: aggregate header + meal card list.
- `ui/MealCard.kt` — Composable for collapsed/expanded meal card with sparkline.
- `ui/MealSparkline.kt` — Canvas composable: zone-colored dots, threshold lines, excursion fill, baseline.

### Modified Files

- `network/TreatmentSyncer.kt` — Change `PRUNE_MS` from 48h to 14 days. Adjust lookback window for Nightscout fetch to 14 days on initial sync.
- `ui/StatsScreen.kt` — Add "Meals" tab alongside existing Metrics and AGP tabs.
- `data/TreatmentDao.kt` — Add query: `treatmentsWithCarbsInRange(start: Long, end: Long): List<Treatment>` (where carbs > 0).

### Reused Components

- `ReadingDao.readingsInRange()` — BG data for each meal window
- `IOBComputer.computeIOB()` — IOB at meal time
- `GlucoseUnit` — display-time conversion
- `GraphColors.canvasColorFor()` — zone coloring for sparkline dots (Compose equivalent)
- Design system colors from `Color.kt` — TIR pill tints, status colors

## Key Decisions

- **No food logging.** Strimma doesn't know what the user ate — just carb amount and timestamp from Nightscout. That's enough.
- **Baseline** = average BG in the 15 minutes preceding the carb event. If fewer than 3 readings in that window, use the single closest reading before the event.
- **TIR scoring** uses the user's existing bgLow/bgHigh thresholds (not hardcoded ADA targets).
- **Units:** All internal math in mg/dL. Display-time conversion via `GlucoseUnit`. iAUC stored as mg/dL·min, displayed as mmol/L·min when in mmol mode.
- **Overlapping meals:** Window cut short at next carb event timestamp. Each meal gets its own independent window.
- **Empty states:** "No meal data" if no carb treatments found. "Not enough BG data" if readings are sparse (< 50% coverage of the window).
- **Period selector:** Reuse the existing Stats screen period selector (24h / 7d / 14d / 30d). 30d works since readings are kept 30 days; treatments will now also be kept 14 days (meals older than 14 days won't have treatment data but will have readings — show "no meal data" for those periods).

## Sparkline Rendering

Five layers, bottom to top:
1. **In-range zone band** — subtle fill between bgLow and bgHigh y-positions
2. **Threshold lines** — dashed lines at bgLow and bgHigh
3. **Excursion fill** — gradient fill from the BG curve down to baseline (visualizes iAUC)
4. **BG curve** — zone-colored dots connected by lines
5. **Baseline** — faint dashed horizontal line at the pre-meal BG level

Collapsed sparkline: ~40px tall, minimal detail.
Expanded sparkline: ~80px tall, peak value annotated.

## Testing

Follow the exercise stats testing pattern:
- `MealAnalyzerTest` — unit tests for metric computation (peak, excursion, time-to-peak, recovery, iAUC, TIR). Test adaptive window logic (3h default, 4h extension, early cutoff).
- `MealStatsCalculatorTest` — grouping by time-of-day and carb size, aggregate metric computation.
- `MealTimeSlotTest` — boundary conditions for time slot classification.
