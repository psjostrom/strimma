# Privacy & Security

Strimma takes your medical data seriously. Here's exactly what it does with your data.

---

## Data Collection

**Strimma collects no data.** There is no analytics, no telemetry, no crash reporting, no usage tracking, and no network calls except to the servers you configure (Nightscout, LibreLinkUp, Tidepool).

---

## Data Storage

### On Your Device

| Data | Storage | Encryption |
|------|---------|------------|
| Glucose readings | Room database (`strimma.db`) | Android filesystem encryption |
| Treatment data | Room database (`strimma.db`) | Android filesystem encryption |
| Settings | DataStore Preferences | Android filesystem encryption |
| Nightscout API secret | EncryptedSharedPreferences | AES-256 (Android Keystore) |
| Debug logs | Plain text files (7-day retention) | Android filesystem encryption |

- Glucose readings are retained locally for **30 days**, then automatically pruned
- Treatment data is retained for **30 days**, then pruned
- Debug logs are retained for **7 days**, then deleted

### On Your Nightscout Server

If you configure Nightscout push, your glucose readings are sent to your server. Strimma has no control over data retention on your Nightscout server — that's configured on the server side.

---

## Network Communication

Strimma only makes network requests to:

1. **Your Nightscout server** (push URL) — to upload glucose readings
2. **Your follower Nightscout server** (follower URL) — to download readings in Nightscout Follower mode
3. **Abbott's LibreLinkUp API** — to download readings in LibreLinkUp mode (only if you configure LibreLinkUp credentials)
4. **Tidepool** — to upload glucose readings to your Tidepool account (only if you configure Tidepool credentials)

No other network connections are made. No data is sent to Strimma's developers, third-party services, or any other endpoint.

### Authentication

- The Nightscout API secret is hashed with SHA-1 before transmission
- The plain-text secret never leaves your device
- HTTPS is supported and recommended for all Nightscout connections

---

## Permissions

| Permission | Why |
|-----------|-----|
| Notification access | Read glucose from CGM app notifications (Companion mode only) |
| Foreground service | Keep Strimma running for continuous monitoring |
| Internet | Communicate with your Nightscout server |
| Boot completed | Auto-start after device restart |
| Battery optimization exemption | Prevent Android from killing the service |
| Post notifications | Show the BG notification and alerts |
| Notification policy access | Allow urgent alerts to bypass Do Not Disturb |
| Health Connect (exercise, heart rate, steps, calories) | Read exercise sessions for exercise-BG analysis |
| Health Connect (blood glucose write) | Optionally write CGM readings to Health Connect |
| Calendar read | Read calendar events for context (e.g., scheduled workouts) |
| Exact alarm scheduling | Schedule precise alert timing |

Strimma does not access your contacts, camera, microphone, location, or files.

---

## Open Source

Strimma's entire source code is publicly available on [GitHub](https://github.com/psjostrom/strimma) under the GPLv3 license. You can audit every line of code to verify these privacy claims.

---

## Settings Backup

The settings export feature (**Settings > Sharing > Export Settings**) creates a JSON file that includes your Nightscout API secret in **plain text**. Handle exported settings files securely — don't share them publicly or store them in unencrypted cloud storage.

---

## Local Web Server

If you enable the local web server (**Settings > Sharing > Local Web Server**), your glucose data is accessible to any device on your local network on port 17580. The web server does not expose data to the internet unless you've specifically configured port forwarding on your router (don't do this — use Nightscout for remote access).
