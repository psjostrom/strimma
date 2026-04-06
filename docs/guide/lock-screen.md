# Lock Screen & Always-On Display

Strimma includes a live wallpaper that shows your glucose value, trend arrow, delta, and an optional mini graph directly on your lock screen and always-on display (AOD). No need to unlock your phone to check your BG.

## What it shows

- **BG value** -- large, centered, color-coded (cyan for in-range, amber for high, coral for low, grey for stale)
- **Trend arrow** -- next to the BG value
- **Delta** -- below the value, showing the rate of change with unit
- **Time since reading** -- how long ago the last reading was received
- **Mini graph** (optional) -- last 1 hour of readings as color-coded dots

## How to enable

1. Open Strimma and go to **Settings > Display**
2. In the **Live wallpaper** section, tap **Live wallpaper** to open Android's wallpaper picker
3. Select **Strimma BG** from the list
4. Set it as your **lock screen wallpaper** (you can keep a different home screen wallpaper)

<!-- Screenshot `lock-screen.png` may need to be created -->

## Settings

| Setting | Description |
|---------|-------------|
| **Live wallpaper** | Opens Android's live wallpaper picker |
| **Graph on wallpaper** | Toggle the mini glucose graph on or off |

## Tips

- **Set as lock screen only** to keep your regular home screen wallpaper. Android lets you choose different wallpapers for the home screen and lock screen.
- **OLED-friendly** -- the wallpaper uses a transparent background with minimal colored elements (dots, not lines), which is gentle on OLED screens.
- **Battery impact is minimal** -- the wallpaper only redraws when a new glucose reading arrives (typically every 1-5 minutes depending on your CGM). There is no continuous animation.

## Troubleshooting

### The wallpaper is not updating

Make sure Strimma's foreground service is running (you should see the Strimma notification in your notification shade). The wallpaper reads data from the same database as the main app.

### The wallpaper disappears after a restart

Android may clear live wallpapers after a restart on some devices. If this happens, re-select Strimma BG as your lock screen wallpaper.
