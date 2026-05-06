# Workout Mode

Workout Mode raises Strimma's alert thresholds and the in-range band on graphs while you're exercising. It's designed for the way blood glucose moves during a run, ride, or workout: sharper drops at the low end, transient highs from adrenaline at the top.

## Defaults

| | Standard | Workout |
|---|---|---|
| Low | 4.0 mmol | 6.0 mmol |
| Urgent low | 3.0 mmol | 5.0 mmol |
| High | 10.0 mmol | 14.0 mmol |
| Urgent high | 13.0 mmol | 16.0 mmol |

You can change all four workout thresholds in **Settings → Exercise → Workout mode**.

## What changes while Workout Mode is ON

- **Alerts** use the workout thresholds instead of the standard ones. Alert titles are prefixed with `Workout · ` (e.g., `Workout · Urgent Low`) so the severity label stays unambiguous — see [Alerts](alerts.md).
- **In-range band on graphs** (and the BG hero color, widget, web server) uses the workout thresholds, so 12 mmol shows as in-range green during exercise instead of amber high.
- **Predict-low / predict-high** alerts use the workout thresholds — fewer false alarms during the rapid swings of exercise.
- **Stale-sensor alerts are suppressed for the first 30 minutes** of the session — sensor contact loss right when you start moving is expected and shouldn't trigger an alarm. After 30 min, stale alerts re-arm even while workout mode stays on; for a multi-hour event you still need to know if the sensor died.
- **Historical analysis is unaffected.** The Story screen (monthly TIR / AGP / meal stats) always uses your standard thresholds, never workout thresholds, so opening Stats during a workout doesn't corrupt last-month's report.

## How to turn it on

There are two ways:

**Manual toggle.** Tap the runner-person icon at the top-right of the main screen, the "Workout" pill below the BG value, or use the "Start workout" action button on the foreground notification. The pill appears in the BG header and the icon tints cyan. After the first minute the pill shows the elapsed time (e.g., "Workout 0:42"). Tap any of these to turn off.

**Calendar event.** If you've configured a workout calendar in Strimma (Settings → Exercise → Calendar), workout mode auto-activates when an event in that calendar is currently happening. The pill shows the elapsed time since the event started.

Manual action always wins. If a calendar event is active and you turn off manually — whether you toggled on yourself during that event or it was the calendar that put you in workout mode — mode stays off until that event ends. Normal calendar logic resumes for future events.

## Auto-off safety timeout

When you turn workout mode on manually, it auto-turns-off after a configurable number of hours (default 3, range 1–12). This prevents a forgotten toggle from keeping wider thresholds active during your commute home. Calendar-driven workout mode has no timeout — it ends when the calendar event ends.

If you regularly do longer workouts (marathons, ultras, all-day rides), bump the safety timeout in settings before heading out.

## Coexistence with Pause All

Pause All and Workout Mode are independent.

- For low / high alerts: Pause All wins (alerts off means workout thresholds are moot until pause expires).
- For stale alerts: Workout Mode is the only suppressor — Pause All does NOT cover stale alerts.
