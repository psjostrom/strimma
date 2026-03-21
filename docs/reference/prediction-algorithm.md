# Prediction Algorithm

Strimma predicts glucose trajectory using **dampened velocity extrapolation** — a weighted least-squares velocity estimate with exponential dampening.

---

## Overview

The prediction algorithm answers: "Given recent glucose behavior, where is glucose heading in the next 15–30 minutes?"

It uses three stages:

1. **Weighted velocity estimation** — compute how fast glucose is changing, emphasizing recent data
2. **Dampened projection** — extend the trajectory forward, assuming velocity naturally decreases over time
3. **Threshold crossing detection** — find when (if ever) the projection crosses low or high thresholds

---

## Stage 1: Weighted Velocity Estimation

### Data Window

The algorithm uses readings from the last **12 minutes** (`LOOKBACK_MS = 720,000 ms`). At least 2 readings are needed.

### Exponential Time Weighting

Recent readings carry more weight than older ones:

```
weight(t) = e^(DECAY × t)
```

where `t` is seconds before now (negative, so recent readings get higher weights) and `DECAY = 0.35`.

**Effect:** The last 4 minutes hold approximately **84% of the total weight**. The full 12-minute window is preserved for better slope estimation, but the velocity is dominated by recent behavior.

### Weighted Linear Regression

A weighted least-squares regression computes the slope (velocity) of glucose over time:

```
v₀ = (Σw × Σw·t·y - Σw·t × Σw·y) / (Σw × Σw·t² - (Σw·t)²)
```

where:

- `w` = weight for each reading
- `t` = time (minutes)
- `y` = glucose (mg/dL)

The result `v₀` is the estimated velocity in **mg/dL per minute**.

### Velocity Sanity Gate

If the computed velocity exceeds **±9.0 mg/dL/min** (`MAX_VELOCITY`), the prediction is rejected as a sensor artifact. Normal physiological glucose change rarely exceeds this rate.

---

## Stage 2: Dampened Projection

Instead of projecting glucose as a straight line (which over-predicts), Strimma uses a dampened model where velocity naturally decreases:

```
x(t) = x₀ + (v₀ / DAMP) × (1 - e^(-DAMP × t))
```

where:

- `x₀` = current glucose (mg/dL)
- `v₀` = current velocity (mg/dL per minute)
- `DAMP = 0.05` — dampening coefficient
- `t` = minutes into the future

### Properties

- At `t = 0`: `x(0) = x₀` — prediction starts at current glucose (natural anchor)
- Initial slope equals `v₀` — matches current rate of change
- Velocity halves at approximately **14 minutes**
- As `t → ∞`: `x → x₀ + v₀/DAMP` — glucose approaches a maximum excursion

### Why Dampening?

Straight-line projection causes false alarms. If glucose is rising at 2 mg/dL/min, a straight line predicts it will rise 60 mg/dL in 30 minutes — but in reality, glucose trends usually slow down as the body responds.

The dampened model was specifically chosen to handle **V-recovery** scenarios: when glucose drops sharply (treating a low) and then bounces back, a straight-line or polynomial model predicts a false high from the upward bounce. The dampened model correctly predicts the bounce will decelerate.

---

## Stage 3: Threshold Crossing Detection

If the current glucose is **in range** (between bgLow and bgHigh):

1. Walk through the prediction minute by minute
2. Check if the predicted glucose crosses bgLow (going low) or bgHigh (going high)
3. Return the first crossing with:
    - Type: LOW or HIGH
    - Minutes until crossing
    - Predicted glucose at the crossing point

Predicted values are clamped to **18–540 mg/dL** to stay within physiologically plausible bounds.

!!! note
    The prediction algorithm itself reports all crossings from minute 1 onward. The 4-minute minimum filter shown in the UI and described in [Prediction](../guide/prediction.md) is applied by the alert system, not the prediction algorithm.

---

## Parameters

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `LOOKBACK_MS` | 720,000 (12 min) | Velocity estimation window |
| `DECAY` | 0.35 | Exponential time weighting emphasis |
| `DAMP` | 0.05 | Velocity dampening rate |
| `MAX_VELOCITY` | 9.0 mg/dL/min | Sensor artifact rejection |
| `MGDL_FLOOR` | 18 mg/dL | Prediction lower clamp |
| `MGDL_CEILING` | 540 mg/dL | Prediction upper clamp |

---

## Sensor Interval Compatibility

The algorithm works with any CGM reading interval:

- **Libre 3:** 1-minute readings — the 12-minute window contains ~12 data points, excellent velocity estimation
- **Dexcom G6/G7:** 5-minute readings — the 12-minute window contains 2–3 data points, sufficient for velocity estimation
- **Irregular intervals:** The weighted regression handles non-uniform spacing naturally

---

## Visual Output

### On the Graph

The prediction appears as a **dashed line** extending from the most recent reading into the future. Each predicted point is computed at 1-minute intervals up to the configured prediction horizon (15 or 30 minutes).

### In the BG Header

When a threshold crossing is detected, a **prediction pill** appears:

- **Low in X min** — red-tinted background, red text
- **High in X min** — amber-tinted background, amber text

### In the Notification

The notification subtitle includes a compact warning (e.g., "Low 10m") when applicable.
