```markdown
## [1.4.0-rc.3] - 2026-07-25

### Added
- Introduced a new setting to configure alert cooldown and re-alert intervals, giving users more control over notification timing. (#245)

### Fixed
- Resolved an issue where the app could incorrectly determine navigation history, improving the reliability of the back button functionality. (#247)

### Changed
- Enhanced the changelog generation process to ensure more accurate release notes. (#250)
```

## [v1.3.1-rc.1] - 2026-06-14

### Internal
- Updated Android build dependencies, including Kotlin 2.4, Compose BOM 2026.05.01, KSP 2.3.9, and AndroidX Core 1.19.
- Raised compile SDK to Android 17 while keeping target SDK on Android 16 until Android 17 runtime behavior is audited.
