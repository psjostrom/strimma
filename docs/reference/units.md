# Units: mmol/L vs mg/dL

Strimma supports both glucose units used worldwide.

---

## The Two Units

| Unit | Used in | Example value |
|------|---------|---------------|
| **mmol/L** (millimoles per litre) | Europe, Australia, Canada, most of Asia | 5.5 |
| **mg/dL** (milligrams per decilitre) | USA, Germany, Austria, Japan, some other countries | 99 |

They measure the same thing — glucose concentration in blood — just with different scales.

---

## Conversion

```
mg/dL = mmol/L × 18.0182
mmol/L = mg/dL ÷ 18.0182
```

Strimma uses the factor **18.0182** for all conversions. This is the standard molecular weight conversion factor for glucose.

### Quick Reference

| mmol/L | mg/dL | Meaning |
|--------|-------|---------|
| 2.0 | 36 | Severe hypoglycemia |
| 3.0 | 54 | Urgent low (international threshold) |
| 3.9 | 70 | Low (clinical threshold) |
| 4.0 | 72 | Strimma default low |
| 5.5 | 99 | Normal fasting |
| 7.0 | 126 | Typical post-meal target |
| 10.0 | 180 | Strimma default high |
| 13.0 | 234 | Urgent high |
| 22.2 | 400 | Very high |

---

## How Strimma Handles Units

### Internal Storage

All glucose values are stored internally as **mg/dL integers**. This matches the Nightscout protocol and is the standard in the CGM industry (SGV = Sensor Glucose Value in mg/dL).

### Display Conversion

When you select mmol/L in settings, Strimma converts at display time:

- **mmol/L:** 1 decimal place (e.g., "5.5")
- **mg/dL:** integer (e.g., "99")

### Threshold Storage

All threshold settings (low, high, alert thresholds) are also stored in mg/dL internally. When you change units in settings, the displayed threshold values update automatically.

### Settings Migration

If you started with mmol/L thresholds (older versions), Strimma automatically migrates them to mg/dL storage on first launch. This is a one-time migration.

---

## Changing Units

1. Go to **Settings > Display**
2. Tap **mmol/L** or **mg/dL**

The change takes effect immediately across the entire app — main screen, graph axes, notification, widget, statistics, and all threshold displays.

!!! note
    Changing units does not affect your data or thresholds. It only changes how values are displayed. A reading of 100 mg/dL is always 5.6 mmol/L, regardless of the display setting.
