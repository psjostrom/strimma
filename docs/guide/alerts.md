# Alerts

Strimma can alert you with sound and vibration when your glucose crosses configurable thresholds.

---

## Alert Types

Strimma has **seven** alert types, each with its own Android notification channel so you can customize the sound and vibration for each one independently.

### Glucose Threshold Alerts

| Alert | Default Threshold | Bypasses Do Not Disturb | Vibration |
|-------|-------------------|------------------------|-----------|
| **Urgent Low** | 3.0 mmol/L (54 mg/dL) | Yes | Strong, repeated |
| **Low** | 4.0 mmol/L (72 mg/dL) | No | Medium |
| **High** | 10.0 mmol/L (180 mg/dL) | No | Short |
| **Urgent High** | 13.0 mmol/L (234 mg/dL) | Yes | Strong, repeated |

### Predictive Alerts

| Alert | Trigger | Bypasses DND | Vibration |
|-------|---------|-------------|-----------|
| **Low Soon** | Predicted to cross low threshold within prediction window | No | Gentle |
| **High Soon** | Predicted to cross high threshold within prediction window | No | Gentle |

### Data Freshness Alert

| Alert | Trigger | Bypasses DND | Vibration |
|-------|---------|-------------|-----------|
| **Stale Data** | No reading received for 10+ minutes | No | Gentle |

---

## Priority Logic

Alerts follow a priority system to avoid duplicate noise:

- **Urgent Low takes priority over Low.** If your glucose triggers both, only Urgent Low fires. When it rises above the urgent threshold, Urgent Low clears and Low can fire if still below the low threshold.
- **Urgent High takes priority over High.** Same logic — only the more severe alert fires.
- **Predictive alerts only fire when in range.** If you're already low, "Low Soon" won't fire — you already know.

---

## Snooze

Each alert has a **30-minute snooze**. When an alert fires, the notification includes a **Snooze** button. Tapping it silences that specific alert for 30 minutes.

- Snooze applies to one alert type — snoozing Low doesn't affect Urgent Low
- After 30 minutes, the alert can fire again if the condition persists
- Snooze state is stored locally and survives app restarts

---

## Configuring Alerts

Go to **Settings > Alerts** to configure each alert:

### Enable/Disable

Each alert type has an independent toggle. Disabled alerts never fire, regardless of your glucose level.

### Set Thresholds

For the four glucose threshold alerts (Urgent Low, Low, High, Urgent High), you can set the threshold value. Enter the value in your configured unit (mmol/L or mg/dL).

### Customize Sound

Each alert type has a **Sound** button that opens the Android notification channel settings for that alert. From there you can:

- Choose a different notification sound or alarm tone
- Enable/disable vibration
- Change the vibration pattern
- Override Do Not Disturb settings (for urgent alerts)

!!! tip "Use distinct sounds"
    Pick clearly different sounds for Low vs High alerts so you know which one it is without looking at your phone. Many users set the urgent alerts to a loud alarm tone and the regular alerts to a gentler notification sound.

---

## Do Not Disturb Bypass

**Urgent Low** and **Urgent High** alerts bypass Do Not Disturb mode by default. This is critical for safety — a severe low at 3 AM needs to wake you up.

The other alerts (Low, High, Low Soon, High Soon, Stale Data) respect Do Not Disturb by default. However, you can change **any** alert to bypass DND:

1. Go to **Settings > Alerts**
2. Tap the **Sound** button next to the alert you want to change
3. In the Android notification channel settings, enable **Override Do Not Disturb**

This is an Android feature — once you change a channel's DND setting, Android remembers it.

---

## How Alerts Are Triggered

1. Strimma receives a new glucose reading
2. The reading is checked against each enabled alert's threshold
3. If a threshold is crossed and the alert isn't snoozed, the alert fires
4. Stale data is checked every 60 seconds independently

### Re-alerting

Alerts **keep firing** as long as the condition persists. Each new glucose reading triggers a check — if you're still low and the alert isn't snoozed, it fires again. This means you'll get alerted on every reading until you either:

- **Snooze** the alert (silences it for 30 minutes), or
- **Return to range** (the condition clears)

If you snooze an alert and you're still out of range when the snooze expires, the alert fires again on the next reading.

This is intentional — a persistent low or high should not be silently ignored.
