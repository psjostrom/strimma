#!/usr/bin/env python3

from __future__ import annotations

import argparse
import math
import random
import sqlite3
import statistics
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

DEFAULT_PACKAGE = "com.psjostrom.strimma.debug"
DEFAULT_SEED = 20260423
DEFAULT_KEEP_DAYS = 8
RUNNING_EXERCISE_TYPE = 56
ADB_TMP_DB = "/data/local/tmp/strimma-screenshot-seed.db"


@dataclass(frozen=True)
class ExercisePlan:
    title: str
    days_ago: int
    hour: int
    minute: int
    duration_min: int
    avg_hr: int
    max_hr: int
    steps: int
    calories: float


@dataclass(frozen=True)
class SeedSummary:
    readings: int
    treatments: int
    exercise_sessions: int
    heart_rate_samples: int
    avg_mgdl: float
    avg_mmol: float
    tir_pct: float
    low_pct: float
    high_pct: float
    very_high_pct: float
    cv_pct: float
    latest_mmol: float
    stats_days: int
    stats_count: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Seed a Strimma database with realistic CGM, treatment, and exercise data for screenshots."
    )
    parser.add_argument(
        "--db",
        type=Path,
        help="Existing SQLite database to modify in place. If omitted, the script pulls the DB from the emulator.",
    )
    parser.add_argument(
        "--install",
        action="store_true",
        help="Push the seeded database back into the emulator app sandbox after generation.",
    )
    parser.add_argument(
        "--device",
        help="ADB device serial. Uses the default connected device if omitted.",
    )
    parser.add_argument(
        "--package",
        default=DEFAULT_PACKAGE,
        help=f"Android package to pull from / install into. Default: {DEFAULT_PACKAGE}",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=DEFAULT_SEED,
        help=f"Deterministic RNG seed. Default: {DEFAULT_SEED}",
    )
    parser.add_argument(
        "--keep-days",
        type=int,
        default=DEFAULT_KEEP_DAYS,
        help=f"How many days of seeded data to keep. Default: {DEFAULT_KEEP_DAYS}",
    )
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=repo_root() / "tmp" / "screenshot-seed",
        help="Working directory for pulled DBs and backups.",
    )
    return parser.parse_args()


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def rounded_now() -> datetime:
    now = datetime.now().astimezone().replace(second=0, microsecond=0)
    return now - timedelta(minutes=now.minute % 5)


def adb_prefix(device: str | None) -> list[str]:
    command = ["adb"]
    if device:
        command.extend(["-s", device])
    return command


def run_command(command: list[str], *, stdout=None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, check=True, text=stdout is None, stdout=stdout)


def pull_database(package: str, device: str | None, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    run_command(adb_prefix(device) + ["shell", "am", "force-stop", package])
    with destination.open("wb") as handle:
        run_command(adb_prefix(device) + ["shell", "run-as", package, "cat", "databases/strimma.db"], stdout=handle)


def install_database(package: str, device: str | None, source: Path, backup_dir: Path) -> Path:
    backup_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    backup_path = backup_dir / f"strimma-before-seed-{timestamp}.db"
    pull_database(package, device, backup_path)

    run_command(adb_prefix(device) + ["push", str(source), ADB_TMP_DB])
    run_command(adb_prefix(device) + ["shell", "am", "force-stop", package])
    run_command(
        adb_prefix(device)
        + [
            "shell",
            "run-as",
            package,
            "rm",
            "-f",
            "databases/strimma.db",
            "databases/strimma.db-shm",
            "databases/strimma.db-wal",
        ]
    )
    run_command(
        adb_prefix(device)
        + ["shell", "run-as", package, "cp", ADB_TMP_DB, "databases/strimma.db"]
    )
    run_command(adb_prefix(device) + ["shell", "rm", "-f", ADB_TMP_DB])
    return backup_path


def ts_ms(value: datetime) -> int:
    return int(value.timestamp() * 1000)


def gamma_peak(minutes_since_event: float, peak_minutes: float, amplitude: float) -> float:
    if minutes_since_event <= 0:
        return 0.0
    x = minutes_since_event / peak_minutes
    if x > 7:
        return 0.0
    return amplitude * x * math.exp(1 - x)


def build_exercise_plans(keep_days: int) -> list[ExercisePlan]:
    plans = [
        ExercisePlan("Club Run", 5, 18, 30, 52, 141, 172, 9200, 470.0),
        ExercisePlan("Tempo Run", 2, 12, 15, 46, 154, 178, 8600, 505.0),
        ExercisePlan("Easy Run", 1, 18, 10, 61, 136, 163, 9800, 520.0),
    ]
    if keep_days >= 10:
        plans.insert(0, ExercisePlan("Long Run", 7, 11, 40, 73, 148, 176, 13400, 760.0))
    return plans


def seed_database(database_path: Path, *, seed: int, keep_days: int) -> SeedSummary:
    rng = random.Random(seed)
    now = rounded_now()
    start = (now - timedelta(days=keep_days)).replace(hour=0, minute=0)

    meal_events: list[dict[str, float | int | datetime]] = []
    correction_events: list[dict[str, float | int | datetime]] = []
    exercise_sessions: list[dict[str, str | int | float | datetime]] = []
    heart_rate_rows: list[tuple[str, int, int]] = []

    day_count = (now.date() - start.date()).days + 1
    stress_days = {index for index in range(day_count) if index % 4 == 2}
    overnight_low_days = {index for index in range(day_count) if index in {2, day_count - 3} and 0 <= index < day_count}

    for plan in build_exercise_plans(keep_days):
        session_date = now.date() - timedelta(days=plan.days_ago)
        start_dt = datetime(
            session_date.year,
            session_date.month,
            session_date.day,
            plan.hour,
            plan.minute,
            tzinfo=now.tzinfo,
        )
        if start_dt < start or start_dt > now:
            continue
        end_dt = start_dt + timedelta(minutes=plan.duration_min)
        session_id = f"seed-{session_date.isoformat()}-{plan.title.lower().replace(' ', '-')}"
        exercise_sessions.append(
            {
                "id": session_id,
                "type": RUNNING_EXERCISE_TYPE,
                "start": start_dt,
                "end": end_dt,
                "title": plan.title,
                "steps": plan.steps,
                "calories": plan.calories,
                "avg_hr": plan.avg_hr,
                "max_hr": plan.max_hr,
            }
        )
        for minute_index in range(plan.duration_min + 1):
            phase = minute_index / max(1, plan.duration_min)
            bpm = plan.avg_hr + 11 * math.sin(phase * math.pi) + rng.gauss(0, 5)
            bpm = min(plan.max_hr, max(118, round(bpm + (phase > 0.7) * 7)))
            if minute_index in {plan.duration_min // 2, int(plan.duration_min * 0.8)}:
                bpm = plan.max_hr
            heart_rate_rows.append((session_id, ts_ms(start_dt + timedelta(minutes=minute_index)), int(bpm)))

    day = start.date()
    while day <= now.date():
        day_index = (day - start.date()).days
        weekend = day.weekday() >= 5

        def meal_dt(hour: int, minute: int, jitter: int = 18) -> datetime:
            value = datetime(day.year, day.month, day.day, hour, minute, tzinfo=now.tzinfo)
            return value + timedelta(minutes=rng.randint(-jitter, jitter))

        breakfast = meal_dt(7 if not weekend else 8, 25)
        lunch = meal_dt(12 if not weekend else 13, 15)
        dinner = meal_dt(18, 35 if not weekend else 50)
        snack = meal_dt(15, 50, 25) if rng.random() < 0.45 else None
        late_snack = meal_dt(21, 20, 20) if rng.random() < 0.28 else None

        meal_specs = [
            (breakfast, rng.randint(24, 42), "breakfast"),
            (lunch, rng.randint(32, 66), "lunch"),
            (dinner, rng.randint(40, 84), "dinner"),
        ]
        if snack is not None:
            meal_specs.append((snack, rng.randint(14, 26), "snack"))
        if late_snack is not None:
            meal_specs.append((late_snack, rng.randint(10, 22), "snack"))
        meal_specs.sort(key=lambda item: item[0])

        for meal_time, carbs, meal_kind in meal_specs:
            if meal_time > now:
                continue
            excursion_scale = rng.uniform(0.9, 1.35)
            if meal_kind == "breakfast":
                excursion_scale *= rng.uniform(1.0, 1.18)
            if meal_kind == "dinner":
                excursion_scale *= rng.uniform(1.04, 1.28)
            if day_index in stress_days and meal_kind in {"lunch", "dinner"}:
                excursion_scale *= 1.3
            peak = 48 if meal_kind == "breakfast" else 66 if meal_kind == "lunch" else 82
            amp = carbs * (1.15 if meal_kind == "snack" else 1.55) * excursion_scale
            bolus = round(max(0.7, carbs / 11.0 * rng.uniform(0.85, 1.1)), 1)
            meal_events.append(
                {
                    "time": meal_time,
                    "carbs": carbs,
                    "amp": amp,
                    "peak": peak,
                    "bolus": bolus,
                }
            )
            if amp > 72 and rng.random() < 0.38:
                correction_time = meal_time + timedelta(minutes=rng.randint(105, 150))
                if correction_time < now:
                    correction_events.append(
                        {
                            "time": correction_time,
                            "drop": rng.uniform(14, 26),
                            "peak": rng.randint(80, 110),
                            "insulin": round(rng.uniform(0.8, 1.8), 1),
                        }
                    )

        if day_index in overnight_low_days:
            correction_events.append(
                {
                    "time": datetime(day.year, day.month, day.day, 2, 35, tzinfo=now.tzinfo),
                    "drop": rng.uniform(28, 42),
                    "peak": 75,
                    "insulin": round(rng.uniform(0.8, 1.3), 1),
                }
            )
        day += timedelta(days=1)

    readings: list[tuple[datetime, int]] = []
    noise = 0.0
    prev_value: float | None = None
    current = start
    while current <= now:
        minute_of_day = current.hour * 60 + current.minute
        day_index = (current.date() - start.date()).days

        circadian = 108 + 9 * math.sin((minute_of_day / 1440.0) * 2 * math.pi - 0.65)
        dawn = 18 * math.exp(-((minute_of_day - 390) / 115.0) ** 2)
        afternoon = -7 * math.exp(-((minute_of_day - 930) / 160.0) ** 2)
        bedtime = 6 * math.exp(-((minute_of_day - 1320) / 170.0) ** 2)
        day_bias = 8 * math.sin(day_index / 2.1) + rng.gauss(0, 3.0)
        value = circadian + dawn + afternoon + bedtime + day_bias

        for meal in meal_events:
            dt_minutes = (current - meal["time"]).total_seconds() / 60.0
            if 0 < dt_minutes < 360:
                value += gamma_peak(dt_minutes, float(meal["peak"]), float(meal["amp"]))

        for correction in correction_events:
            dt_minutes = (current - correction["time"]).total_seconds() / 60.0
            if 0 < dt_minutes < 300:
                value -= gamma_peak(dt_minutes, float(correction["peak"]), float(correction["drop"]))

        for session in exercise_sessions:
            dt_start = (current - session["start"]).total_seconds() / 60.0
            dt_end = (current - session["end"]).total_seconds() / 60.0
            duration = (session["end"] - session["start"]).total_seconds() / 60.0
            if -20 < dt_start < 0:
                value += max(0, 12 + dt_start * 0.45)
            elif 0 <= dt_start <= duration:
                frac = dt_start / max(1.0, duration)
                value -= 12 + 24 * frac + 8 * math.sin(frac * math.pi)
            elif 0 < dt_end < 260:
                peak_drop = 42 if session["title"] != "Long Run" else 58
                value -= gamma_peak(dt_end, 115, peak_drop)

        if day_index in stress_days and 10 * 60 <= minute_of_day <= 16 * 60:
            value += 28 * math.exp(-((minute_of_day - 13 * 60) / 120.0) ** 2)

        noise = noise * 0.76 + rng.gauss(0, 6.5)
        value += noise
        value = max(50, min(305, value))
        if prev_value is not None:
            value = 0.38 * prev_value + 0.62 * value
        prev_value = value
        readings.append((current, int(round(value))))
        current += timedelta(minutes=5)

    adjusted: list[tuple[datetime, int]] = []
    last_three_hours = now - timedelta(hours=3)
    for reading_time, sgv in readings:
        if reading_time > last_three_hours:
            minutes_since_window_start = (reading_time - last_three_hours).total_seconds() / 60.0
            target = (
                148
                + 18 * math.sin(minutes_since_window_start / 24.0)
                - 28 * math.exp(-((minutes_since_window_start - 65) / 18.0) ** 2)
                + 16 * math.exp(-((minutes_since_window_start - 130) / 22.0) ** 2)
            )
            sgv = int(round(0.45 * sgv + 0.55 * max(72, min(210, target))))
        adjusted.append((reading_time, sgv))
    readings = adjusted

    reading_rows: list[tuple[int, int, str, float | None, int]] = []
    for index, (reading_time, sgv) in enumerate(readings):
        if index == 0:
            delta = None
            direction = "Flat"
        else:
            previous = readings[index - 1][1]
            delta = round(sgv - previous, 1)
            change_15 = sgv - readings[max(0, index - 3)][1]
            if change_15 <= -20:
                direction = "DoubleDown"
            elif change_15 <= -10:
                direction = "SingleDown"
            elif change_15 <= -4:
                direction = "FortyFiveDown"
            elif change_15 < 4:
                direction = "Flat"
            elif change_15 < 10:
                direction = "FortyFiveUp"
            elif change_15 < 20:
                direction = "SingleUp"
            else:
                direction = "DoubleUp"
        reading_rows.append((ts_ms(reading_time), int(sgv), direction, delta, 1))

    fetched_at = ts_ms(now)
    treatment_rows: list[tuple[str, int, str, float | None, float | None, float | None, int | None, str, int]] = []
    for meal in meal_events:
        event_ts = ts_ms(meal["time"])
        treatment_rows.append(
            (
                f"meal-{event_ts}",
                event_ts,
                "Meal Bolus",
                float(meal["bolus"]),
                float(meal["carbs"]),
                None,
                None,
                "ScreenshotSeed",
                fetched_at,
            )
        )
    for correction in correction_events:
        event_ts = ts_ms(correction["time"])
        treatment_rows.append(
            (
                f"corr-{event_ts}",
                event_ts,
                "Correction Bolus",
                float(correction["insulin"]),
                None,
                None,
                None,
                "ScreenshotSeed",
                fetched_at,
            )
        )

    connection = sqlite3.connect(database_path)
    cursor = connection.cursor()
    cursor.execute("PRAGMA journal_mode=DELETE")
    cursor.execute("DELETE FROM heart_rate_samples")
    cursor.execute("DELETE FROM exercise_sessions")
    cursor.execute("DELETE FROM treatments")
    cursor.execute("DELETE FROM readings")
    cursor.executemany(
        "INSERT INTO readings (ts, sgv, direction, delta, pushed) VALUES (?, ?, ?, ?, ?)",
        reading_rows,
    )
    cursor.executemany(
        "INSERT INTO treatments (id, createdAt, eventType, insulin, carbs, basalRate, duration, enteredBy, fetchedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        treatment_rows,
    )
    cursor.executemany(
        "INSERT INTO exercise_sessions (id, type, startTime, endTime, title, totalSteps, activeCalories) VALUES (?, ?, ?, ?, ?, ?, ?)",
        [
            (
                str(session["id"]),
                int(session["type"]),
                ts_ms(session["start"]),
                ts_ms(session["end"]),
                str(session["title"]),
                int(session["steps"]),
                float(session["calories"]),
            )
            for session in exercise_sessions
        ],
    )
    cursor.executemany(
        "INSERT INTO heart_rate_samples (sessionId, time, bpm) VALUES (?, ?, ?)",
        heart_rate_rows,
    )
    connection.commit()
    connection.execute("VACUUM")
    connection.commit()

    stats_cutoff = now - timedelta(days=min(7, keep_days))
    stats_values = [sgv for reading_time, sgv in readings if reading_time >= stats_cutoff]
    avg = statistics.mean(stats_values)
    std_dev = statistics.pstdev(stats_values)
    count = len(stats_values)
    low = sum(1 for value in stats_values if value < 72)
    high = sum(1 for value in stats_values if value > 180)
    very_high = sum(1 for value in stats_values if value > 250)
    in_range = sum(1 for value in stats_values if 72 <= value <= 180)

    connection.close()
    return SeedSummary(
        readings=len(reading_rows),
        treatments=len(treatment_rows),
        exercise_sessions=len(exercise_sessions),
        heart_rate_samples=len(heart_rate_rows),
        avg_mgdl=round(avg, 1),
        avg_mmol=round(avg / 18.0182, 1),
        tir_pct=round(in_range / count * 100, 1),
        low_pct=round(low / count * 100, 1),
        high_pct=round(high / count * 100, 1),
        very_high_pct=round(very_high / count * 100, 1),
        cv_pct=round(std_dev / avg * 100, 1),
        latest_mmol=round(reading_rows[-1][1] / 18.0182, 1),
        stats_days=min(7, keep_days),
        stats_count=count,
    )


def main() -> int:
    args = parse_args()

    if args.keep_days < 7:
        print("--keep-days must be at least 7 so the Stats 7d view has enough data.", file=sys.stderr)
        return 2

    work_dir = args.work_dir.resolve()
    work_dir.mkdir(parents=True, exist_ok=True)

    database_path = args.db.resolve() if args.db else work_dir / "strimma.db"
    backup_path: Path | None = None

    try:
        if args.db is None:
            pull_database(args.package, args.device, database_path)

        summary = seed_database(database_path, seed=args.seed, keep_days=args.keep_days)

        if args.install:
            backup_path = install_database(args.package, args.device, database_path, work_dir / "backups")
    except subprocess.CalledProcessError as error:
        print(f"Command failed: {' '.join(error.cmd)}", file=sys.stderr)
        return error.returncode or 1
    except sqlite3.DatabaseError as error:
        print(f"SQLite error while seeding {database_path}: {error}", file=sys.stderr)
        return 1

    print(f"Seeded DB: {database_path}")
    if backup_path is not None:
        print(f"Backup before install: {backup_path}")
    print(
        f"Wrote {summary.readings} readings, {summary.treatments} treatments, "
        f"{summary.exercise_sessions} exercise sessions, {summary.heart_rate_samples} heart-rate samples"
    )
    print(
        f"{summary.stats_days}d stats: avg {summary.avg_mgdl} mg/dL ({summary.avg_mmol} mmol/L), "
        f"TIR {summary.tir_pct}%, low {summary.low_pct}%, high {summary.high_pct}%, "
        f"very high {summary.very_high_pct}%, CV {summary.cv_pct}%, "
        f"latest {summary.latest_mmol} mmol/L, readings {summary.stats_count}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())