## [v1.3.1-rc.2] - 2026-07-24

### Added
- Re-alert interval (cooldown) setting for glucose alerts — configurable per-alarm (Urgent Low, Low, High, Urgent High) with 5/10/15-minute options or Default (no cooldown). Supports 5 languages (en, de, es, fr, sv). Cooldown resets when glucose returns to range; predictive alerts (Low Soon/High Soon) excluded by design. (#245)

## [v1.3.1-rc.1] - 2026-06-14

### Internal
- Updated Android build dependencies, including Kotlin 2.4, Compose BOM 2026.05.01, KSP 2.3.9, and AndroidX Core 1.19.
- Raised compile SDK to Android 17 while keeping target SDK on Android 16 until Android 17 runtime behavior is audited.
