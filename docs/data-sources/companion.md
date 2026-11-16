# Companion Mode

Companion mode is the default and most common way to use Strimma. It reads glucose values from your CGM app's notifications.

---

## How It Works

1. Your CGM app receives a glucose reading from your sensor via Bluetooth
2. The CGM app posts a notification showing the glucose value
3. Strimma reads the notification and extracts the glucose value
4. The value is processed, stored, displayed, and optionally pushed to Nightscout

```mermaid
graph LR
    A[CGM Sensor] -->|Bluetooth| B[CGM App]
    B -->|Notification| C[Strimma]
    C -->|Extract BG| D[Process & Store]
```

Strimma automatically detects whether the notification shows glucose in mmol/L or mg/dL and converts as needed. Values outside the range 51–500 mg/dL (2.8–27.8 mmol/L) are ignored.

---

## Supported Apps

Strimma recognizes notifications from CGM app variants across all major manufacturers:

- **Dexcom** — G6, G7, ONE, D1+, Stelo (all regional variants)
- **Abbott** — Libre 2 / LibreLink
- **CamAPS FX** — all variants (mmol/L and mg/dL)
- **Medtronic** — Guardian Connect, MiniMed Mobile, Simplera
- **Eversense** — all versions including Gen12 and 365
- **Third-party** — xDrip+, Juggluco, Diabox
- **Others** — Aidex, Sinocare, Suswel, GlucoTech, OttAI

See [Supported CGM Apps](supported-apps.md) for the complete list.

---

## Direction Computation

Strimma computes the glucose direction (trend arrow) **locally** rather than trusting what your CGM app reports. This ensures consistent, clinically-validated trend information regardless of which CGM app you use.

The direction uses EASD/ISPAD standard thresholds, computed from the change since the reading about 5 minutes earlier. See [Direction Arrows](../reference/direction.md) for details.

---

## Deduplication

CGM apps often post their notification several times for the same reading. Strimma ignores a repeat of the same value when it comes within the same sensor interval as the reading it repeats (for example within the same 5 minutes for a Dexcom), or within 30 seconds of it.

---

## Reading Time

Strimma uses the timestamp your CGM app puts on its notification as the reading time. Some apps, such as CamAPS FX, update that timestamp every time they post the notification again, also when there is no new reading. When such a repost comes later than the deduplication above allows, Strimma counts it as a new reading with the same value. The reading's age and the stale-data alert then count from that later post.

---

## Does It Interfere with My CGM App?

**No.** Strimma only reads notifications — it doesn't modify them, intercept them, or affect your CGM app in any way. Your CGM app continues to work normally, including closed-loop systems like CamAPS FX or AndroidAPS.

---

## Troubleshooting

!!! question "Strimma doesn't see my CGM app's notifications"
    1. Check that notification access is granted: **Android Settings > Special app access > Notification access > Strimma**
    2. Check that your CGM app is posting notifications (some apps let you disable them)
    3. Check the debug log (**Settings > Debug Log**) for messages about received or rejected notifications
    4. If your CGM app isn't in the [Supported Apps](supported-apps.md) list, please [open an issue](https://github.com/psjostrom/strimma/issues)

!!! question "Values look wrong"
    Check the debug log for the raw text Strimma extracted from the notification. If you find a parsing issue, [open an issue](https://github.com/psjostrom/strimma/issues) with the debug log snippet.
