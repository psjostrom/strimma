# Install Strimma

Strimma is distributed as an APK from GitHub Releases. It is not on Google Play.

---

## Download

1. Open [Strimma Releases](https://github.com/psjostrom/Strimma/releases) on your Android phone
2. Find the latest release
3. Under **Assets**, tap the `.apk` file to download
4. When prompted, tap **Install**

!!! note "Allow installation from unknown sources"
    Android may ask you to allow installation from your browser. This is normal for apps distributed outside Google Play. Go to **Settings > Apps > Special app access > Install unknown apps** and enable it for your browser.

---

??? tip "Verify the APK (optional, for advanced users)"
    Every release APK is signed with the Strimma release key. If you want to verify the signature, you can run this on a computer with Android SDK tools installed:

    ```bash
    apksigner verify --print-certs strimma-<version>.apk
    ```

    Most users can skip this — the APK is built and signed automatically by GitHub Actions from the public source code.

---

## Update Strimma

To update, download the latest release APK and install it over the existing version. Your settings and data are preserved — Android keeps them as long as the signing key matches.

!!! tip "Check for updates"
    Watch the [Strimma repository](https://github.com/psjostrom/Strimma) on GitHub to get notified of new releases.

---

## Supported Devices

- **Android version:** 13 (Tiramisu) or newer
- **Architecture:** arm64, arm32, x86_64
- **Tested on:** Pixel 9 Pro (Android 16)

Strimma should work on any Android 13+ device. If you encounter issues on your device, please [open an issue](https://github.com/psjostrom/Strimma/issues).

---

## Next Step

Once installed, proceed to [Initial Setup](setup.md) to grant the permissions Strimma needs.
