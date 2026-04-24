# Troubleshooting

Common issues and how to fix them.

---

## No Glucose Data

### Strimma shows "No data"

1. **Check the data source:**
   Settings > Data Source — make sure the correct mode is selected. Then follow the steps for your mode:

2. **Companion mode:**
    - Check notification access: Android Settings > Apps > Special app access > Notification access > Strimma must be **enabled**
    - Check your CGM app is running and posting notifications (some apps let you disable them)
    - Check the debug log (Settings > Debug Log) for messages about received or rejected notifications
    - Check your CGM app is in the [Supported Apps](data-sources/supported-apps.md) list

3. **xDrip Broadcast mode:**
    - Check that the source app (xDrip+, Juggluco, AAPS) is configured to broadcast glucose values
    - Check the debug log for broadcast messages

4. **Nightscout Follower mode:**
    - Check your Nightscout URL and API secret in Settings > Data Source
    - Check that the Nightscout server is reachable (open the URL in a browser)
    - Check the debug log for connection errors

5. **LibreLinkUp mode:**
    - Check your LibreLinkUp email and password in Settings > Data Source
    - Make sure LibreLinkUp sharing is enabled in the Libre 3 app
    - Check the debug log for API errors

---

## Strimma Stops Working / Gets Killed

Android aggressively kills background apps to save battery. Strimma needs to run continuously.

### Fix Battery Optimization

1. Go to Android **Settings > Apps > Strimma > Battery**
2. Select **Unrestricted** (not "Optimized" or "Restricted")

### Fix Manufacturer-Specific Battery Savings

Some manufacturers (Samsung, Xiaomi, Huawei, OnePlus) add extra battery restrictions beyond stock Android:

- **Samsung:** Settings > Device care > Battery > App power management > Strimma > "Don't put to sleep"
- **Xiaomi:** Settings > Battery > App battery saver > Strimma > "No restrictions"
- **Huawei:** Settings > Battery > App launch > Strimma > Manual > Enable all toggles
- **OnePlus:** Settings > Battery > Battery optimization > Strimma > "Don't optimize"

!!! tip
    [Don't Kill My App](https://dontkillmyapp.com/) has device-specific instructions for keeping background apps alive.

---

## Nightscout Push Issues

### Readings not appearing on Nightscout

1. **Check URL:** Settings > Data Source > Nightscout URL — must be the base URL only (e.g., `https://my-ns.fly.dev`), no `/api/v1/entries` path
2. **Check API secret:** Make sure it matches the `API_SECRET` configured on your Nightscout server
3. **Check debug log:** Look for push error messages:
    - `"Push failed: 401"` — wrong API secret
    - `"Push failed: connection refused"` — server is down or URL is wrong
    - `"Push failed: timeout"` — network issue
4. **Test the server:** Open your Nightscout URL in a browser — it should show the dashboard

### Readings are delayed

- Strimma pushes immediately on each new reading
- If there's a delay, check your internet connection
- Unpushed readings are retried every 5 minutes

---

## Treatment Sync Issues

### Treatments are missing or stop updating

1. **Check that treatment sync is enabled:** Settings > Treatments > Treatment sync
2. **Check the debug log:**
    - `Treatments: 404` means the Nightscout server does not expose `/api/v1/treatments.json`
    - `Treatments HTTP ...` or `Treatments fetch error ...` means a real server or network failure
    - `Treatments parse error ...` means the server returned data Strimma could not parse
3. **Check your Nightscout URL and API secret:** Strimma uses the same shared Nightscout configuration for glucose, follower mode, and treatments
4. **Backfill after fixing the server:** Settings > Treatments > Pull 7 days / 14 days / 30 days

---

## Tidepool Upload Issues

### Tidepool does not retry immediately after a failure

- Automatic Tidepool uploads are rate-limited to one background attempt every 20 minutes, even after a failed upload
- After fixing connectivity, login, or server issues, use **Settings > Sharing > Tidepool > Force Upload Now** to retry immediately
- Check the debug log for `Tidepool upload` errors if uploads keep failing

---

## Alerts Not Working

### No alert sound

1. **Check alert is enabled:** Settings > Alerts > make sure the toggle is on
2. **Check the threshold:** Make sure the threshold matches your situation (e.g., a low alert at 4.0 mmol/L won't fire at 4.5 mmol/L)
3. **Check Android notification channel:** Settings > Alerts > Sound button — verify a sound is selected
4. **Check Do Not Disturb:** Regular alerts respect DND. Only Urgent Low and Urgent High bypass DND by default.
5. **Check snooze:** If you snoozed the alert, it won't fire again for 30 minutes

### Alert fires but no sound

- The notification channel may have sound disabled. Tap the **Sound** button next to the alert in Strimma settings to open the Android channel settings.
- Check your phone's volume — alert sound uses the notification volume.

---

## Graph Issues

### Graph is empty

- The graph shows data from the last N hours (default 4). If Strimma hasn't received data in that window, it will be empty.
- Pull history from Nightscout: Settings > Data Source > Pull readings 7 days

### Graph doesn't scroll smoothly

- Very long graph windows (8 hours) with Libre 3 data (1-minute readings) contain many data points. Reduce the graph window or zoom in for smoother interaction.

---

## Debug Log

The debug log is your best tool for troubleshooting. Access it via **Settings > Debug Log**.

### What it shows

- **Live entries** — real-time log messages from the current session
- **File entries** — historical logs from previous sessions (7-day retention)

### Sharing logs

Tap the **share icon** in the debug log screen to share the current log file via email, messaging, or file manager.

When reporting an issue, always include the debug log — it contains the information needed to diagnose the problem. You can report issues on [GitHub](https://github.com/psjostrom/strimma/issues).

---

## Reinstalling / Data Loss

Strimma stores all data locally. If you uninstall the app, all data is lost. To preserve your data:

1. **Export settings** before uninstalling: Settings > Sharing > Export Settings
2. **Nightscout is your backup** — if push is configured, your glucose history is safe on Nightscout and can be pulled back after reinstalling

Updating Strimma (installing a new version over the existing one) preserves all data and settings.
