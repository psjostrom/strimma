## [1.4.0-rc.1] - 2026-07-25

### Added
- add alert cooldown/re-alert interval setting (#245) (`2078f4a`)

### Fixed
- correct release tag range and improve changelog generation (#250) (`a38af2f`)

### Changed
- remove AI from release workflow (#260) (`804dc65`)

### Internal
- Add local workflow and agent configuration (#242) (`079eb84`)

## [v1.3.1-rc.1] - 2026-06-14

### Internal
- Updated Android build dependencies, including Kotlin 2.4, Compose BOM 2026.05.01, KSP 2.3.9, and AndroidX Core 1.19.
- Raised compile SDK to Android 17 while keeping target SDK on Android 16 until Android 17 runtime behavior is audited.
