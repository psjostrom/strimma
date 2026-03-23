# Privacy Policy

**Effective date:** 2026-03-23
**Last updated:** 2026-03-23

Strimma is an open-source Android app for displaying continuous glucose monitor (CGM) data. This privacy policy explains what data Strimma accesses, how it is used, and your rights.

---

## Summary

Strimma **does not collect, transmit, or share any personal data** with the developer or any third party. All data stays on your device unless you explicitly configure Nightscout integration, in which case data is sent only to your own server.

---

## Data Accessed

### Glucose readings

Strimma reads glucose values from your CGM app's notification (Companion Mode), via xDrip-compatible broadcast intents, or from a Nightscout server you configure (Follower Mode). These readings are stored locally on your device in a Room database.

### Treatment data

If you connect Strimma to a Nightscout server, it can download treatment records (insulin doses, carbohydrates) from that server. Treatment data is stored locally for up to 48 hours.

### Nightscout credentials

If you configure Nightscout integration, Strimma stores your server URL and API secret on your device. The API secret is encrypted using AES-256 via Android Keystore (EncryptedSharedPreferences). The plain-text secret never leaves your device — only a SHA-1 hash is transmitted to your Nightscout server for authentication.

### Debug logs

Strimma can store diagnostic log files on your device. Logs are retained for 7 days and automatically deleted. Logs are never transmitted anywhere — you can choose to share them manually via Android's share dialog.

---

## Data NOT Collected

Strimma does **not** collect, access, or transmit:

- Personal identifying information (name, email, phone number, address)
- Device identifiers or advertising IDs
- Location data
- Usage analytics or telemetry
- Crash reports
- Any data to the developer, third-party services, ad networks, or analytics platforms

---

## Network Communication

Strimma only makes network requests to servers **you configure**:

| Connection | When | What is sent |
|-----------|------|-------------|
| Nightscout push | When you configure a Nightscout URL | Glucose readings (value, timestamp, direction) |
| Nightscout follower | When you enable follower mode | Query parameters to fetch readings |
| Local web server | When you enable this feature | Glucose data served on your local network only |

No other network connections are made. Strimma does not contact any remote server by default.

---

## Data Retention

| Data | Retention | Location |
|------|-----------|----------|
| Glucose readings | 30 days | On-device Room database |
| Treatment data | 48 hours | On-device Room database |
| Settings | Until you change or uninstall | On-device DataStore |
| Nightscout API secret | Until you change or uninstall | On-device EncryptedSharedPreferences |
| Debug logs | 7 days | On-device files |

Uninstalling Strimma deletes all data from your device.

---

## Permissions

| Permission | Purpose |
|-----------|---------|
| Notification access | Read glucose values from your CGM app's notification |
| Foreground service | Keep Strimma running for continuous glucose monitoring |
| Internet | Communicate with your Nightscout server (only if configured) |
| Boot completed | Auto-start Strimma after device restart |
| Battery optimization exemption | Prevent Android from stopping the monitoring service |
| Post notifications | Display the glucose notification and alerts |
| Notification policy access | Allow urgent low glucose alerts to bypass Do Not Disturb |

No other permissions are requested.

---

## Children's Privacy

Strimma does not knowingly collect data from children. The app processes glucose data identically regardless of the user's age. No personal information is collected from any user.

---

## Your Rights

Since Strimma stores all data locally on your device and transmits nothing to the developer:

- **Access:** All your data is on your device — you can export it via CSV from the Statistics screen.
- **Deletion:** Uninstall Strimma to delete all data. You can also clear app data from Android Settings.
- **Portability:** Use CSV export or Nightscout to transfer your data.

---

## Open Source

Strimma is free and open-source software licensed under the [GNU General Public License v3.0](https://github.com/psjostrom/Strimma/blob/main/LICENSE). The entire source code is publicly available for audit at [github.com/psjostrom/Strimma](https://github.com/psjostrom/Strimma).

---

## Medical Disclaimer

Strimma is not a medical device and is not intended to diagnose, treat, cure, or prevent any medical condition. Do not make medical decisions based solely on information displayed by Strimma. Always consult your healthcare provider.

---

## Changes to This Policy

Changes to this privacy policy will be posted on this page with an updated date. Since Strimma is open-source, all changes are visible in the project's commit history.

---

## Contact

For questions about this privacy policy, open an issue on [GitHub](https://github.com/psjostrom/Strimma/issues).
