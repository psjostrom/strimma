## [1.4.0-rc.1] - 2026-07-25

### Added
- Added alert cooldown/re-alert interval setting (#245)
- Added release workflow and script (#248)
- Added local workflow and agent configuration (#242)

### Fixed
- Fixed test to await canGoBack settling in StoryViewModelTest (#247)

### Changed
- Bumped minor-and-patch dependencies across one directory with 24 updates (#243)
- Updated actions/setup-python from version 6 to 7 (#244)
- Updated actions/checkout from version 6 to 7 (#239)

### Internal
- Validated Kotlin 2.4 dependency bumps (#237)

## [v1.3.1-rc.1] - 2026-06-14

### Internal
- Updated Android build dependencies, including Kotlin 2.4, Compose BOM 2026.05.01, KSP 2.3.9, and AndroidX Core 1.19.
- Raised compile SDK to Android 17 while keeping target SDK on Android 16 until Android 17 runtime behavior is audited.
