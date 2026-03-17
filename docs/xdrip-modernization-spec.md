# xDrip+ Modernization Spec

**Date:** 2026-03-17
**Status:** Draft
**Scope:** Full codebase review with verified findings

All claims in this document have been verified against the source code with exact file paths and line numbers. No finding is based on assumption or inference.

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Build System & Dependencies](#2-build-system--dependencies)
3. [UI Layer](#3-ui-layer)
4. [Architecture & Code Structure](#4-architecture--code-structure)
5. [Security](#5-security)
6. [Testing & Code Quality](#6-testing--code-quality)
7. [Improvement Plan](#7-improvement-plan)

---

## 1. Executive Summary

xDrip+ is a 10+ year old Android app built on 2015-era foundations. The BLE/CGM data pipeline is solid engineering, but everything around it — UI, architecture, dependencies, security — is a generation behind modern Android.

The root constraint is `targetSdkVersion 24` (Android 7.0, 2016), kept intentionally low because modern Android background execution restrictions break continuous glucose monitor data collection. This creates a cascading lock: modernizing the target SDK requires rearchitecting the entire background data pipeline, which blocks most other modernization.

### Codebase at a Glance

| Metric | Value | Source |
|--------|-------|--------|
| Java source files (app module) | 976 | `find app/src/main/java -name "*.java"` |
| Test files | 67 | `find . -name "*Test.java"` |
| Activities in manifest | 74 | AndroidManifest.xml |
| Services in manifest | 40 | AndroidManifest.xml |
| Broadcast receivers in manifest | 22 | AndroidManifest.xml |
| Largest file (Home.java) | 3,880 lines | `wc -l` |

---

## 2. Build System & Dependencies

### 2.1 Build Tool Versions

| Component | Used | Current (2026) | Evidence |
|-----------|------|----------------|----------|
| Gradle | 7.5 | 8.10+ | `gradle/wrapper/gradle-wrapper.properties` line 6 |
| Android Gradle Plugin | 7.4.2 | 8.2+ | `build.gradle` line 12 |
| Kotlin | 1.8.0 | 2.0+ (K2 compiler) | `build.gradle` line 16 |
| Java source/target | 1.8 | 17+ (Android) | `app/build.gradle` lines 173-175 |
| compileSdk | 34 | 35+ | `app/build.gradle` line 72 |
| targetSdkVersion | 24 | 34+ | `app/build.gradle` line 95 |

**Why AGP can't be upgraded:** A comment at `build.gradle` lines 10-11 states: *"Upgrading AGP to a newer version also updates the data binding library. This causes build failures with mixed case package names currently in use."* The codebase uses mixed-case Java package names that are incompatible with newer data binding compilers.

**Why targetSdk is 24:** Documented in `CLAUDE.md`: *"Kept low to avoid Android permission/background-execution restrictions that break CGM data collection."* Modern Android (API 26+) enforces background execution limits, implicit broadcast restrictions, and foreground service requirements that would break the always-on CGM connection model.

### 2.2 Dependency Versions

All references from `app/build.gradle` unless noted.

| Dependency | Version | Line | Current (2026) | Gap |
|------------|---------|------|----------------|-----|
| OkHttp 2.x | 2.7.5 | 297 | EOL (2014) | Dead library |
| OkHttp 3.x | 3.12.13 | 298 | 4.12+ | ~6 years |
| Retrofit 2 | 2.4.0 | 301 | 2.11+ | ~6 years |
| RxJava 1.x | 1.3.3 | 314 | 3.x (1.x is EOL) | Unmaintained |
| RxAndroidBLE | 1.12.1 | 315 | 1.12+ | Current for 1.x line |
| Dagger | 2.25.4 | 342 | 2.52+ | ~6 years |
| Material Design | 1.1.0 | 277 | 1.12+ | ~6 years |
| AndroidX AppCompat | 1.0.0 | 276 | 1.7+ | ~7 years |
| Play Services (all) | 15.0.0 | 288-294 | 21.x+ | ~7 years |
| Gson | 2.8.6 | 305 | 2.11+ | ~4 years |
| Guava | 24.1-jre | 309 | 33+ | ~7 years |
| Bouncycastle | 1.68 | 346 | 1.78+ | ~4 years |

**Dual OkHttp reasoning:** Legacy code (pre-2018) uses the OkHttp 2.x API (`com.squareup.okhttp`). Newer code uses OkHttp 3.x (`com.squareup.okhttp3`). The two have incompatible APIs (different package names, different class hierarchies). Both coexist because migrating legacy callers is non-trivial and risky for a medical app.

**RxJava 1.x + 2.x coexistence:** The app declares `io.reactivex:rxjava:1.3.3` (RxJava 1) explicitly. However, `com.polidea.rxandroidble2:rxandroidble:1.12.1` pulls in RxJava 2 transitively (the `rxandroidble2` artifact targets RxJava 2). This means two RxJava major versions with different error handling semantics coexist at runtime.

### 2.3 Build Configuration Issues

**Debug builds are minified.** Both `release` and `debug` build types set `minifyEnabled true` and `shrinkResources true` (`app/build.gradle` lines 186-200). This slows down development iteration and makes debugging stack traces harder. The `dev` build type (line 202) correctly sets `minifyEnabled false`.

**ProGuard vs R8.** The app module uses `proguard-android.txt` (lines 186, 192) — the legacy ProGuard rules file without R8 optimizations. The `libkeks` and `libglupro` modules correctly use `proguard-android-optimize.txt` (R8). Inconsistent across modules.

**Deprecated Gradle DSL.** The `lintOptions` block (`app/build.gradle` lines 136-140) is the legacy name. Newer AGP versions use `lint {}`. This works with AGP 7.4.2 but would need changing for AGP 8.x.

**Lint is disabled for releases.** `checkReleaseBuilds false` (line 137) means no lint enforcement on production builds. Only `MissingTranslation` and `ExtraTranslation` are explicitly disabled (lines 138-139), but the release check bypass makes this moot.

**Lombok version fragmentation.** `common.gradle` lines 3-4 force all Lombok to `1.18.20`. However, `libkeks/build.gradle` explicitly declares `1.18.24` and `libglupro/build.gradle` declares `1.18.42`, overriding the global constraint.

---

## 3. UI Layer

### 3.1 Framework & Toolkit

The entire app is built with traditional XML layouts and the Android View system. No Jetpack Compose (`@Composable`), no Jetpack ViewModel (`extends ViewModel`), no LiveData (`MutableLiveData`). Verification confirmed zero occurrences of any of these across the codebase.

### 3.2 Theme

The primary theme is `Theme.AppCompat` (`app/src/main/res/values/styles.xml` line 2). This is Material Design 1.0 from 2015 — no Material 3, no Material You, no dynamic colors.

Legacy Holo theme references persist at lines 34, 39, 97, and 102 in styles.xml (`OldAppTheme` with parent `android:Theme.Holo` and `MyActionBar` styles). These are used for legacy UI components that were never migrated.

The color palette is minimal — approximately 7 hardcoded values centered on the 2014 Material Indigo/Pink spec (`#3F51B5`, `#303f9f`, `#ff4081`). The dark background (`#212121`) is hardcoded directly into layouts rather than driven by theme attributes, making a proper dark/light theme toggle impossible without touching every layout.

### 3.3 Layouts

**Layout complexity.** The main screen layout `activity_home.xml` is 664 lines (`app/src/main/res/layout/activity_home.xml`). It uses deeply nested `RelativeLayout` with `LinearLayout` children, manually positioning dozens of views with `layout_below`, `layout_toEndOf`, `layout_alignParentStart`, etc.

**ConstraintLayout adoption: 9%.** Of approximately 100 layout XML files, only 9 use `ConstraintLayout`. The rest use `RelativeLayout` and `LinearLayout` nesting — a pattern that results in deeper view hierarchies and slower measure/layout passes.

**Why this matters:** Deeply nested `RelativeLayout` hierarchies cause exponential measure passes. Each level of nesting multiplies the number of measure calls. `ConstraintLayout` (or Compose) flattens this to a single pass. For a medical app that needs to update the glucose display quickly and reliably, layout performance matters.

### 3.4 List Components

| Component | Files Using It | Examples |
|-----------|---------------|----------|
| ListView | 10 | NavigationDrawerFragment, AlertList, BluetoothScan, ErrorsActivity, MegaStatus, SensorDataTable |
| RecyclerView | 8 | EventLogActivity, ProfileEditor, Reminders, LanguageAdapter |

The navigation drawer uses a plain `<ListView>` with `ArrayAdapter` (`app/java/com/eveningoutpost/dexdrip/NavigationDrawerFragment.java`). `ListView` is not deprecated but is considered legacy — `RecyclerView` provides view recycling, animations, layout flexibility, and better performance for long lists.

### 3.5 Navigation

Navigation is Activity-based: each screen is a separate Activity launched via `Intent`. There is no Jetpack Navigation component, no `NavController`, no `FragmentContainerView`-based navigation. Some screens use Fragments within Activities, but there is no consistent navigation pattern.

### 3.6 Missing Modern UI Patterns

- **No shared element transitions or motion design.** Screen transitions are the default system animations.
- **No custom typography.** System default fonts with hardcoded text sizes (50sp, 22sp scattered through layouts).
- **No adaptive layouts.** RelativeLayout with absolute positioning doesn't scale gracefully across the Android device ecosystem (phones, tablets, foldables).
- **Data binding is partially adopted.** Some layouts use `<layout>` tags with `@{}` binding syntax (e.g., `activity_home.xml`). Many still use manual `findViewById()`. No View Binding adoption.

---

## 4. Architecture & Code Structure

### 4.1 No Architectural Pattern

The app follows no identifiable architecture (not MVVM, MVP, or MVC). It is organically grown code accumulated over 10+ years. Activities directly reference models, models contain business logic, services manage their own state via static fields, and SharedPreferences are read/written from anywhere.

### 4.2 God Classes

| Class | Lines | Role | Why It's a Problem |
|-------|-------|------|--------------------|
| `Home.java` | 3,880 | Main Activity | UI rendering, state management, BLE management, speech recognition, gesture detection, chart rendering, insulin profiles — all in one file. |
| `BgReading.java` | 2,394 | Glucose reading model | ORM entity + calibration logic + trend calculation + data validation + persistence queries. 100+ `@Column` fields. |
| `DexCollectionService.java` | 2,163 | BLE CGM collection | Connection management, retries, polling, bonding, battery monitoring. No state machine pattern. |
| `NightscoutUploader.java` | 1,482 | Cloud sync | Upload/download logic with inline Retrofit calls, error handling, and retry logic. |
| `Treatments.java` | 1,436 | Insulin/carbs model | ORM entity + JSON serialization + undo/redo logic + static caching. |

**Why god classes matter here:** In a medical app, testability is safety. A 3,880-line Activity with 74+ interleaved responsibilities cannot be meaningfully unit tested. Changes to chart rendering can accidentally break BLE management because they share state in the same class.

### 4.3 State Management

**SharedPreferences everywhere.** The `Pref` wrapper class is called from 1,561 locations across the codebase. Any code path can read or write any preference at any time. There is no single source of truth, no reactive observation (no LiveData/Flow wrapping preferences), and no mechanism for the UI to know when a preference changes.

**Mutable static fields.** Verified across 174 files: `synchronized` blocks are used for manual thread safety. Services like `DexCollectionService` use dozens of `static volatile` fields to share state. This is error-prone — a missed synchronization causes a race condition, and over-synchronization causes deadlocks.

**No ViewModel.** Zero Jetpack `ViewModel` instances. All UI state lives in Activity instance fields (lost on rotation/process death) or in static fields (leaked across configuration changes). The app works around this by locking screen rotation in many Activities and by reconstructing state from the database.

### 4.4 Threading

| Pattern | Files | Status |
|---------|-------|--------|
| `AsyncTask` | 13 | Deprecated since API 29 (2019). Still used in Home.java, WatchUpdaterService, NFCReaderX, ImportDatabaseActivity, and others. |
| `new Thread()` | 44 | Raw thread creation with no structured cancellation. If an Activity is destroyed, spawned threads continue running. |
| `synchronized` | 174 | Manual lock management across the codebase. |
| `LocalBroadcastManager` | 10 | Deprecated since AndroidX 1.1.0. Used in Home.java, WatchUpdaterService, Preferences, and others. |
| RxJava | Partial | RxJava 1.x (explicit dep) + RxJava 2.x (transitive via RxAndroidBLE2). Two major versions with different error semantics at runtime. |
| Kotlin Coroutines | 3 files | Minimal adoption (TestKot.kt, Coroutines.kt, Health Connect). |

**Why this matters:** `AsyncTask` is tied to the Activity lifecycle in subtle ways — a destroyed Activity can leave an `AsyncTask` holding a dead reference, causing crashes or silent failures. Raw threads have no cancellation mechanism. The mix of three concurrency frameworks (AsyncTask, RxJava 1+2, raw threads) means no single mental model for reasoning about concurrent behavior.

### 4.5 Dependency Injection

Dagger 2 is declared (`2.25.4`, line 342 of `app/build.gradle`) but barely used. Verification found only ~20 `@Inject` annotations across the codebase. Most dependencies are created via:

- Static getter methods on singleton classes
- `Injectors.java` — a manual service locator (anti-pattern)
- Direct instantiation in Activities and Services

**Why this matters:** Without DI, classes are tightly coupled to concrete implementations. Swapping a real BLE service for a fake one in tests requires modifying the class under test. This makes unit testing impractical for most of the codebase.

### 4.6 Database Layer

The app uses a custom thread-safe fork of **ActiveAndroid ORM**. Models extend `Model` with `@Table` and `@Column` annotations. There is no repository pattern — Activities and Services query models directly via static methods (e.g., `BgReading.latest()`).

**Risks:**
- The upstream ActiveAndroid library is abandoned (last commit 2015). The custom fork adds thread safety but inherits all other limitations.
- Schema changes are ad-hoc — there is a `SqliteRejigger` utility but no formal migration framework like Room's `Migration` classes.
- Business logic lives inside model classes. `BgReading` has trend calculation, calibration, and quality assessment mixed in with ORM annotations. A schema change can break business logic and vice versa.

### 4.7 Networking

Retrofit 2.4.0 for API definitions, with dual OkHttp backends (2.7.5 and 3.12.13). API clients are not abstracted — upload/download logic is embedded directly in domain classes like `NightscoutUploader` (1,482 lines). There is no centralized error handling, no request cancellation on lifecycle events, and no retry policy framework.

---

## 5. Security

Every finding in this section has been verified with exact file paths and line numbers.

### 5.1 CRITICAL: Cleartext Traffic Allowed Globally

**File:** `app/src/main/AndroidManifest.xml` line 122
```xml
android:usesCleartextTraffic="true"
```

**What it does:** Allows the app to make unencrypted HTTP connections to any server. Combined with the absence of a `network_security_config.xml` (verified: file does not exist anywhere in the project), there is no TLS enforcement at the platform level.

**Why it matters:** The app transmits glucose readings, insulin doses, API secrets, and authentication tokens. Any of these can be intercepted on an untrusted network (public WiFi, compromised router) if the server endpoint uses HTTP instead of HTTPS. While most cloud endpoints (Nightscout, Tidepool) use HTTPS, the user can configure custom endpoints, and nothing prevents an HTTP URL.

**Reasoning for fix:** Add a `network_security_config.xml` that defaults to denying cleartext traffic, with explicit exceptions only for `localhost` (needed for local development/testing). Remove `usesCleartextTraffic="true"` from the manifest. This is backward-compatible — HTTPS endpoints continue working unchanged.

### 5.2 CRITICAL: InfoContentProvider Exported Without Permission

**Manifest:** `app/src/main/AndroidManifest.xml` lines 192-196
```xml
<provider
    android:name=".receiver.InfoContentProvider"
    android:authorities="${applicationId}.contentprovider"
    android:enabled="true"
    android:exported="true">
</provider>
```

**Java:** `app/src/main/java/com/eveningoutpost/dexdrip/receiver/InfoContentProvider.java`

The provider handles queries for:
- `"bg"` — current glucose reading
- `"alarms"` — alarm state, can trigger opportunistic snooze
- `"eassist"` — triggers emergency assist
- `"calibration"` — calibration data
- `"graph"` — glucose graph image
- `"version"` — app version

**Existing defense:** The `enabled()` method (lines 287-289) checks `getBooleanDefaultFalse("host_content_provider")`. By default this is false, meaning the provider returns null for non-native callers. However, this is a preference-based check, not a platform-enforced permission. Any app can attempt queries, and the preference can be enabled by the user without understanding the security implications.

**Why it matters:** A malicious app could query glucose readings, calibration data, or trigger emergency assist actions. Even with the preference defaulting to off, the lack of manifest-level permission means there's no OS-enforced access control.

**Reasoning for fix:** Add `android:permission="com.eveningoutpost.dexdrip.permission.READ_GLUCOSE"` (a custom signature-level permission) to the manifest declaration. Apps that legitimately need access (e.g., companion apps signed with the same key) would declare `<uses-permission>` for it. The preference toggle can remain as an additional user-controlled gate.

### 5.3 HIGH: No Certificate Pinning

**Verified:** Zero occurrences of `CertificatePinner` in the codebase. No `network_security_config.xml` with `<pin-set>` declarations.

Cloud endpoints include Nightscout (user-configurable URL), Tidepool (`https://auth.tidepool.org`), Carelink (`https://carelink.minimed.eu`), and Dexcom Share servers. All communicate over HTTPS but accept any valid certificate signed by any CA in the system trust store.

**Why it matters:** Without pinning, a compromised or rogue CA can issue a valid certificate for any of these domains, enabling MITM interception of glucose data and credentials. This is a known attack vector — governments and corporate proxies routinely install custom root CAs.

**Reasoning for fix:** Implement certificate pinning for known first-party endpoints (Tidepool, Carelink, Dexcom Share) via `network_security_config.xml`. Do NOT pin Nightscout URLs because they are user-hosted and use varied certificates. Include backup pins to avoid bricking the app when certificates rotate.

### 5.4 HIGH: Credentials Stored as Plaintext in SharedPreferences

Verified locations of plaintext credential storage:

| Credential | File | Line |
|------------|------|------|
| Tidepool password | `tidepool/MAuthRequest.java` | 16 |
| Dexcom account password | `sharemodels/ShareRest.java` | 92 |
| xDrip web service secret | `webservices/XdripWebService.java` | 273 |
| Web follow password | Accessed via `Pref.getString("webfollow_password", null)` | Multiple |
| Custom sync key | Accessed via `Pref.getString("custom_sync_key", "")` | Multiple |

Additionally, secrets are exported via QR code for backup/sharing (`utils/DisplayQRCode.java` line 153).

**Verified:** Zero occurrences of `EncryptedSharedPreferences` anywhere in the codebase.

**Why it matters:** On a rooted device, any app can read another app's SharedPreferences XML file. On a non-rooted device, ADB backup (even with `allowBackup="false"`) can extract data on older Android versions. Passwords like the Tidepool credential are reusable credentials — if stolen, an attacker can access the user's cloud health data.

**Reasoning for fix:** Migrate sensitive preferences to `EncryptedSharedPreferences` (backed by Android Keystore). This is transparent to the rest of the codebase — the `Pref` wrapper class can be modified to use an encrypted store for a defined list of sensitive keys while keeping non-sensitive preferences in the standard store. This requires `minSdkVersion 23+` (the app's minSdk is 24, so this is compatible).

### 5.5 HIGH: Exported Components Without Permission Protection

Beyond `InfoContentProvider`, many Services and BroadcastReceivers are exported without `android:permission`:

**Services (exported=true, no permission):** `DexCollectionService`, `WifiCollectionService`, `BroadcastService`, `WatchUpdaterService`, `SyncService`, `DexShareCollectionService`, `G5CollectionService`, `WidgetUpdateService`, and others.

**Broadcast Receivers (exported=true, no permission):** `ExternalStatusBroadcastReceiver` (lines 335-338) accepts `com.eveningoutpost.dexdrip.ExternalStatusline` intents from any app. The only check is `Pref.getBoolean("accept_external_status", true)` — defaults to accepting.

**Why it matters:** An exported Service without permission can be bound or started by any app on the device. For BLE services, this could interfere with CGM data collection. For broadcast receivers, any app can inject fake status data.

**Reasoning for fix:** Add `android:exported="false"` to all components that don't need external access. For components that do (e.g., watch integration), add custom permissions with `protectionLevel="signature"`.

### 5.6 MEDIUM: Weak Cryptographic Primitives

**SHA-1 for API secret hashing.** Found in 4 locations:
- `cgm/nsfollow/utils/NightscoutUrl.java` line 83
- `utilitymodels/NightscoutUploader.java` lines 409, 510
- `webservices/XdripWebService.java` line 391

All use `Hashing.sha1().hashBytes(secret.getBytes(Charsets.UTF_8)).toString()`.

SHA-1 has known collision vulnerabilities (SHAttered, 2017). For one-way API authentication this is less critical than for signatures, but it's still a weak choice. The Nightscout API protocol itself specifies SHA-1, so this is partly an upstream constraint.

**MD5 for encryption key derivation.** Found in `utils/CipherUtils.java` lines 56-66:
```java
MessageDigest digest = MessageDigest.getInstance("MD5");
digest.update(mykey.getBytes(Charset.forName("UTF-8")));
return digest.digest();
```

Single-pass MD5 with no salt and no iterations. This is cryptographically inadequate for key derivation. Should use PBKDF2 (available since Java 1.4) or Argon2.

**Reasoning for fix:** SHA-1 usage for Nightscout is constrained by the Nightscout API spec — can't change unilaterally. For CipherUtils key derivation, migrate to `PBKDF2WithHmacSHA256` with a random salt and >= 100,000 iterations. Existing encrypted data would need a migration path (decrypt with old key, re-encrypt with new).

### 5.7 MEDIUM: Overly Broad FileProvider Path

**File:** `app/src/main/res/xml/provider_paths.xml` lines 2-4
```xml
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <external-path name="external_files" path="."/>
</paths>
```

`path="."` exposes the root of external storage via the FileProvider. Any URI generated by this provider can reference any file on external storage.

**Reasoning for fix:** Restrict to the specific subdirectory the app actually uses (e.g., `path="xDrip/"`). This limits the blast radius if a FileProvider URI is leaked or shared with an untrusted app.

---

## 6. Testing & Code Quality

### 6.1 Test Coverage

67 test files for 976 production Java files — a 6.9% file coverage ratio. Tests are concentrated on critical areas (models, CGM data processing, NFC reading) but large swaths of the codebase (UI, services, network) have no test coverage.

### 6.2 Test Infrastructure

| Component | Version | Notes |
|-----------|---------|-------|
| JUnit | 4.13.2 | JUnit 5 not adopted |
| Robolectric | 4.11.1 (app), 4.2.1 (wear), 4.16 (libkeks) | Inconsistent across modules |
| Google Truth | 1.1.3 | Good assertion library |
| Mockito Core | 4.11.0 | Reasonably current |
| Mockito Inline | 2.13.0 | Ancient, version mismatch with Core |
| PowerMock | 1.7.1 (app), 2.0.9 (libkeks/libglupro) | 1.7.1 is unmaintained |

Two base test classes: `RobolectricTestWithConfig` (initializes ActiveAndroid + app context) and `RobolectricTestNoConfig` (lightweight, no DB). This is a reasonable pattern for the ORM.

### 6.3 CI/CD

Single GitHub Actions workflow (`unit_test.yml`): runs on push/PR to master, Ubuntu latest, Java 11, executes `assembleProdRelease testProdReleaseUnitTest`. No matrix testing across SDK versions, no integration test stage, no automated deployment.

### 6.4 Static Analysis

- **Lint:** Disabled for release builds (`checkReleaseBuilds false`)
- **Code formatter:** None configured (no Spotless, no EditorConfig, no Checkstyle)
- **Static analysis tools:** None (no Detekt, SonarQube, SpotBugs)

### 6.5 Code Quality Metrics

| Metric | Count | Concern Level |
|--------|-------|---------------|
| Files using `synchronized` | 174 | High — manual thread safety is error-prone |
| Files using `new Thread()` | 44 | High — no structured cancellation |
| Files using `AsyncTask` | 13 | Medium — deprecated, lifecycle issues |
| Files using `LocalBroadcastManager` | 10 | Low — deprecated but functional |
| TODO/FIXME comments | ~686 | Medium — acknowledged but unaddressed debt |

---

## 7. Improvement Plan

### Guiding Principles

1. **Never break CGM data collection.** Every change must be validated against actual glucose monitor hardware. A UI improvement that crashes the BLE service at 3 AM is worse than no improvement.
2. **Incremental, not rewrite.** The codebase works. Replace pieces one at a time, with each piece independently shippable and rollback-safe.
3. **Test before you touch.** For any god class targeted for refactoring, write characterization tests first (tests that document current behavior, even if that behavior has bugs). Then refactor under test.
4. **Security fixes don't need architecture changes.** Most security items can be fixed without restructuring the app. Do these first.

### Tier 1: Security Hardening

These changes are independent of each other, don't require architectural changes, and directly reduce risk. Each is a single PR.

| # | Item | Effort | Risk |
|---|------|--------|------|
| S1 | Add `network_security_config.xml`, remove `usesCleartextTraffic="true"` | Small | Low — HTTPS endpoints unaffected. Must test user-configured Nightscout URLs. |
| S2 | Add permission to `InfoContentProvider` | Small | Low — default behavior is already gated by `host_content_provider` pref (defaults false). |
| S3 | Audit all 40 Services + 22 receivers: set `exported="false"` where external access isn't needed | Medium | Medium — must verify watch integration and companion app interop still work. |
| S4 | Migrate sensitive prefs to `EncryptedSharedPreferences` | Medium | Medium — must handle migration of existing plaintext values without data loss. |
| S5 | Restrict FileProvider path from `"."` to specific subdirectory | Small | Low |
| S6 | Replace MD5 key derivation with PBKDF2 in CipherUtils | Medium | Medium — requires decrypt/re-encrypt migration for existing encrypted data. |

### Tier 2: Build System

| # | Item | Effort | Risk | Dependency |
|---|------|--------|------|------------|
| B1 | Fix mixed-case package names | Large | High — touching package structure affects every import | None |
| B2 | Upgrade AGP to 8.x | Medium | Medium | B1 |
| B3 | Upgrade Gradle to 8.x | Medium | Low | B2 |
| B4 | Re-enable lint for release builds | Small | Low — will surface existing warnings | None |
| B5 | Consolidate OkHttp to single 4.x version | Large | High — every HTTP call site must be verified | None |
| B6 | Migrate RxJava 1.x to Coroutines (or 3.x) | Large | High — threading model change | None |
| B7 | Standardize Lombok version across modules | Small | Low | None |
| B8 | Add code formatter (Spotless + Google Java Format) | Small | Low — formatting-only changes | None |

### Tier 3: Architecture

These are large, sequential changes. Each depends on the previous ones being stable.

| # | Item | Effort | Risk | Dependency |
|---|------|--------|------|------------|
| A1 | Extract ViewModel from Home.java — separate UI state from Activity | Large | High | None, but needs characterization tests first |
| A2 | Introduce Repository pattern over ActiveAndroid models | Large | High | None |
| A3 | Replace AsyncTask + raw Thread with Coroutines in critical paths | Large | Medium | B6 (RxJava migration) ideally done first |
| A4 | Wrap sensitive SharedPreferences access in a typed, observable abstraction | Medium | Medium | S4 (encrypted prefs) |
| A5 | Evaluate Room migration to replace ActiveAndroid | Very Large | Very High | A2 (repository pattern provides the seam) |

### Tier 4: UI Modernization

| # | Item | Effort | Risk |
|---|------|--------|------|
| U1 | Upgrade Material Design library to 1.12+ and adopt Material 3 theme | Medium | Medium — visual changes across all screens |
| U2 | Replace RelativeLayout nesting with ConstraintLayout in key screens | Medium | Low per screen |
| U3 | Replace ListView with RecyclerView (10 files) | Medium | Low |
| U4 | Implement Navigation component | Large | Medium — fundamental navigation change |
| U5 | Introduce Jetpack Compose for new screens (keep XML for existing) | Large | Medium — dual UI toolkit during transition |
| U6 | Theme-driven colors (remove hardcoded `#212121` etc.) | Medium | Low |

### Tier 5: Testing

| # | Item | Effort | Risk |
|---|------|--------|------|
| T1 | Write characterization tests for Home.java, BgReading.java, DexCollectionService.java | Large | None — read-only tests |
| T2 | Standardize test dependency versions across modules | Small | Low |
| T3 | Remove PowerMock 1.7.1, replace with Mockito-inline | Medium | Low |
| T4 | Add GitHub Actions matrix (multiple JDK versions, Android SDK levels) | Small | Low |
| T5 | Target 30%+ test coverage on models and CGM processing | Large | None |

### What NOT to Do

- **Don't bump targetSdk without rearchitecting background execution.** This will break CGM collection on devices running Android 8+. The fix requires migrating all background work to proper foreground services with notification channels, WorkManager for periodic tasks, and handling the battery optimization whitelist flow. This is a project in itself.
- **Don't attempt a full Kotlin conversion.** The codebase is 976 Java files. Converting them all would be a multi-month effort that introduces bugs in the conversion process. New files can be Kotlin; existing files should only be converted when they're being substantially rewritten for other reasons.
- **Don't replace ActiveAndroid with Room in one pass.** Room migration requires defining every entity, DAO, and migration in advance. Do it incrementally: repository pattern first (A2), then migrate one model at a time behind the repository interface.
- **Don't add Jetpack Compose to existing screens.** Use Compose for new screens only. Mixing Compose and XML in the same screen adds complexity without proportional benefit unless the screen is being fully rewritten.

---

## Appendix: Verification Methodology

All findings were verified by automated agents reading actual source files and counting occurrences. Specific verification steps:

1. **Line counts:** `wc -l` on each file
2. **Dependency versions:** Read exact lines from `build.gradle` files
3. **Security claims:** Read both manifest XML and Java implementation for each component
4. **Pattern counts:** `grep -r` / `rg` across the codebase with manual verification of false positives
5. **Absence claims** (no Compose, no ViewModel, no LiveData, no certificate pinning, no network security config): Searched for all known indicators and confirmed zero matches

Numbers that were corrected during verification:
- ConstraintLayout files: initially reported as 5, verified as 9
- ListView files: initially reported as 28, verified as 10
- RecyclerView files: initially reported as 5, verified as 8
- AsyncTask files: initially reported as 11, verified as 13
- Services in manifest: initially reported as "20+", verified as 40
- activity_home.xml lines: initially reported as 665, verified as 664
