# Strimma — CGM App Landscape

**Updated:** 2026-04-06

Reference material on what exists in the CGM app space, what works well, and what's missing. See `ideas.md` for ideas.

---

## What's Out There

| App                  | Purpose                     | Sensor Support                                    | Open Source         | Distribution    |
| -------------------- | --------------------------- | ------------------------------------------------- | ------------------- | --------------- |
| **xDrip+**           | Full CGM platform           | 15+ CGM types                                     | Yes                 | GitHub APK      |
| **Juggluco**         | CGM display + management    | Libre 1/2/2+/3/3+, Dexcom G7, Sibionics, AccuChek | Yes (public domain) | Google Play     |
| **Diabox**           | CGM display                 | Libre 1/2/2+/3/3+ (via Bubble)                    | No                  | Telegram/GitHub |
| **GlucoDataHandler** | CGM hub + watch bridge      | Any (receives from other apps)                    | Yes                 | Google Play     |
| **Gluroo**           | Collaborative CGM + logging | Dexcom, Libre                                     | No (free)           | App stores      |
| **mySugr**           | CGM + bolus calculator      | Accu-Chek SmartGuide, Dexcom                      | No (Roche)          | App stores      |
| **Nightscout**       | Cloud CGM dashboard         | Any (receives via uploaders)                      | Yes                 | Self-hosted web |
| **CamAPS FX**        | Closed-loop pump control    | Libre 3 (direct BLE)                              | No                  | Prescription    |

## Feature Comparison

| Feature                     | xDrip+                    | Juggluco                    | Diabox               | GDH                           | Strimma                           |
| --------------------------- | ------------------------- | --------------------------- | -------------------- | ----------------------------- | --------------------------------- |
| **Direct BLE CGM**          | Yes (15+ sensors)         | Yes (Libre, Dexcom, etc.)   | Yes (Libre + Bubble) | No                            | No (via notification parsing)     |
| **Glucose alerts**          | Yes (highly configurable) | Yes (low/high + medication) | Yes                  | Yes (5 levels)                | Yes (7 types, per-alarm channels) |
| **Predictive alerts**       | Yes                       | No                          | No                   | No                            | Yes (low soon / high soon)        |
| **Home screen widget**      | Yes                       | Yes + floating overlay      | Via complications    | Yes + floating                | Yes (Glance, with graph)          |
| **Watch support**           | Yes (5 platforms)         | Yes (Wear OS native)        | Via G-Watch          | Yes (Wear OS, MiBand, Garmin) | No                                |
| **Nightscout upload**       | Yes                       | Yes                         | No                   | No                            | Yes                               |
| **Nightscout follower**     | Yes                       | No                          | No                   | No                            | Yes                               |
| **Treatment display (IOB)** | Yes                       | Yes                         | No                   | No                            | Yes (from Nightscout)             |
| **Calibration**             | Yes                       | Yes                         | Yes                  | No                            | No                                |
| **Prediction**              | Yes (multiple algorithms) | No                          | No                   | No                            | Yes (30-min dampened velocity)    |
| **Statistics (TIR, GMI)**   | Yes                       | Yes                         | Limited              | No                            | Yes (TIR, GMI, CV%, coverage)     |
| **GVI/PGS variability**     | Yes                       | No                          | No                   | No                            | No                                |
| **Health Connect**          | No                        | Yes                         | No                   | No                            | Yes (exercise read + glucose write) |
| **Interactive graph**       | Yes (pinch zoom)          | Yes (landscape)             | Basic                | Basic                         | Yes (zoom, pan, scrub, minimap)   |
| **Data broadcast**          | Yes                       | Yes                         | Yes                  | Yes                           | Yes (xDrip format)                |
| **mg/dL support**           | Yes                       | Yes                         | Yes                  | Yes                           | Yes                               |
| **Dark/Light theme**        | Dark only                 | Partial                     | Dark only            | Yes                           | Yes (Dark/Light/System)           |
| **Lock screen display**     | Via notification          | No                          | Yes                  | Yes (AOD)                     | Yes (AOD wallpaper + notification) |
| **Android Auto**            | No                        | No                          | No                   | Yes                           | No                                |

## Interesting Features Elsewhere

| Feature                        | Who                                  | What It Does                                                       |
| ------------------------------ | ------------------------------------ | ------------------------------------------------------------------ |
| **Meal photo AI**              | Abbott Libre Assist, SNAQ, DiabTrend | Photo → predicted glucose impact. Libre Assist is free (Jan 2026). |
| **Coordinated cascade alerts** | Gluroo                               | Alert PWD first, escalate to caregivers if unaddressed.            |
| **Per-meal postprandial TIR**  | Undermyfork                          | Shows which meals kept you in range vs. which didn't.              |
| **Chat-based logging**         | Gluroo                               | Shorthand like "20m high workout" to log activities.               |
| **IFTTT/Tasker triggers**      | xDrip+                               | BG events trigger smart home actions.                              |
| **8-hour ML prediction**       | One Drop                             | ML trained on 11B data points. Cloud-based.                        |
| **Offline food recognition**   | DiabTrend                            | Camera food ID + portion estimation, works offline.                |
| **Voice readout**              | Juggluco                             | Spoken glucose values.                                             |
| **Floating overlay**           | Juggluco                             | Always-visible BG widget over other apps.                          |
| **Gamification**               | Happy Bob                            | Stars for TIR, avatar personalities.                               |
| **AOD wallpaper**              | GDH                                  | BG on always-on display via wallpaper.                             |
| **Overnight prediction**       | Accu-Chek SmartGuide                 | AI-enabled overnight glucose prediction.                           |

## What People Wish Was Better

1. **Alarm fatigue** — can't customize enough, alarms in public settings
2. **Food logging is painful** — manual entry is tedious, barcode scanning unreliable
3. **No exercise-BG correlation** — no app shows how activity affects glucose well
4. **Subscription fatigue** — paying monthly for basic features
5. **Data portability** — hard to export data from manufacturer ecosystems
6. **Sensor accuracy distrust** — CGM vs. fingerstick differences erode confidence
7. **Wear OS is broken** — OS 5 broke legacy watchfaces, no manufacturer supports it natively
