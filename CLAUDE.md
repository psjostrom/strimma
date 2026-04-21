# Strimma

Android CGM companion app. Receives glucose from dozens of CGM apps (CamAPS FX, Dexcom G6/G7, LibreLink, Libre 3, Juggluco, xDrip+, etc.) via NotificationListenerService, displays with graph + notification, pushes to / follows Nightscout.

## Build

```bash
./gradlew assembleDebug        # build
./gradlew installDebug         # build + install on connected device(s)
adb connect 192.168.1.10:<port> # wireless debug (port changes on reconnect)
```

Run tests with `./gradlew testDebugUnitTest`.

## Testing

Integration over isolation. Tests wire real collaborators — mock only at the system boundary.

- **Use real Room** (in-memory via `Room.inMemoryDatabaseBuilder`). Never mock a DAO.
- **Use real SettingsRepository** (backed by DataStore + SharedPreferences via Robolectric). Never mock settings.
- **Use real domain classes** (DirectionComputer, IOBComputer, MealAnalyzer, etc.). Never mock a collaborator that's pure computation.
- **Mock only the network boundary.** Hand-written test doubles (e.g. `FakeNightscoutClient`) or Ktor `MockEngine` for HTTP. No mock frameworks (Mockito, MockK) — if you need one, the design is wrong.
- **Test behavior, not implementation.** Assert on observable outcomes: DB state after a pipeline run, notifications fired, JSON shape returned, Flow emissions. Never assert on method call counts or internal state.
- **Pure-logic classes need no integration.** If a class has no collaborators (just data in → result out), test it directly with synthetic data. That's not isolation — it's just a function with nothing to integrate.
- **Robolectric for Android services.** NotificationManager, SharedPreferences, Context — use Robolectric's real implementations, not mocks.
- **Each test is self-contained.** Arrange-act-assert in one function. No shared mutable state via `@Before`. Setup helpers that return what you need are fine.

## Architecture

```
CGM Sensor → CGM App → notification → GlucoseNotificationListener (COMPANION)
                                    ↓
xDrip+/Juggluco/AAPS → broadcast → XdripBroadcastReceiver (XDRIP_BROADCAST)
                                    ↓
Remote Nightscout → polling → NightscoutFollower (NIGHTSCOUT_FOLLOWER)
                                    ↓
LibreLinkUp (Abbott API) → polling → LibreLinkUpFollower (LIBRELINKUP)
                                    ↓
  → StrimmaService → Room DB → NightscoutPusher → Nightscout /api/v1/entries
                   → NotificationHelper (graph bitmap)
                   → AlertManager (urgent low/low/high/urgent high/stale)
                   → BG Broadcast (xDrip-compatible intent, optional)
```

Single-module app. Hilt DI. All async via Coroutines/Flow.

## Project Structure

- `data/` — Room entities, DAO, settings, direction computation, unit conversion, data source selection, statistics
- `data/meal/` — Per-meal postprandial analysis: MealAnalyzer, MealStatsCalculator, MealAgpCalculator, time slot config, carb size bucketing
- `graph/` — Shared graph logic (colors, Y-range computation, critical thresholds) + weighted least-squares prediction with endpoint anchoring
- `network/` — Nightscout HTTP client, push logic (Ktor, `/api/v1/entries`), and follower mode (polling)
- `notification/` — Foreground notification (collapsed/expanded with graph bitmap), alert manager
- `receiver/` — Data source receivers (notification parser for CGM apps, xDrip broadcast receiver), debug logging
- `service/` — Foreground service, boot receiver
- `ui/` — Compose screens (Main, Settings, Stats, Debug), ViewModel, theme
- `ui/theme/` — Dark + light palettes, status colors, Material 3 theme with `ThemeMode` (Dark/Light/System)

## Nightscout Compliance

Strimma is a Nightscout-compatible client. All HTTP endpoints, URL formats, query parameters, and data shapes MUST follow the Nightscout API spec. This is non-negotiable — Strimma must work with any real Nightscout server, not just Springa.

- **GET endpoints use `.json` suffix:** `/api/v1/entries.json`, `/api/v1/treatments.json`. POST endpoints do not use `.json`. This is the Nightscout convention.
- **Query params use Nightscout's MongoDB-style syntax:** `find[date][$gt]=`, `find[created_at][$gte]=`, `count=`.
- **Auth via `api-secret` header** with SHA-1 hashed secret.
- **Data shapes match Nightscout spec:** `sgv`, `date`, `dateString`, `direction`, `type` for entries; `_id`, `eventType`, `created_at`, `insulin`, `carbs`, `absolute`, `duration`, `enteredBy` for treatments.
- **If Springa (or any server) can't handle standard Nightscout URLs, fix the server.** Never weaken Strimma's NS compliance to work around a server bug.

## Conventions

- **Colors:** InRange=cyan (#56CCF2), AboveHigh=amber (#FFB800), BelowLow=coral (#FF4D6A). Defined in `ui/theme/Color.kt` (Compose, prefixed `Dark`/`Light`) and `graph/GraphColors.kt` (Canvas int constants, prefixed `CANVAS_`). Keep in sync.
- **Theme:** Dark/Light/System via `ThemeMode` enum. Graphs follow the active theme.
- **Direction:** Computed locally via 3-point averaged SGV + EASD/ISPAD thresholds. Never trust CGM app broadcast direction values.
- **Units:** Configurable mmol/L or mg/dL via `GlucoseUnit` enum. Store internally as mg/dL (Room DB), convert at display time. Conversion factor: `18.0182`.
- **Graph rendering:** Two renderers sharing logic — Compose Canvas (MainScreen) and Android Canvas (GraphRenderer for notifications). Both use `computeYRange()` from `graph/GraphColors.kt`.
- **Notification graph:** 1-hour window. Main graph: configurable (1-8h, default 4h).
- **Nightscout push:** Immediate on new reading via `/api/v1/entries`. Retry with linear backoff. Unpushed readings marked `pushed=0` in Room.

## Engineering Mindset

NEVER treat Strimma as a "single-user app" or use that as justification to cut corners. This is a crucial, life-saving medical app designed for millions of users. Every decision — testing, architecture, reliability, CI — must reflect that.

## Key Domain Details

- minSdk 33 (Android 13) — oldest version still receiving security updates
- Target device: Pixel 9 Pro, Android 16 (API 36)
- Single user, single sensor (Libre 3)
- Medical data — reliability is non-negotiable
- xDrip+ retired — Strimma is the sole data source (validated equivalent coverage and accuracy)
- `foregroundServiceType="specialUse"` (not connectedDevice — no direct BLE)

## Documentation Updates (HARD GATE)

Any user-visible change is not done until docs are checked. Before declaring work complete:

1. **Grep `docs/` for references** to the changed feature (screen name, setting name, behavior). Update any doc pages that describe changed behavior.
2. **If no docs exist** for a user-visible feature, write them. Match the structure and style of existing `docs/guide/` pages.
3. **Grep affected doc pages for `screenshots/`** references. Flag any screenshot that may be stale: "Screenshot `X.png` may be stale — [reason]."

Skip only for pure refactors with zero user-visible change.

## Git Workflow

When a merge or push fails, ALWAYS check CI status (`gh pr checks`) and read the failure logs before retrying. Never blindly force or retry — diagnose the failure first.

## Releasing

1. Run `git log <last-tag>..main --oneline` to see ALL changes since the last release. Never assume the latest commit is all that changed.
2. Create branch `release/vX.Y.Z` from main.
3. Bump `versionName` in `app/build.gradle.kts`. Never bump `versionCode` (not on Google Play).
4. Check docs for staleness against the changes (see Documentation Updates above).
5. Create PR. Write release notes in the PR body inside a ` ```markdown ` fenced block — CI extracts this block and uses it as the GitHub Release body. If no fenced block is found, CI falls back to auto-generated notes from PR titles.
6. Merge PR, then tag: `git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z`. CI builds the APK and creates the GitHub Release.
7. Always create releases by pushing a version tag. Never use `gh release create` manually.

## Spec

Full spec in `docs/internal/spec.md`. Ideas in `docs/internal/ideas.md`. CGM app landscape in `docs/internal/cgm-landscape.md`.
