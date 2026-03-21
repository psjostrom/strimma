# Initial Setup

When you first open Strimma, it needs a few permissions to work properly. The app will guide you through each one.

---

## Step 1: Notification Access

Strimma reads glucose values from your CGM app's notifications. Without this permission, Strimma can't see any data.

1. Strimma will prompt you to enable notification access
2. You'll be taken to Android's **Notification access** settings
3. Find **Strimma** in the list and toggle it **on**
4. Android will show a warning dialog — read it and confirm

!!! warning "Required permission"
    This is the core permission Strimma needs. Without notification access, no glucose data will appear.

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
    Strimma uses very little battery — it passively listens for notifications and wakes briefly to process each reading. Most users see negligible battery impact.

---

## Step 3: Notification Permission

On Android 13+, apps must ask for permission to post notifications. Strimma needs this for:

- The persistent foreground notification showing your current BG
- Glucose alerts (low, high, urgent)

Tap **Allow** when prompted.

---

## Step 4: Choose Your Data Source

Go to **Settings > Data Source** and select how Strimma receives glucose data:

| Mode | Best for | How it works |
|------|----------|-------------|
| **Companion** (default) | Most users | Reads glucose from your CGM app's notification |
| **xDrip Broadcast** | xDrip+/Juggluco/AAPS users | Receives glucose from apps that broadcast in xDrip format |
| **Nightscout Follower** | Caregivers, remote monitoring | Polls a remote Nightscout server for readings |

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

Open your CGM app (CamAPS FX, Dexcom, LibreLink, etc.) and wait for its next glucose notification. Strimma will pick it up within seconds.

Proceed to [Your First Reading](first-reading.md) to see what to expect.
