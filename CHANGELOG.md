## [1.4.0-rc.1] - 2026-07-25

### Added
- Introduced a new "Alert Cooldown" setting, allowing users to configure the minimum time interval (0–60 minutes) before the same alert type can re-trigger. (#245)

### Fixed
- Resolved an issue where the app could incorrectly handle navigation back actions in certain scenarios. (#247)

### Changed
- Improved the user experience for selecting alert cooldown intervals by providing predefined options (0, 5, 10, 15 minutes) in the settings screen. (#245)

## [v1.3.1-rc.1] - 2026-06-14

### Internal
- Updated Android build dependencies, including Kotlin 2.4, Compose BOM 2026.05.01, KSP 2.3.9, and AndroidX Core 1.19.
- Raised compile SDK to Android 17 while keeping target SDK on Android 16 until Android 17 runtime behavior is audited.
