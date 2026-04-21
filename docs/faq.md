# FAQ

Frequently asked questions about Strimma.

---

## General

### What does "Strimma" mean?

Strimma is a Swedish word for a streak or thin band — the kind of line a CGM sensor draws across your day. *En strimma hopp* (a glimmer of hope) works too.

### Is Strimma free?

Yes. Strimma is free, open-source software licensed under GPLv3. No ads, no subscriptions, no in-app purchases.

### Is Strimma on Google Play?

No. Strimma is distributed as APKs from [GitHub Releases](https://github.com/psjostrom/strimma/releases).

### Does Strimma replace my CGM app?

No. In Companion mode (the default), Strimma works **alongside** your CGM app — it reads data from your CGM app's notification. Other data sources (Nightscout Follower, LibreLinkUp) don't require a CGM app on the same phone.

### Does Strimma connect to my CGM sensor?

No. Strimma never connects to your sensor via Bluetooth. It receives glucose data from other apps or servers, not directly from the sensor. This means it can't interfere with your sensor or your closed-loop system.

---

## Compatibility

### Which phones does Strimma work on?

Any Android phone running Android 13 (API 33) or newer. Strimma has been tested on the Pixel 9 Pro running Android 16.

### Does Strimma work on iOS?

No. Strimma is Android-only. iOS doesn't allow apps to read other apps' notifications.

### Which CGM systems does Strimma work with?

Any CGM system with an Android app that shows glucose in a notification. This includes Dexcom G6/G7, Libre 2, CamAPS FX, Eversense, Medtronic Guardian, xDrip+, Juggluco, and many more. Libre 3 is supported via [LibreLinkUp mode](data-sources/librelinkup.md). See the [full list](data-sources/supported-apps.md).

### Does Strimma work with AndroidAPS / CamAPS FX / Loop?

Yes. Strimma reads notifications passively — it doesn't interfere with any closed-loop system. Many users run Strimma alongside their loop app for better display and alerting.

---

## Data

### Where is my data stored?

Glucose readings are stored in a local database on your phone (Room database). If you configure Nightscout push or Tidepool upload, readings are also sent to those servers. Strimma sends no data anywhere else.

### How long does Strimma keep my data?

Locally: 30 days of glucose readings, 100 days of treatments, 7 days of debug logs. Older data is automatically pruned. Nightscout retains data according to your server's configuration.

### Can I export my data?

Yes. Go to Settings > Statistics, then tap the share icon to export a CSV file. You can export 24 hours, 7 days, 14 days, or 30 days of data.

### Can I back up my settings?

Yes. Settings > Sharing > Export Settings creates a JSON backup of all your settings. Import it on another device or after reinstalling.

---

## Nightscout

### Do I need Nightscout?

No. Strimma works completely offline — it can display glucose from your CGM app's notification or xDrip broadcasts without any server. Nightscout is optional for cloud backup, remote monitoring, and treatment data. (Nightscout Follower mode does require a Nightscout server, since that's where it reads data from.)

### What Nightscout servers does Strimma work with?

Any server that implements the Nightscout API. This includes the standard Nightscout project (cgm-remote-monitor), hosted Nightscout services, and custom implementations like [Springa](https://github.com/psjostrom/springa).

### Can multiple people push to the same Nightscout?

Technically yes, but it's not recommended — readings from different people would be mixed. Each person should have their own Nightscout instance.

---

## Alerts

### Can Strimma wake me up at night for a low?

Yes. Urgent Low and Urgent High alerts bypass Do Not Disturb mode by default. You can also configure **any** alert to bypass DND via the Sound button in Settings > Alerts, which opens Android's notification channel settings where you can enable "Override Do Not Disturb."

### Do alerts keep repeating if I stay low/high?

Yes. Alerts fire on every new glucose reading as long as the condition persists. If you want temporary silence, tap **Snooze** on the alert notification — it silences that alert for 30 minutes. After the snooze expires, it fires again if the condition still holds.

### Can I change the alert sounds?

Yes. In Settings > Alerts, each alert type has a **Sound** button that opens the Android notification channel settings where you can choose any sound.

---

## Battery

### How much battery does Strimma use?

Very little. Strimma's foreground service is lightweight — it does not use Bluetooth or GPS. In Companion mode, it passively listens for notifications. In Follower or LibreLinkUp mode, it polls at configurable intervals. Network usage is minimal.

### Why does Android say Strimma uses a lot of battery?

Android's battery stats can be misleading for foreground services. Because Strimma runs continuously, Android may attribute time-based battery usage to it even though its actual power consumption is minimal.

---

## Technical

### Why does Strimma compute direction locally?

Testing against real CamAPS FX data showed a 31% mismatch rate between the app's reported direction and the direction computed from actual readings using EASD/ISPAD thresholds. Local computation provides consistent trend information regardless of which CGM app you use. See [Direction Arrows](reference/direction.md).

### What units does Strimma use internally?

mg/dL. This matches the Nightscout protocol and CGM industry standard. Display conversion to mmol/L happens at render time. See [Units](reference/units.md).

### Why can't I set different thresholds for alerts vs. graph?

The low/high thresholds in Display settings control both the graph coloring and the alert triggers. This is intentional — having different thresholds for display vs. alerting would be confusing and potentially dangerous (you'd see "in range" colors while being in an alert state, or vice versa).

### Is Strimma a medical device?

No. Strimma is an open-source display tool. It is not a medical device, not FDA/CE approved, and should not be used as the sole basis for medical decisions. Always consult your healthcare team.
