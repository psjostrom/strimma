# Contributing to Strimma

Strimma is a medical app — reliability is non-negotiable. Every contribution, no matter how small, is held to that standard.

## Quick Start

```bash
git clone https://github.com/psjostrom/strimma.git
cd strimma
git config core.hooksPath .githooks
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

**Requirements:** Java 21 (Zulu recommended), Android SDK 36. No emulator needed for tests — everything runs on JVM via Robolectric.

## Project Structure

Single-module app. ~55 Kotlin source files.

```
app/src/main/java/com/psjostrom/strimma/
  data/          Room entities, DAO, settings, direction, units, IOB, stats
  graph/         Shared graph constants, colors, Y-range, prediction
  network/       Nightscout HTTP (push, pull, follow), treatment sync
  notification/  Foreground notification (graph bitmap), alerts
  receiver/      Data source receivers (notification parser, xDrip broadcast)
  service/       Foreground service, boot receiver
  ui/            Compose screens, ViewModel, theme
  ui/settings/   Settings subsections (alerts, display, data source, etc.)
  ui/theme/      Colors, theme mode, shapes
  webserver/     Local Nightscout-compatible HTTP server (Ktor)
  widget/        Glance home screen widget
```

```
app/src/test/   Unit + integration tests (JVM, Robolectric)
config/detekt/  Static analysis config
.github/        CI, issue templates, PR template
docs/           Specs and design docs
```

## Stack

| Layer | Library |
|-------|---------|
| UI | Jetpack Compose + Material 3 |
| Database | Room |
| DI | Hilt |
| HTTP client | Ktor Client (CIO) |
| HTTP server | Ktor Server (CIO) |
| Async | Kotlin Coroutines + Flow |
| Settings | DataStore + EncryptedSharedPreferences |
| Widget | Glance |
| Tests | JUnit 4 + Robolectric + Ktor test host |
| Static analysis | Detekt + Android Lint |

## Architecture

### Data flow

```
CGM sensor
  -> CGM app (notification or broadcast)
    -> Strimma receiver (GlucoseNotificationListener or XdripBroadcastReceiver)
      -> StrimmaService (foreground service)
        -> Room DB (GlucoseReading)
        -> DirectionComputer (EASD/ISPAD thresholds)
        -> NotificationHelper (graph bitmap + BG display)
        -> AlertManager (5 alert types)
        -> NightscoutPusher (POST /api/v1/entries)
        -> Widget update
        -> BG broadcast (optional, xDrip format)
```

### Key conventions

- **Units:** Internal storage is mg/dL (industry standard, matching Nightscout). `GlucoseUnit` handles display-time conversion to mmol/L when the user selects that preference.
- **Direction:** Computed locally by `DirectionComputer` using 3-point averaged SGV and EASD/ISPAD thresholds. Never trust direction from upstream apps.
- **Colors:** Status colors are fixed across themes — InRange=cyan, AboveHigh=amber, BelowLow=coral. Defined in `ui/theme/Color.kt` (Compose) and `graph/GraphColors.kt` (Canvas). Keep them in sync.
- **Nightscout compliance:** All endpoints, query params, and data shapes must follow the Nightscout API spec. Strimma must work with any real Nightscout server.

## Adding a Data Source

Data sources are the most natural contribution point. Each source is a receiver class + an enum value.

### 1. Add the enum value

In `data/GlucoseSource.kt`:

```kotlin
enum class GlucoseSource(val label: String, val description: String) {
    COMPANION("Companion Mode", "Parse notifications from CGM apps"),
    XDRIP_BROADCAST("xDrip Broadcast", "Receive xDrip-compatible BG broadcasts"),
    NIGHTSCOUT_FOLLOWER("Nightscout Follower", "Follow a remote Nightscout server"),
    YOUR_SOURCE("Your Source", "Description of what it does")
}
```

### 2. Create the receiver

In `receiver/`, create a class that receives data and forwards it to `StrimmaService`:

```kotlin
class YourReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Extract glucose value, convert to mg/dL
        // Validate range (18-900 mg/dL)
        // Forward to StrimmaService via startForegroundService()
    }
}
```

Use `XdripBroadcastReceiver` as a reference — it's the simplest complete example (~50 lines).

### 3. Wire it up in StrimmaService

Register/unregister your receiver based on the active `GlucoseSource` setting.

### 4. Write tests

Every data source needs tests covering: valid data, invalid data, out-of-range values, and edge cases. See `GlucoseParserTest.kt` for the pattern.

## Quality Gates

### Pre-commit (local)

The pre-commit hook runs Detekt automatically. Set it up once:

```bash
git config core.hooksPath .githooks
```

### CI (on every PR)

GitHub Actions runs all of these — your PR won't merge if any fail:

| Step | Command |
|------|---------|
| Tests | `./gradlew testDebugUnitTest` |
| Lint | `./gradlew lintDebug` |
| Detekt | `./gradlew detekt` |
| Coverage | `./gradlew jacocoTestReport` |
| Build | `./gradlew assembleDebug` |

### Run them locally before pushing

```bash
./gradlew testDebugUnitTest lintDebug detekt assembleDebug
```

## Detekt Rules

Detekt is configured in `config/detekt/detekt.yml`. Key settings:

- Max line length: 140
- `@Composable` functions are exempt from `LongMethod`, `CyclomaticComplexMethod`, `LongParameterList`, `MagicNumber`, and `FunctionNaming`
- Wildcard imports allowed for Compose, Ktor, coroutines, and test assertions
- Magic numbers flagged except in property declarations, companion objects, and enums

Do not disable Detekt rules to make code pass. If a rule flags your code, fix the code.

## Testing

Tests run on JVM via Robolectric — no emulator needed.

```bash
./gradlew testDebugUnitTest
```

### What to test

- **Data processing:** Parsing, conversion, computation (unit tests)
- **Database operations:** DAO queries with in-memory Room (integration tests)
- **HTTP endpoints:** Ktor test host for web server routes (integration tests)
- **UI components:** Compose test rules for settings screens (UI tests)

### Test style

- Flat tests, no deep nesting. Each test reads top-to-bottom.
- Test behavior, not implementation. If a refactor breaks tests, the tests were wrong.
- Prefer duplication over shared setup that hides what's being tested.
- Use descriptive test names: `validReading_isInsertedAndQueryable`, not `test1`.

## Pull Request Process

1. Fork the repo and create a feature branch
2. Make your changes with tests
3. Run `./gradlew testDebugUnitTest lintDebug detekt assembleDebug` locally
4. Test on a real device if your change affects UI or notifications
5. Open a PR against `main`

### PR requirements

- **Signed commits** (GPG or SSH)
- **Linear history** (squash or rebase merge only)
- **All CI checks pass**
- **All review conversations resolved**

### PR checklist

The PR template includes a checklist — fill it out honestly:

- [ ] Builds without warnings (`./gradlew assembleDebug`)
- [ ] Tests pass (`./gradlew testDebugUnitTest`)
- [ ] Tested on a real device (if applicable)
- [ ] Commits are signed

## Filing Issues

Use the issue templates:

- **Bug report** — include device, Android version, Strimma version, data source, and steps to reproduce. Attach debug logs if possible (Settings > Debug Log > Share).
- **Feature request** — describe the problem it solves and alternatives you've considered.

## What Makes a Good Contribution

- **Bug fixes with tests.** Found a bug? Write a failing test first, then fix it.
- **New data sources.** Follow the plugin pattern above. One class, one set of tests.
- **Test coverage.** There are untested paths — AlertManager edge cases, NightscoutPusher retry logic, TreatmentSyncer polling. Tests for any of these are welcome.
- **Documentation.** If something confused you during setup, improve this guide.

## What to Avoid

- Drive-by refactors outside the scope of your PR
- Disabling lint or Detekt rules
- Changes to Nightscout data formats or endpoints without discussion
- UI changes without testing on a real device
- Large PRs that mix multiple concerns — keep PRs focused

## License

By contributing, you agree that your contributions will be licensed under the [GNU General Public License v3.0](LICENSE).
