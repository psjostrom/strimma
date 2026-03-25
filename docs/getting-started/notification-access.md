# Notification Access on Android 13+

Android 13 introduced "restricted settings" for apps installed outside the Play Store (sideloaded). This affects Strimma when installed from GitHub releases, which is the recommended installation method.

**This is a security feature, not a bug.** Android wants to make sure you intentionally grant powerful permissions to apps you've installed manually.

---

## Why does Strimma need notification access?

Strimma reads glucose values from your CGM app's notifications. This is the same approach used by xDrip+, Juggluco, and other CGM companion apps. Without notification access, Strimma can't see any glucose data.

Android's notification access is all-or-nothing — there's no way to grant access to only specific apps' notifications. Strimma only reads notifications from known CGM apps and ignores everything else.

---

## How to grant notification access

### Step 1: Try to enable notification access

1. Open Android **Settings** > **Apps** > **Special app access** > **Notification access**
2. Find **Strimma** in the list and try to toggle it **on**
3. Android will show a message: *"For your security, this setting is currently unavailable"*

This is expected. Move on to step 2.

### Step 2: Allow restricted settings

1. Open Android **Settings** > **Apps** > **Strimma**
2. Tap the **⋮** (three-dot menu) in the top right corner
3. Tap **"Allow restricted settings"**
4. You may need to confirm with your fingerprint or PIN

!!! tip "Can't find the menu option?"
    The "Allow restricted settings" option only appears **after** you've attempted step 1. If you don't see it, go back and try step 1 first.

### Step 3: Enable notification access

1. Go back to **Settings** > **Apps** > **Special app access** > **Notification access**
2. Find **Strimma** and toggle it **on**
3. Android will show a warning dialog — read it and tap **Allow**

That's it. Strimma can now read glucose notifications.

---

## Play Store installs

If Strimma is installed from the Google Play Store, Android does not apply the restricted settings restriction. You can skip step 2 — step 1 will work directly.

---

## Still not working?

- Make sure your CGM app is running and showing glucose notifications
- Check that Strimma's data source is set to **Companion Mode** (Settings > Data Source)
- Try toggling notification access off and on again
- Restart your phone

See the [Troubleshooting](../troubleshooting.md) page for more help.
