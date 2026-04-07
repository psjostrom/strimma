# Tidepool

Strimma can upload your glucose readings to [Tidepool](https://www.tidepool.org/), a nonprofit, vendor-neutral diabetes data platform used by clinics and researchers.

---

## What Is Tidepool?

Tidepool provides a free, open platform for viewing and sharing diabetes data. Many endocrinologists use Tidepool to review CGM data during clinic visits. By uploading from Strimma, your readings are available alongside data from other devices in one place.

---

## Enable Tidepool Upload

1. Go to **Settings > Tidepool**
2. Toggle **Upload to Tidepool** on
3. Tap **Log in** — this opens a browser-based login (OIDC). Sign in with your Tidepool account
4. After login, you are redirected back to Strimma. The connection is saved — you only need to log in once

---

## What Gets Uploaded

Strimma uploads **glucose readings** to Tidepool. Treatments (bolus, carbs, basal) are not uploaded — those typically reach Tidepool through your pump or closed-loop system.

---

## Upload Frequency

Readings are uploaded automatically every **20 minutes**. Tidepool rate-limits uploads, so more frequent syncing is not possible.

If you want to push readings immediately, use the **Force upload** button in **Settings > Tidepool**.

---

## Disconnect

To stop uploading and remove your Tidepool credentials:

1. Go to **Settings > Tidepool**
2. Tap **Disconnect**

This removes the stored session. You can reconnect at any time by logging in again.

