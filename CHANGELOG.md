# Changelog

All notable changes to Strimma are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/)

## [v1.0.0] - 2026-04-10

First public release.

### Added
- Medical disclaimer on setup wizard welcome step
- Tests for StatsCalculator, NightscoutPuller, NightscoutFollower, TreatmentSyncer (#169)
- NightscoutPusher tests (#164)
- AlertManager core alert logic tests (#162)
- Loading spinners for async operations (#170)
- Integration connection status in settings (#159)
- In-app auto-update via GitHub Releases (#150)

### Changed
- Extract hardcoded strings to resources (#170)
- Tidepool switched to production environment, fix OIDC scopes (#171)
- Remove specific CGM app counts from docs and strings — use generic language instead

### Fixed
- Exercise overview card: "Lowest after" changed to "Lowest during" (#174)
- Meal cards sort order: show newest first (#173)
- Stale docs, safe null handling, dead code cleanup (#172)
- 5 bugs found during readiness audit (#168)
- Dual-storage atomicity in SettingsRepository (#161)
- Force update deserialization with text/plain content type (#157)
- Setup wizard spec aligned with implementation (#163)

### Removed
- AOD/lock screen live wallpaper — rejected after testing (#167)

## [v0.10.1] - 2026-04-04

### Added
- Activity/Full toggle and axis labels on exercise BG graph (#155)

### Fixed
- Force update dialog never triggered because GitHub serves `update.json` as `text/plain` (#157)

### Internal
- Removed 4 unnecessary lint/detekt suppressions (#156)
- Added CHANGELOG.md (#154)

## [v0.10.0] - 2026-04-03

### Added
- Bottom navigation bar (#139)
- In-app auto-update via GitHub Releases (#150)

### Changed
- Redesigned exercise detail screen with bottom sheets and IOB breakdown (#141)
- Pre-activity card timing and compact layout improvements (#138)
- Renamed BgStatus.LOW to DANGER for clarity (#142)

### Fixed
- Alert snooze bypassed when BG level transitions between zones (#146)
- Tidepool dataset creation 500 error (#149)
- Tidepool integration auth and settings (#143)

### Internal
- Fixed all 18 baselined lint issues and removed both baselines (#144)
- Added docs-update enforcement rule and hook (#145)
- Updated screenshots and docs (#148, #151, #140)

## [v0.9.1] - 2026-04-01

### Added
- Tidepool upload integration via OIDC (#48)
- Reading age shown in notification collapsed view (#134)
- Socket and request timeouts to HTTP clients (#127)

### Fixed
- LibreLinkUp follower for current Abbott API (#135)
- CancellationException rethrown in NightscoutPuller and TreatmentSyncer (#130)
- Treatment syncSince exception handling to keep poll loop alive (#129)
- Corrupted EncryptedSharedPreferences on keystore failure (#128)
- NightscoutPusher scope cancelled on service destroy (#131)
- Duplicate check handles backward clock adjustments (#132)
- Widget update errors logged instead of silently swallowed (#133)
- Notification rebuild skipped when nothing changed (#125)

### Internal
- Extract AlertsViewModel — centralize alert state (#123)
- Extract computeCurrentIOB() for resolved settings values (#124)
- Eliminate duplicate dao.since() query in processReading (#121)
- Centralize time constants into TimeConstants object (#119)
- Type-safe themeMode via ThemeMode enum (#120)
- Deduplicate constants: MAX_ERROR_LENGTH, thresholds, CRITICAL_LOW, valid SGV range, stale threshold, SHA-1 hash, ISO formatter, MGDL_FACTOR (#108-#118)
- Reduce treatment sync to 24h window on regular polls (#126)
- Remove duplicate LanguagePicker from GeneralSettings (#122)

## [v0.9.0] - 2026-03-28

### Added
- Per-meal postprandial analysis: TIR, peak excursion, time-to-peak, recovery, iAUC, IOB at meal, sparkline graphs, AGP-style aggregate profile (#93)
- Pause alerts by category (Low or High) for custom duration (#91)

### Fixed
- Exercise screens using dark-only tint colors in light mode (#95)
- Debug screen OOM crash (#92)
- Documentation accuracy across 13 files (#94)

## [v0.8.0] - 2026-03-27

### Added
- Exercise stats with unified categories and metabolic profiles (Aerobic, Resistance, High-Intensity) (#88)
- Planned workout detail sheet and swipeable tabs (#87)
- Actionable suggestions to pre-activity guidance (#86)

### Fixed
- Notification readability on light theme (#89)

## [v0.7.1] - 2026-03-26

### Added
- Calendar sync helpers and pull-to-refresh on Planned tab (#84)
- Planned/Completed tabs to Exercise screen (#83)

## [v0.7.0] - 2026-03-25

### Added
- LibreLinkUp data source for Libre 3 users (#70)
- First-time setup wizard with guided configuration (#71)
- Pre-activity guidance with calendar integration (#77)
- Alert when glucose uploads fail for 15+ minutes (#75)

### Fixed
- Non-exhaustive when in SetupScreen (#73)

### Internal
- LibreLinkUp added to setup wizard (#74)
- Named release APK with version (#69)
- Lean CI: single Gradle invocation, on-demand coverage (#68)

## [v0.6.0] - 2026-03-24

### Added
- Health Connect exercise integration: exercise bands on graph, per-session BG analysis (#66)
- AGP (Ambulatory Glucose Profile) tab in Statistics (#65)

### Fixed
- Nightscout secret field inconsistency and save-on-focus-loss bug (#64)
- Release notes including changes from previous releases (#62)

## [v0.5.1] - 2026-03-22

### Added
- In-app language picker (#55)
- General settings section, start-on-boot toggle, debug build variant (#56)
- Battery optimization status display (#59)

### Fixed
- Prediction pill using dark tint background in light mode (#54)
- CamAPS-specific language generalized (#58)

### Internal
- Reorganized docs and added feature wishlist (#57)

## [v0.5.0] - 2026-03-21

### Added
- Internationalization: English, Swedish, Spanish, French, German (#52)
- HbA1c unit setting (#46)

### Internal
- Upgrade to AGP 9, Kotlin 2.3, all dependencies (#45)
- Bump Gradle wrapper 8.12 to 9.4.1, Compose BOM, security-crypto (#25-#27)

## [v0.4.1] - 2026-03-19

### Added
- Documentation site (strimma.org) (#40)

### Changed
- Internal canonical unit switched from mmol/L to mg/dL (#39)

### Fixed
- Notification delta truncation and text overflow (#38, #41)
- Graph popover missing negative sign on delta (#42)
- Nightscout URL normalization and .json suffix rule (#41)
- Stale BG cleared from notification (#44)
- Delta included in Nightscout push payload (#43)

## [v0.4.0] - 2026-03-18

### Added
- Local web server — Nightscout-compatible HTTP on port 17580 (#30)
- IOB breakdown dialog with bolus label precision (#35)
- CI workflow with Dependabot (#18)
- Pre-commit hooks, JaCoCo coverage, Compose UI tests (#36)
- Static analysis (Detekt) and leak detection (LeakCanary) (#16)

### Changed
- Settings reorganized into navigable sub-screens (#19)

### Fixed
- Graph gesture handling: scrub, pan, pinch-to-zoom (#34)

### Internal
- All 261 Detekt violations resolved (#29, #31)
- Widget settings extracted into WidgetSettingsRepository (#32)

## [v0.3.0] - 2026-03-17

### Added
- Nightscout history pull (#15)
- Nightscout treatment sync with IOB display (#14)

### Fixed
- Alert snooze bypassed by BG bounce (#9)

## [v0.2.1] - 2026-03-16

### Fixed
- Import file picker not allowing JSON selection (#13)

## [v0.2.0] - 2026-03-15

### Added
- Nightscout follower mode
- Prediction with "Low in X min" / "High in X min" warnings
- Predictive low-soon / high-soon alerts (#5)
- Settings export/import (#11)
- GitHub repo hardening: security policy, issue/PR templates, contributing docs (#6)
- Follower URL, secret, and poll interval settings

### Changed
- Modernized main screen: clean hero layout, card borders, prediction pill
- Unified design system aligned with Springa (#7)
- Prediction warning moved from graph to notification text (#8)

### Fixed
- False high alerts during V-recovery from lows (#10)
- Prediction gap and sluggish trend response
- Widget settings not updating immediately

## [v0.1.0] - 2026-03-14

Initial release.

- Companion mode: glucose from CamAPS FX notification
- Direction computation using EASD/ISPAD thresholds
- Nightscout push via `/api/v1/entries` with retry and offline resilience
- Room database with 30-day retention
- Foreground service with persistent notification (collapsed + expanded with graph)
- Status bar BG icon
- Interactive Compose Canvas graph with pinch-zoom, pan, scrub
- Settings: Nightscout URL/secret, graph window, thresholds
- Dark theme
- Home screen widget (Glance)
- Glucose alerts (urgent low, low, high, urgent high, stale)
- Statistics (TIR, GMI, CV%, coverage, CSV export)
- mmol/L and mg/dL support
- xDrip-compatible BG broadcast
- BG notification with graph bitmap
- Auto-start on boot
