# Changelog

All notable changes to Strimma are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/)

## [v0.10.0] - 2026-04-03

### Added
- Bottom navigation bar (#139)
- In-app auto-update via GitHub Releases (#150)

### Changed
- Redesigned exercise detail screen with bottom sheets and IOB breakdown (#141)
- Pre-activity card timing and compact layout improvements (#138)
- Renamed BgStatus.LOW → DANGER for clarity (#142)

### Fixed
- Alert snooze bypassed when BG level transitions between zones (#146)
- Tidepool dataset creation 500 error (#149)
- Tidepool integration auth and settings (#143)
- Pre-activity card timing edge cases (#138)

### Internal
- Fixed all 18 baselined lint issues and removed both baselines (#144)
- Suppressed OldTargetApi lint (AGP 9.1.0 vs Android 17 beta SDK), unpinned CI runner (#153)
- Added docs-update enforcement rule and hook (#145)
- Updated screenshots and docs (#148, #151, #140)
