# AGP Report — Design Spec

**Date:** 2026-03-24
**Status:** Approved

## Summary

Add an Ambulatory Glucose Profile (AGP) tab to the existing StatsScreen. The AGP is the ADA-endorsed standard for presenting CGM data to clinicians — a 14-day composite glucose profile showing median + percentile bands by time of day, plus standardized metrics.

## Design Decisions

- **Location:** New tab in StatsScreen (Metrics | AGP segmented control)
- **Period:** Fixed 14 days (ADA standard), no period selector on AGP tab
- **Thresholds:** ADA fixed (54/70/180/250 mg/dL) for 5-tier TIR — independent of user's configured thresholds which remain on Metrics tab
- **Buckets:** 15-minute time-of-day buckets (96 per day), standard AGP resolution
- **Percentiles:** 5th, 25th, 50th (median), 75th, 95th
- **No PDF export** (may add later)

## Architecture

### 1. `AgpCalculator` (data/)

Pure computation, no Android dependencies. Testable.

**Input:** `List<GlucoseReading>`, returns `AgpResult`:

```kotlin
data class AgpBucket(
    val minuteOfDay: Int,      // 0, 15, 30, ... 1425
    val p5: Double,            // mg/dL
    val p25: Double,
    val p50: Double,
    val p75: Double,
    val p95: Double,
    val count: Int             // readings in this bucket across all days
)

data class AgpMetrics(
    val veryLowPercent: Double,   // <54
    val lowPercent: Double,       // 54-70
    val inRangePercent: Double,   // 70-180
    val highPercent: Double,      // 180-250
    val veryHighPercent: Double,  // >250
    val averageMgdl: Double,
    val gmi: Double,
    val cv: Double,
    val sensorActivePercent: Double,  // readings / expected readings
    val count: Int
)

data class AgpResult(
    val buckets: List<AgpBucket>,
    val metrics: AgpMetrics
)
```

**Sensor active %:** Compare actual reading count to expected. Libre 3 = 1 reading/min = 20,160 expected in 14 days. Other sensors may differ, so compute expected from actual min interval observed in data.

**Percentile method:** Linear interpolation (standard statistical percentile).

### 2. `AgpChart` composable (ui/)

Compose Canvas rendering the 24h composite profile:

- **X-axis:** 00:00 to 24:00, labels every 3h (00, 03, 06, ..., 21)
- **Y-axis:** Shared `computeYRange()` from `graph/GraphColors.kt`
- **Target zone:** 70-180 mg/dL subtle fill band (reuse in-range zone pattern from MainScreen)
- **5th-95th band:** Light fill (InRange at ~15% alpha)
- **25th-75th band:** Medium fill (InRange at ~30% alpha)
- **Median line:** Bold InRange color, 2.5dp stroke
- **Low threshold line:** Dashed coral at 70 mg/dL
- **High threshold line:** Dashed amber at 180 mg/dL
- Dark surface card with 12.dp radius (matches existing cards)
- Y-axis labels in user's configured glucose unit

### 3. StatsScreen Changes

- Add two-tab segmented control at top: "Metrics" | "AGP"
- Metrics tab: existing content unchanged (period selector + stats)
- AGP tab: AgpChart + 5-tier TIR bar + ADA metrics block
- Period selector only visible on Metrics tab

### 4. Colors

New colors in `Color.kt`:
- `VeryLow` — deeper red for <54 zone (#E53935 or similar)
- `VeryHigh` — deeper orange for >250 zone (#EF6C00 or similar)

Existing colors unchanged:
- `BelowLow` (coral #FF4D6A) — for 54-70 zone
- `InRange` (cyan #56CCF2) — for 70-180 zone
- `AboveHigh` (amber #FFB800) — for 180-250 zone

### 5. Strings

New string resources for AGP tab labels, 5-tier zone names, metrics labels. Support all existing locales (en, sv, es, fr, de).

## Testing

- `AgpCalculatorTest` — unit tests for bucketing, percentile computation, metrics, edge cases (empty data, single day, gaps)
- Manual verification on device with real 14-day data

## Files to Create/Modify

**New:**
- `data/AgpCalculator.kt`
- `ui/AgpChart.kt`
- `test/.../data/AgpCalculatorTest.kt`

**Modified:**
- `ui/StatsScreen.kt` — add tab control, AGP tab content
- `ui/theme/Color.kt` — VeryLow, VeryHigh colors
- `res/values/strings.xml` + translations
