# Play Store Distribution — Research & Plan

**Date:** 2026-03-23
**Status:** Decided: GitHub releases for 1.0.0, Play Store deferred

---

## Why Play Store?

Strimma currently distributes via GitHub releases (signed APKs). This works but has growing problems:

1. **Android Auto requires Play Store.** The `androidx.car.app` Car App Library specifically checks that the app was installed from a trusted source. The "unknown sources" developer toggle in Android Auto does **not** work for Car App Library apps — [confirmed by Google's official docs](https://developer.android.com/training/cars/testing):

   > "Android Auto has a developer option that lets you run apps that aren't installed from a trusted source. This setting applies to media, messaging notifications, and parked apps but **doesn't apply to apps built using the Android for Cars App Library.**"

2. **Google is tightening sideloading.** Starting Sep 2026 (rolling out regionally), APKs from unverified developers will be blocked unless installed via ADB. [Source](https://hackaday.com/2025/10/06/google-confirms-non-adb-apk-installs-will-require-developer-registration/)

3. **No auto-updates.** GitHub releases require users to manually download and install each update.

---

## Play Store Testing Tracks

Google Play has four distribution tracks. Each is a step toward full public visibility.

| Track | Who can install | Google review | Max testers | Notes |
|-------|----------------|---------------|-------------|-------|
| **Internal** | Invited by email | **No review** | 100 | Available in minutes. For dev/QA. |
| **Closed** | Invited by email or link | **Yes, on first upload** | Unlimited | Required 14-day gate for production access (personal accounts). |
| **Open** | Anyone with link | Yes | Unlimited | Public beta. |
| **Production** | Everyone via Play Store search | Yes | Everyone | Full public listing. |

**Source:** [Internal testing](https://play.google.com/console/about/internal-testing/), [Closed testing](https://play.google.com/console/about/closed-testing/)

### Internal testing — no review

Internal testing bypasses app review entirely. Builds are available to up to 100 invited testers within minutes of upload. This is sufficient for personal use and early community testing.

### Closed testing — 12-tester gate

For **personal** developer accounts (created after Nov 2023), Google requires a closed test with **12+ opted-in testers for 14 consecutive days** before granting production access. Testers must:

- Opt in via a link you share
- Stay opted in for 14 consecutive days (if someone drops out, their replacement's clock resets)
- Use real Android devices (emulators don't count)
- Actually engage with the app (Google monitors for inactive/fake testers)

**Organization accounts are exempt** from the 12-tester requirement.

**Source:** [App testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en)

---

## Organization Account Requirement for Health Apps

As of **January 28, 2026**, Google requires developers publishing health or medical apps to use an **organization** developer account. Personal/individual accounts are no longer permitted for these categories.

From [Choose a developer account type](https://support.google.com/googleplay/android-developer/answer/13634885?hl=en):

> "You should choose an organization account if you provide any of the following services: [...] health apps, such as Medical apps and Human Subjects Research apps."

From [Google Play policy announcements](https://support.google.com/googleplay/android-developer/announcements/13412212?hl=en):

> "Google is **requiring** developers providing services in financial products, health, VPN, and government categories to register as an Organization."

This applies regardless of which Play Store category you list under. If the app touches health data in any way, the [Health Content and Services policy](https://support.google.com/googleplay/android-developer/answer/16679511?hl=en) applies.

### What an organization account requires

- **D-U-N-S number** — a unique business identifier from Dun & Bradstreet. Free to obtain but takes 1-2 weeks.
- **Registered business entity** — in Sweden, an enskild firma (sole proprietorship) registered via [verksamt.se](https://www.verksamt.se) is sufficient.
- **$25 one-time fee** — same as a personal account.
- **Google verification** — business name, address, contact details. Takes days.

### Precedent: CGM apps on Play Store

Two apps with architectures similar to Strimma are currently on Google Play:

- **[GlucoDataHandler](https://play.google.com/store/apps/details?id=de.michelinside.glucodatahandler)** — receives CGM data from multiple sources including notification parsing (beta). Has Android Auto support via companion app. Developer: pachi81 (individual).
- **[Juggluco](https://play.google.com/store/apps/details?id=tk.glucodata)** — direct BLE CGM connection. Developer: Jaap Korthals Altes (individual). Has been [removed from Play Store at least once](https://www.juggluco.nl/Juggluco/removed.html) for policy issues.

Both appear to have been published under personal accounts before the Jan 2026 org requirement. Their current compliance status is unknown.

---

## Health App Policy Requirements

### Health Apps Declaration Form

All apps on Google Play must complete the [Health apps declaration form](https://support.google.com/googleplay/android-developer/answer/14738291?hl=en). For apps with health features, this includes declaring what health functionality the app provides.

### Disclaimer (required for non-regulated apps)

Apps without regulatory clearance (FDA, CE mark) must include in the **first paragraph** of the app description:

> "This app is not a medical device and does not diagnose, treat, or prevent any condition."

### Privacy policy

Required. Must be:
- Identical URL in Play Console, in-app, and on website
- Publicly accessible, non-geofenced, non-editable
- Comprehensive about data access, collection, use, and sharing

A GitHub Pages site is acceptable.

### External hardware disclosure

Apps connecting to external devices (like CGMs) must clearly disclose hardware requirements in the app description and not imply the app works without the hardware.

**Source:** [Health Content and Services](https://support.google.com/googleplay/android-developer/answer/16679511?hl=en), [Health app categories](https://support.google.com/googleplay/android-developer/answer/13996367?hl=en)

---

## NotificationListenerService — Sensitive Permission

Google classifies `BIND_NOTIFICATION_LISTENER_SERVICE` as a high-risk sensitive permission. Publishing an app that uses it requires:

- **Permissions Declaration Form** — justification that notification access is core functionality with no alternative method
- **Extended review** — may take several weeks (not days)

Strimma's justification: CGM apps (Dexcom, Libre, CamAPS FX, Juggluco, etc.) do not expose public APIs or broadcast intents from their official apps. Notification parsing is the only available method to read glucose data from these apps. GlucoDataHandler uses the same approach and is approved on Play Store.

**Source:** [Permissions and APIs that Access Sensitive Information](https://support.google.com/googleplay/android-developer/answer/16558241)

---

## Android Auto — Technical Scope

The actual code for Android Auto BG display is small:

- **`CarAppService`** — entry point, declares the app to Android Auto
- **`Screen` + `Template`** — e.g. `MessageTemplate` for large BG text display
- **`HostValidator`** — `ALLOW_ALL_HOSTS_VALIDATOR` for debug, allowlist for release
- **Manifest entries** — `automotive_app_desc.xml`, service declaration
- **Dependency** — `androidx.car.app:app` + `androidx.car.app:app-projected`
- **Data** — subscribe to `StrimmaService` reading Flow, call `invalidate()` on new values

Template restrictions mean no custom graphs — just big text, icon, maybe a simple layout. Fine for a BG display.

**Testing:** Use the [Desktop Head Unit (DHU)](https://developer.android.com/training/cars/testing/dhu) for development. Real-car testing requires Play Store install (any track).

**Source:** [Use the Android for Cars App Library](https://developer.android.com/training/cars/apps/library), [Car App Library fundamentals codelab](https://developer.android.com/codelabs/car-app-library-fundamentals)

---

## Action Plan

### Phase 1: Play Store setup (no code)

| Step | Effort | Dependency |
|------|--------|------------|
| Register enskild firma via verksamt.se | ~30 min online | None |
| Obtain D-U-N-S number from Dun & Bradstreet | Free, 1-2 weeks wait | Enskild firma |
| Create Google Play organization developer account ($25) | Days for verification | D-U-N-S number |
| Create privacy policy page (GitHub Pages) | ~1 hour | None |
| Prepare store listing assets (icon, screenshots, description) | ~2 hours | None |

### Phase 2: Internal testing (immediate distribution)

| Step | Effort | Dependency |
|------|--------|------------|
| Configure Play App Signing | ~30 min | Dev account |
| Update build to produce AAB (Android App Bundle) | Small — Gradle config | None |
| Upload first AAB to internal testing track | Minutes, no review | Dev account |
| Invite testers via email | Minutes | Upload |

### Phase 3: Health compliance + closed testing

| Step | Effort | Dependency |
|------|--------|------------|
| Complete Health Apps Declaration Form | ~1 hour | Dev account |
| Complete Permissions Declaration Form (NotificationListener) | ~1 hour | Dev account |
| Submit to closed testing track | First upload triggers review (weeks) | Declaration forms |
| Recruit 12 testers for 14-day gate (if needed for production) | Community outreach | Closed track approved |

### Phase 4: Android Auto feature (code)

| Step | Effort | Dependency |
|------|--------|------------|
| Add `androidx.car.app` dependencies | Small | None |
| Implement `CarAppService` + BG display screen | ~2-3 files | None |
| Test with Desktop Head Unit | Hours | DHU installed |
| Test in real car via Play Store install | Requires any Play Store track | Phase 2 or 3 |

---

## Decision: Keep GitHub Releases?

Options:

1. **Play Store only** — simplest, one distribution channel. Users get auto-updates.
2. **Both** — Play Store for most users, GitHub APKs for users who can't or won't use Play Store (degoogled phones, etc.). More maintenance.
3. **GitHub only** — status quo. No Android Auto, no auto-updates, increasingly hostile sideloading.

The existing GitHub Actions release workflow can coexist with Play Store distribution. The signing key setup would need to accommodate Play App Signing (Google manages the release key, you keep an upload key).

---

## Open Questions

- Does Strimma need to be categorized as "Medical" or can "Health & Fitness" work? Medical has stricter requirements but is more accurate.
- Is the existing release keystore compatible with Play App Signing, or do we need a new upload key?
- Should Android Auto be a separate module/APK or integrated into the main app?
