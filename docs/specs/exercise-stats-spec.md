# Per-Category Exercise Stats — Spec

**Status:** Draft (v2 — research-informed)
**Date:** 2026-03-27
**Depends on:** Exercise-BG Context (done), Health Connect integration (done)

---

## Problem

Strimma computes BG context for individual exercise sessions but doesn't aggregate patterns across sessions. A T1D exerciser who has done 15 runs and 10 bike rides can't see "running drops my BG twice as fast as cycling" or "when I start above 8, I rarely go hypo."

This is the single most actionable insight a CGM app can give an active T1D user: **what happens to YOUR blood sugar when YOU do THIS type of exercise.**

### Market Gap

Research across 14 diabetes/CGM apps and 14 fitness apps confirms: **no consumer app** does per-category BG pattern aggregation. Most diabetes apps have a generic "activity" marker on the timeline. Even specialized T1D exercise apps (acT1ve, GlucoseZone, Engine 1) provide guidance but don't learn from accumulated personal data. Strimma's pre-activity guidance already puts it ahead of most — adding pattern learning would make it unique.

### Category Unification

Strimma currently has two disconnected category systems:

- **`WorkoutCategory`** (calendar-based): EASY, INTERVAL, LONG, STRENGTH, FALLBACK — runner-centric vocabulary.
- **`ExerciseCategory`** (Health Connect-based): RUNNING, WALKING, CYCLING, SWIMMING, OTHER — too few categories.

**Resolution:** Kill `WorkoutCategory`. Expand `ExerciseCategory` as the single category system. Add `MetabolicProfile` as the physiological dimension that actually drives BG response.

---

## Design Constraints

- **Mass market.** Must work for a runner, a cyclist, a climber, a dog walker, a gym-goer. No assumptions about which sports the user does.
- **Zero configuration required.** Works out of the box with Health Connect data. No setup, no onboarding questions.
- **Local-only.** All computation on-device from Room DB. No cloud, no AI.
- **Honest about data.** Don't show stats until there's enough data to be meaningful. Show "N sessions" so the user can judge confidence.
- **Physiology over labels.** The BG response is driven by energy system (aerobic/anaerobic) and intensity, not by sport name. The model must reflect this.

---

## Research Basis

### Clinical Consensus (Riddell 2017, Moser/EASD/ISPAD 2024, ADA)

The three physiological dimensions that determine BG response to exercise:

1. **Energy system** — aerobic (BG drops), anaerobic (BG rises or stable), resistance (delayed drop)
2. **Intensity** — determines which energy system engages. Same sport at different intensities has opposite BG effects.
3. **Duration** — dose-response for aerobic exercise. Longer = more total BG drop.

The sport name (running vs cycling vs swimming) is secondary. A moderate run and a moderate bike ride produce similar BG trajectories. An easy run and an interval run produce opposite ones.

### Pre-Exercise Targets (Clinical Evidence)

| Metabolic Profile | Recommended Starting BG | Source |
|-------------------|------------------------|--------|
| Aerobic (moderate, >30 min) | 7–10 mmol/L (126–180 mg/dL) | ADA, Riddell |
| High-intensity / anaerobic | 5–14 mmol/L (90–250 mg/dL) | ADA (wider range, lower hypo risk) |
| Resistance training | 7–10 mmol/L (126–180 mg/dL) | Riddell |

Key modifiers: IOB (most critical), time of day (morning = more protection from dawn phenomenon), starting BG trend.

### Fitness Industry Consensus

All major fitness platforms (Strava, Garmin, Apple, WHOOP) agree: **activity type and intensity are separate dimensions.** No major app encodes intensity into the activity type. HR zones are the universal intensity measure. This aligns with clinical guidelines.

---

## Category Model

### Two Dimensions

**Dimension 1: Activity Type (`ExerciseCategory`)** — what the user sees and picks. From Health Connect exercise type for completed sessions, from title keywords for calendar events.

**Dimension 2: Metabolic Profile (`MetabolicProfile`)** — what drives BG response. Auto-derived from HR data when available, inferred from activity type when not.

### ExerciseCategory (Expanded)

One enum used for both planned and completed workouts:

| Category | HC Types | Calendar Keywords | Default Metabolic Profile |
|----------|----------|-------------------|--------------------------|
| RUNNING | running, running_treadmill | run, jog, sprint, löpning | AEROBIC |
| WALKING | walking | walk, promenad | AEROBIC |
| HIKING | hiking | hike, hiking, vandring | AEROBIC |
| CYCLING | biking, biking_stationary | bike, cycle, cykel | AEROBIC |
| SWIMMING | swimming_open_water, swimming_pool | swim, simning | AEROBIC |
| STRENGTH | weightlifting, calisthenics | gym, strength, weights, lift, styrka | RESISTANCE |
| YOGA | yoga, pilates | yoga, pilates | AEROBIC |
| ROWING | rowing, rowing_machine | row, erg, rodd | AEROBIC |
| SKIING | skiing_cross_country, skiing_downhill, snowboarding | ski, snowboard, skid | AEROBIC |
| CLIMBING | rock_climbing | climb, boulder, klättr | RESISTANCE |
| MARTIAL_ARTS | martial_arts | martial, boxing, mma, kampsport | HIGH_INTENSITY |
| OTHER | everything else | _(fallback)_ | AEROBIC |

**Calendar event mapping:** `ExerciseCategory.fromTitle(title)` replaces `WorkoutCategory.fromTitle(title)`. Includes Swedish keywords.

**Completed session mapping:** `ExerciseCategory.fromHCType(type)` — expansion of existing method.

### MetabolicProfile

Three profiles based on the clinical consensus (Riddell, EASD/ISPAD):

```
enum MetabolicProfile {
    AEROBIC,        // Sustained moderate effort. BG drops steadily during and after.
    HIGH_INTENSITY, // Bursts >80% max HR. BG stable/rises during, drops later.
    RESISTANCE      // Strength-based. Small initial change, delayed post-exercise drop.
}
```

**How it's determined (priority order):**

1. **From HR data (best):** If max HR is set and session has HR samples:
   - Avg HR >80% max HR → HIGH_INTENSITY
   - Otherwise → use default from ExerciseCategory
   - Exception: STRENGTH/CLIMBING always → RESISTANCE regardless of HR

2. **From calendar title keywords:** If intensity keywords detected in planned workout title:
   - "interval", "tempo", "threshold", "speed", "fartlek", "hiit", "sprint", "intervall" → HIGH_INTENSITY
   - "easy", "recovery", "lugn", "lätt" → AEROBIC (confirms default)
   - No match → use default from ExerciseCategory

3. **From ExerciseCategory default (fallback):** Every category has a sensible default metabolic profile (see table above). Most map to AEROBIC, which is the safe/conservative default.

**Why three profiles and not five:** Clinical research identifies three distinct BG response patterns. Finer granularity (e.g., separating "light aerobic" from "moderate aerobic") doesn't produce meaningfully different BG trajectories. The HR-based intensity breakdown within a profile captures that variation.

---

## Pre-Activity Guidance (Reworked)

### Target Ranges by MetabolicProfile

Pre-activity BG targets are driven by metabolic profile, not sport name. This matches clinical guidelines.

| Metabolic Profile | Default Target (mmol/L) | Default Target (mg/dL) | Rationale |
|-------------------|------------------------|------------------------|-----------|
| AEROBIC | 7–10 | 126–180 | ADA/Riddell consensus for sustained aerobic |
| HIGH_INTENSITY | 8–12 | 144–216 | Lower hypo risk, may spike then drop |
| RESISTANCE | 7–10 | 126–180 | Similar to aerobic, delayed effect |

**Intensity modifier for calendar events:** When a calendar event's title contains intensity keywords, the metabolic profile overrides the category default. "Interval Run" → RUNNING + HIGH_INTENSITY → target 8–12. "Easy Swim" → SWIMMING + AEROBIC → target 7–10.

**Special case modifiers** (additive, applied after metabolic profile target):

| Condition | Modifier | Rationale |
|-----------|----------|-----------|
| Swimming (any profile) | +0.5 mmol/L to low end | Hard to treat hypo in water |
| Skiing (any profile) | +0.5 mmol/L to low end | Cold + altitude + hard to eat |

Users can override target ranges per ExerciseCategory in settings. The metabolic profile provides the default; the user's per-category override takes precedence when set.

### Settings UI Change

Replace the current 4 hardcoded `WorkoutCategory` target rows with a dynamic list based on the user's actual activities. Only show categories that appear in their completed sessions or upcoming calendar events. Each row: emoji + category name + editable low/high targets.

### What PreActivityAssessor Needs

1. Accept `MetabolicProfile` instead of raw target values
2. Look up per-category override first, fall back to metabolic profile defaults
3. Apply special case modifiers (swimming, skiing)
4. Assessment logic itself (BG thresholds, slope checks, IOB carb calculation, compound risk) doesn't change

---

## Data Model

### CategoryStats

Aggregated stats for one activity type (or activity type + metabolic profile):

```
CategoryStats {
    category: ExerciseCategory
    metabolicProfile: MetabolicProfile?  // null = all profiles combined
    sessionCount: Int

    // Entry conditions
    avgEntryBG: Double                   // mg/dL
    entryBGDistribution: Map<BGBand, Int>

    // During exercise
    avgMinBG: Double                     // mg/dL
    avgDropRate: Double                  // mg/dL per 10 min
    avgDurationMin: Int

    // Outcomes
    hypoCount: Int                       // sessions with hypo during or within 4h after
    hypoRate: Double                     // hypoCount / sessionCount

    // Post-exercise (4h window)
    avgPostNadir: Double?                // mg/dL
    avgPostHighest: Double?              // mg/dL
    postHypoCount: Int                   // sessions with post-exercise hypo

    // By starting BG band
    statsByEntryBand: Map<BGBand, BandStats>
}
```

### BGBand

Entry BG grouped into clinically meaningful bands:

```
BGBand {
    LOW        // below bgLow setting (default 4.0 / 72 mg/dL)
    LOW_RANGE  // bgLow to 7.0 mmol/L (126 mg/dL)
    MID_RANGE  // 7.0 to 10.0 mmol/L (126-180 mg/dL)
    HIGH       // above 10.0 mmol/L (180 mg/dL)
}
```

### BandStats

```
BandStats {
    sessionCount: Int
    avgMinBG: Double
    avgDropRate: Double
    hypoRate: Double
    avgPostNadir: Double?
}
```

### Minimum Data Thresholds

- **Show category at all:** >= 3 sessions with BG coverage > 50%
- **Show metabolic profile breakdown:** >= 3 sessions per profile
- **Show entry band stats:** >= 3 sessions in that band
- **Show post-exercise stats:** >= 3 sessions with post-exercise BG data

Below threshold: show "N more sessions needed" with a subtle progress indicator.

---

## UI

### Location

New tab in ExerciseHistoryScreen: **Planned | Completed | Patterns**

### Patterns Tab Layout

Two view modes, toggled by a segmented button at the top: **By Activity** | **By Profile**

#### By Activity View (default)

Category cards sorted by session count descending. One card per category that meets the minimum threshold.

```
[emoji] Running                          12 sessions
Avg drop: 0.7 mmol/L per 10 min
Typical low: 5.2 mmol/L
Hypo risk: 17%                           (2 of 12)

  By starting BG:
  Below 7    ████████  5 sessions  low: 4.1  hypo: 40%
  7 to 10    ████████  6 sessions  low: 5.8  hypo: 0%
  Above 10   ██        1 session   low: 6.9  hypo: 0%
```

If metabolic profile data is available, show profile chip on each card:

```
[emoji] Running                    AEROBIC · 10 sessions
                                   HIGH_INTENSITY · 2 sessions
```

#### By Profile View

Groups all activities by their metabolic profile. This is where the real BG insights live — "all your aerobic sessions behave similarly regardless of sport."

```
Aerobic                              22 sessions
Running (10) · Cycling (8) · Walking (4)
Avg drop: 0.6 mmol/L per 10 min
Typical low: 5.4 mmol/L
Hypo risk: 14%

  By starting BG:
  Below 7    ████████  8 sessions  low: 4.3  hypo: 38%
  7 to 10    ████████  12 sessions low: 5.9  hypo: 0%
  Above 10   ██        2 sessions  low: 7.1  hypo: 0%

High Intensity                        5 sessions
Running (3) · Martial Arts (2)
Avg drop: 0.2 mmol/L per 10 min
Typical low: 6.8 mmol/L
Hypo risk: 0%
```

### Empty State

When no category meets the minimum threshold:

> "Keep exercising with Strimma running. After a few sessions of the same type, you'll see your personal BG patterns here."

### Visual Style

- Cards use the standard Strimma card style (12.dp radius, surfaceVariant, outlineVariant border)
- Entry band bars use the existing status colors (BelowLow for LOW, InRange for MID_RANGE, AboveHigh for HIGH)
- Hypo rate > 25% gets BelowLow color as a warning
- Numbers shown in the user's configured glucose unit (mmol/L or mg/dL)
- Profile chips use subtle tinted backgrounds: AEROBIC=TintInRange, HIGH_INTENSITY=TintWarning, RESISTANCE=surfaceVariant

---

## Computation

### When to Compute

**On-demand when Patterns tab is selected.** Not precomputed, not cached. The dataset is small (tens to low hundreds of sessions) and the computation is trivial — grouping and averaging.

### Algorithm

```
1. Load all exercise sessions from Room
2. For each session:
   a. Compute ExerciseBGContext (already exists in ExerciseBGAnalyzer)
   b. Determine ExerciseCategory from HC type
   c. Determine MetabolicProfile:
      - If max HR set + HR data available: avg HR >80% → HIGH_INTENSITY
      - If category is STRENGTH or CLIMBING: → RESISTANCE
      - Else: → category default (usually AEROBIC)
   d. Determine BGBand from entryBG
3. Group by (category) for By Activity view
4. Group by (metabolicProfile) for By Profile view
5. For each group with sessionCount >= threshold:
   a. Compute averages (entryBG, minBG, dropRate, postNadir)
   b. Compute hypo rate
   c. Sub-group by BGBand, compute BandStats
6. Return results sorted by sessionCount desc
```

### Performance

ExerciseBGAnalyzer already loads readings for each session individually. For the stats view, we could optimize by loading all readings in one query spanning the full exercise history range. But premature — profile first, optimize if needed. With 100 sessions and 1-min Libre 3 data, we're talking ~50k readings total. Room handles this fine.

---

## Settings

**Max Heart Rate** — optional Int, no default. Shown in Exercise settings with helper text: "Used for intensity classification and exercise stats. Leave empty to use defaults." Input: number field, valid range 120-220.

**Per-category target ranges** — replaces the current 4 hardcoded `WorkoutCategory` rows. Dynamic list showing only categories the user has encountered (from HC history or calendar events). Each row: emoji + category name + editable low/high targets. Default values come from the category's metabolic profile.

---

## Migration

### Kill `WorkoutCategory`

1. Remove `data/calendar/WorkoutCategory.kt`
2. Add `fromTitle(title: String)` to `ExerciseCategory` — keyword matching for calendar events
3. Add `defaultMetabolicProfile` to `ExerciseCategory` enum values
4. Add `MetabolicProfile` enum with default target ranges
5. Add intensity keyword detection: `MetabolicProfile.fromKeywords(title)` for calendar events
6. Update `WorkoutEvent` to use `ExerciseCategory` + `MetabolicProfile` instead of `WorkoutCategory`
7. Update `CalendarReader.getUpcomingWorkouts()` accordingly
8. Update `PreActivityAssessor` to accept `MetabolicProfile`-based targets with per-category overrides
9. Update `ExerciseSettings` — dynamic target list keyed by `ExerciseCategory`
10. Update `GuidanceState`, `MainViewModel`, `PlannedWorkoutCard`, `PreActivityCard`
11. Migrate DataStore target settings (see below)

### DataStore Migration

Current keys: `workout_target_low_EASY`, `workout_target_high_EASY`, etc.

New keys: `exercise_target_low_RUNNING`, `exercise_target_high_RUNNING`, etc.

Migration: on first access, read old keys if new keys are absent. Map EASY/INTERVAL/LONG → RUNNING (use EASY values as most conservative). Map STRENGTH → STRENGTH. Delete old keys after migration.

---

## What This Is NOT

- **Not AI analysis.** No LLM, no natural language insights. Just numbers and simple visual groupings.
- **Not Springa.** No insulin context, no training load, no fitness data, no fuel rate. Just BG + exercise type + HR.
- **Not prescriptive in stats.** Shows patterns, doesn't tell the user what to eat. Pre-activity guidance handles that.
- **Not real-time.** Historical patterns only.
- **Not sport-specific metrics.** No pace, cadence, power, stroke count. Strimma tracks BG, not athletic performance.

---

## Research Sources

### Clinical Guidelines
- Riddell et al. 2017 — Exercise management in T1D consensus statement (Lancet Diabetes & Endocrinology)
- Moser et al. 2024 — EASD/ISPAD position statement on AID and exercise (Diabetologia)
- ADA — Physical Activity/Exercise and Diabetes position statement

### Key Findings Informing Design
- Aerobic exercise: BG drops steadily; anaerobic/HIIT: BG stable or rises (opposite effects)
- Intensity (HR zones) determines energy system, not sport name
- Starting BG is the primary predictor of exercise outcome
- Pre-exercise targets: aerobic 7-10 mmol/L, anaerobic/HIIT 5-14 mmol/L (ADA)
- Post-exercise hypoglycemia risk elevated for 6-15 hours (late-onset)
- 3 metabolic categories (aerobic/anaerobic/resistance) are clinically established

### Fitness App Patterns
- All major platforms separate activity type from intensity (Strava, Garmin, Apple, WHOOP)
- HR zones are the universal intensity measure (5 zones, % of max HR)
- Hierarchical grouping: sport family → specific activity → tags/attributes

### Diabetes App Gaps
- No consumer app does per-category BG pattern aggregation
- Most apps have generic "activity" markers without type distinction
- Pre-activity guidance is extremely rare (only acT1ve, GlucoseZone, Engine 1)
- Pump systems use single "exercise mode" without type/intensity distinction

---

## Future Extensions (not in scope)

- **PDF export** — include exercise stats in the AGP report for endo visits
- **Trend over time** — "your hypo rate during runs dropped from 40% to 10% over 3 months"
- **Cross-category comparison chart** — visual overlay of BG drop curves by category
- **Smart pre-activity targets** — feed accumulated stats back into PreActivityAssessor for data-driven targets
- **Late-onset hypo tracking** — extend post-exercise window to 8-15h (clinically significant per research)
- **Time-of-day dimension** — morning vs afternoon exercise BG patterns (circadian effect documented in research)
- **Auto-detect max HR** — infer from peak HR across all sessions (common fitness app pattern)
