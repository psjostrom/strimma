# Initial Setup

Strimma includes a setup wizard that walks you through permissions and data source configuration on first launch. This page describes what each step does — useful if you need to reconfigure later or troubleshoot permissions.

---

## Step 1: Notification Access (Companion Mode)

If you selected **Companion** as your data source, Strimma needs notification access to read glucose from your CGM app's notifications. The setup wizard guides you through this with buttons that open the right settings screens.

Android blocks notification access for sideloaded apps by default. Three steps are needed:

1. **Open notification settings and try to enable Strimma.** Android will block it — this is expected.
2. **Open Strimma's app info**, tap the **⋮** menu in the top right, then tap **"Allow restricted settings"**. You may need to confirm with your fingerprint or PIN.
3. **Open notification settings again** and enable Strimma. It will work this time. Android will show a warning dialog — read it and tap **Allow**.

!!! warning "Required for Companion mode"
    Without notification access, Companion mode cannot receive glucose data. Other data sources (xDrip Broadcast, Nightscout Follower, LibreLinkUp) do not need this permission.

!!! info "About the scary warning"
    Android shows a strong warning when you grant notification access: *"This app will be able to read all your notifications."* This is because Android's notification access is all-or-nothing — there's no way to grant access to only specific apps' notifications.

    **What Strimma actually does:** It only reads notifications from a specific list of known CGM app packages (Dexcom, Libre, CamAPS FX, etc.). All other notifications are ignored — Strimma never reads your messages, emails, or other app notifications. The full list of monitored apps is in [Supported CGM Apps](../data-sources/supported-apps.md), and you can verify this in the [source code](https://github.com/psjostrom/Strimma).

!!! tip "If notification access gets revoked"
    Android may revoke notification access after system updates, app updates, or battery optimization events. If Strimma suddenly stops receiving data, check this setting first:

    **Settings > Apps > Special app access > Notification access > Strimma**

    The exact path varies by manufacturer:

    - **Samsung:** Settings > Apps > Menu (⋮) > Special access > Notification access
    - **Xiaomi:** Settings > Apps > Manage apps > Strimma > Permissions > Notification access
    - **Pixel/stock Android:** Settings > Apps > Special app access > Notification access

---

## Step 2: Battery Optimization Exemption

Android aggressively kills background apps to save battery. Strimma needs to run continuously to receive glucose readings and send alerts.

1. Strimma will prompt you to disable battery optimization
2. Tap **Allow** to let Strimma run in the background without restrictions

!!! info "Battery impact"
    Strimma uses very little battery. In Companion and xDrip Broadcast modes it passively listens for incoming data; in Follower and LibreLinkUp modes it polls at configurable intervals. Most users see negligible battery impact.

---

## Step 3: Notification Permission

On Android 13+, apps must ask for permission to post notifications. Strimma needs this for:

- The persistent foreground notification showing your current BG
- Glucose alerts (low, high, urgent)

Tap **Allow** when prompted.

---

## Step 4: Choose Your Data Source

The setup wizard asks you to choose how Strimma receives glucose data. You can also change this later in **Settings > Data Source**.

| Mode | Best for | How it works |
|------|----------|-------------|
| **Companion** (default) | Most users | Reads glucose from your CGM app's notification |
| **xDrip Broadcast** | xDrip+/Juggluco/AAPS users | Receives glucose from apps that broadcast in xDrip format |
| **Nightscout Follower** | Caregivers, remote monitoring | Polls a remote Nightscout server for readings |
| **LibreLinkUp** | Libre 3 users without notification access | Reads glucose from Abbott's LibreLinkUp cloud |

Most users should leave this on **Companion**. See [Data Sources](../data-sources/overview.md) for details on each mode.

---

## Step 5: Set Up Nightscout (Optional)

If you have a Nightscout server and want to push your glucose data to it:

1. Go to **Settings > Data Source**
2. Enter your **Nightscout URL** (e.g., `https://my-nightscout.fly.dev`)
3. Enter your **API Secret**

Strimma will immediately start pushing readings. See [Nightscout Push Setup](../nightscout/push-setup.md) for details.

---

## You're Ready

If you're using **Companion mode**, open your CGM app and wait for its next glucose notification — Strimma will pick it up within seconds. For other data sources, Strimma will start receiving data automatically once configured.

Proceed to [Your First Reading](first-reading.md) to see what to expect.
