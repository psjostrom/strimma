# Monthly Story

A curated monthly glucose summary, inspired by Spotify Wrapped. View your highlights, patterns, and narrative for each completed month.

## How it works

At the start of each month, Strimma generates a Story for the previous month. The Story appears as a card at the top of the Statistics screen. After you've opened it, the card stays in place but switches to a muted style — tap it any time to revisit the same month's Story.

Tap the card to open a full-screen pager with 6 pages:

1. **Your Month** — TIR, GMI, CV, average glucose, comparison vs last month
2. **Flatlines & Streaks** — Longest flatline, in-range streak, steadiest day
3. **Lows & Highs** — Event counts, duration, trend vs last month
4. **Time of Day** — TIR by time block (night, morning, afternoon, evening)
5. **Your Meals** — Best/worst meal type, excursion by meal (requires treatments)
6. **Your Story** — Written narrative summary with share button

## Browsing other months

Use the **←** and **→** arrows in the top-right of the Story screen to scroll through every past month with data. Bounds:

- **Earliest reachable month:** the month containing your oldest local reading. Backfilling history via *Settings > Sharing > Pull readings* unlocks earlier months automatically.
- **Latest reachable month:** the most recently completed month. The current in-progress month is hidden because partial-month stats are misleading.

A month with fewer than 7 days of data still shows the empty state — the arrows let you keep scrolling past it.

Local retention (*Settings > General > Storage > Data retention*) caps how far back you can scroll. With the default *Forever*, every month with data on this device is reachable.

## Requirements

- At least 7 days of glucose readings in the month
- Comparison to previous month requires 7+ days in that month too
- Meals page requires Nightscout treatment sync to be enabled

## Sharing

Tap "Share your Story" on the last page to share a screenshot via Android's share sheet.
