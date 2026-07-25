## [1.4.0-rc.1] - 2026-07-25

### Added
- Introduced a new setting for alert cooldown and re-alert intervals to enhance user control over notifications (#245).

### Fixed
- Resolved an issue with the release tag range, improving the accuracy of changelog generation (#250).
- Fixed a test issue in the StoryViewModel that ensures the `canGoBack` function settles correctly (#247).

## [v1.3.1-rc.1] - 2026-06-14

### Internal
- Updated Android build dependencies, including Kotlin 2.4, Compose BOM 2026.05.01, KSP 2.3.9, and AndroidX Core 1.19.
- Raised compile SDK to Android 17 while keeping target SDK on Android 16 until Android 17 runtime behavior is audited.
