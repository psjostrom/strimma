#!/usr/bin/env python3

from __future__ import annotations

import argparse
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

DEFAULT_PACKAGE = "com.psjostrom.strimma.debug"
DEFAULT_ACTIVITY = "com.psjostrom.strimma.debug/com.psjostrom.strimma.ui.MainActivity"
DEFAULT_WAIT_MS = 1200
DEFAULT_SETTLE_MS = 1500
SETTINGS_DATASTORE_PATH = "files/datastore/settings.preferences_pb"
ADB_TMP_SETTINGS = "/data/local/tmp/strimma-settings.preferences_pb"


@dataclass(frozen=True)
class TapStep:
    labels: tuple[str, ...]
    region: str = "any"


TARGETS: dict[str, tuple[TapStep, ...]] = {
    "current": (),
    "bg": (TapStep(("BG",), "bottom"),),
    "exercise": (TapStep(("Exercise", "Träning"), "bottom"),),
    "stats": (
        TapStep(("Stats", "Statistik"), "bottom"),
        TapStep(("Metrics", "Mätvärden"), "upper"),
    ),
    "stats-metrics": (
        TapStep(("Stats", "Statistik"), "bottom"),
        TapStep(("Metrics", "Mätvärden"), "upper"),
    ),
    "stats-agp": (
        TapStep(("Stats", "Statistik"), "bottom"),
        TapStep(("AGP",), "upper"),
    ),
    "stats-meals": (
        TapStep(("Stats", "Statistik"), "bottom"),
        TapStep(("Meals", "Måltider"), "upper"),
    ),
    "settings": (TapStep(("Settings", "Inställningar"), "bottom"),),
}

ANR_TITLE_FRAGMENTS = (
    "isn't responding",
    "svarar inte",
)
ANR_WAIT_LABELS = ("Wait", "Vänta")
BACKGROUND_DIALOG_TITLE = "let app always run in background?"
BACKGROUND_DIALOG_DISMISS_LABELS = ("Deny", "Neka")
MAIN_NAV_MARKERS = (
    ("BG",),
    ("Exercise", "Träning"),
    ("Stats", "Statistik"),
    ("Settings", "Inställningar"),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Launch Strimma, navigate to a named screen, and save a screenshot PNG."
    )
    parser.add_argument(
        "--target",
        choices=sorted(TARGETS.keys()),
        default="current",
        help="Named capture target to navigate to before saving the screenshot.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        help="Output PNG path. Defaults to tmp/captured-screenshots/<target>.png.",
    )
    parser.add_argument(
        "--device",
        help="ADB device serial. Uses the default connected device if omitted.",
    )
    parser.add_argument(
        "--package",
        default=DEFAULT_PACKAGE,
        help=f"Android package to launch. Default: {DEFAULT_PACKAGE}",
    )
    parser.add_argument(
        "--activity",
        default=DEFAULT_ACTIVITY,
        help=f"Full activity component to launch. Default: {DEFAULT_ACTIVITY}",
    )
    parser.add_argument(
        "--wait-ms",
        type=int,
        default=DEFAULT_WAIT_MS,
        help=f"Delay after each navigation tap in milliseconds. Default: {DEFAULT_WAIT_MS}",
    )
    parser.add_argument(
        "--settle-ms",
        type=int,
        default=DEFAULT_SETTLE_MS,
        help=f"Final delay before capture in milliseconds. Default: {DEFAULT_SETTLE_MS}",
    )
    parser.add_argument(
        "--launch",
        action=argparse.BooleanOptionalAction,
        default=None,
        help="Whether to launch MainActivity before navigation. Defaults to yes for named targets and no for current.",
    )
    parser.add_argument(
        "--complete-setup",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Patch setup_completed/setup_step in DataStore before launching so a fresh emulator skips onboarding.",
    )
    return parser.parse_args()


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def adb_prefix(device: str | None) -> list[str]:
    prefix = ["adb"]
    if device:
        prefix.extend(["-s", device])
    return prefix


def run_adb(
    device: str | None,
    *args: str,
    capture_output: bool = True,
    stdout=None,
    text: bool = True,
) -> subprocess.CompletedProcess:
    return subprocess.run(
        adb_prefix(device) + list(args),
        check=True,
        capture_output=capture_output if stdout is None else False,
        stdout=stdout,
        text=text if stdout is None else False,
    )


def wait_for_device_boot(device: str | None) -> None:
    run_adb(device, "wait-for-device", capture_output=False)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        result = run_adb(device, "shell", "getprop", "sys.boot_completed")
        if result.stdout.strip() == "1":
            return
        time.sleep(1)
    raise RuntimeError("Timed out waiting for Android to finish booting.")


def read_varint(buf: bytes, pos: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        byte = buf[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            return result, pos
        shift += 7


def write_varint(value: int) -> bytes:
    output = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            output.append(byte | 0x80)
        else:
            output.append(byte)
            return bytes(output)


def zigzag_encode(value: int) -> int:
    return (value << 1) ^ (value >> 63)


def zigzag_decode(value: int) -> int:
    return (value >> 1) ^ -(value & 1)


def parse_value(buf: bytes) -> tuple[int, object]:
    pos = 0
    value_type = 0
    value: object = b""
    while pos < len(buf):
        tag, pos = read_varint(buf, pos)
        field = tag >> 3
        wire = tag & 0x7
        if wire == 0:
            raw, pos = read_varint(buf, pos)
            value = zigzag_decode(raw) if field in (3, 4) else bool(raw) if field == 1 else raw
        elif wire == 1:
            value = struct.unpack("<d", buf[pos:pos + 8])[0]
            pos += 8
        elif wire == 2:
            length, pos = read_varint(buf, pos)
            raw = buf[pos:pos + length]
            pos += length
            if field == 5:
                value = raw.decode("utf-8")
            elif field == 6:
                strings: list[str] = []
                inner_pos = 0
                while inner_pos < len(raw):
                    inner_tag, inner_pos = read_varint(raw, inner_pos)
                    inner_field = inner_tag >> 3
                    inner_length, inner_pos = read_varint(raw, inner_pos)
                    inner_raw = raw[inner_pos:inner_pos + inner_length]
                    inner_pos += inner_length
                    if inner_field == 1:
                        strings.append(inner_raw.decode("utf-8"))
                value = strings
            else:
                value = raw
        elif wire == 5:
            value = struct.unpack("<f", buf[pos:pos + 4])[0]
            pos += 4
        else:
            raise RuntimeError(f"Unsupported wire type in DataStore preferences: {wire}")
        value_type = field
    return value_type, value


def encode_value(value_type: int, value: object) -> bytes:
    if value_type == 1:
        return write_varint((1 << 3) | 0) + write_varint(1 if value else 0)
    if value_type == 2:
        return write_varint((2 << 3) | 5) + struct.pack("<f", float(value))
    if value_type == 3:
        return write_varint((3 << 3) | 0) + write_varint(zigzag_encode(int(value)))
    if value_type == 4:
        return write_varint((4 << 3) | 0) + write_varint(zigzag_encode(int(value)))
    if value_type == 5:
        raw = str(value).encode("utf-8")
        return write_varint((5 << 3) | 2) + write_varint(len(raw)) + raw
    if value_type == 6:
        inner = bytearray()
        for item in value:
            raw = str(item).encode("utf-8")
            inner += write_varint((1 << 3) | 2) + write_varint(len(raw)) + raw
        return write_varint((6 << 3) | 2) + write_varint(len(inner)) + bytes(inner)
    if value_type == 7:
        return write_varint((7 << 3) | 1) + struct.pack("<d", float(value))
    if value_type == 8:
        raw = bytes(value)
        return write_varint((8 << 3) | 2) + write_varint(len(raw)) + raw
    raise RuntimeError(f"Unsupported DataStore value type: {value_type}")


def parse_preferences(data: bytes) -> list[tuple[str, int, object]]:
    entries: list[tuple[str, int, object]] = []
    pos = 0
    while pos < len(data):
        tag, pos = read_varint(data, pos)
        field = tag >> 3
        wire = tag & 0x7
        if field != 1 or wire != 2:
            raise RuntimeError(f"Unexpected preferences tag: field={field}, wire={wire}")
        entry_length, pos = read_varint(data, pos)
        entry_buf = data[pos:pos + entry_length]
        pos += entry_length

        entry_pos = 0
        key = ""
        value_buf = b""
        while entry_pos < len(entry_buf):
            entry_tag, entry_pos = read_varint(entry_buf, entry_pos)
            entry_field = entry_tag >> 3
            entry_wire = entry_tag & 0x7
            if entry_wire != 2:
                raise RuntimeError(f"Unexpected entry wire type: {entry_wire}")
            item_length, entry_pos = read_varint(entry_buf, entry_pos)
            item = entry_buf[entry_pos:entry_pos + item_length]
            entry_pos += item_length
            if entry_field == 1:
                key = item.decode("utf-8")
            elif entry_field == 2:
                value_buf = item
        entries.append((key, *parse_value(value_buf)))
    return entries


def encode_preferences(entries: list[tuple[str, int, object]]) -> bytes:
    output = bytearray()
    for key, value_type, value in entries:
        key_raw = key.encode("utf-8")
        value_raw = encode_value(value_type, value)
        entry_raw = bytearray()
        entry_raw += write_varint((1 << 3) | 2) + write_varint(len(key_raw)) + key_raw
        entry_raw += write_varint((2 << 3) | 2) + write_varint(len(value_raw)) + value_raw
        output += write_varint((1 << 3) | 2) + write_varint(len(entry_raw)) + bytes(entry_raw)
    return bytes(output)


def patch_setup_state(device: str | None, package: str, work_dir: Path) -> None:
    work_dir.mkdir(parents=True, exist_ok=True)
    settings_path = work_dir / "settings.preferences_pb"
    with settings_path.open("wb") as handle:
        run_adb(device, "shell", "run-as", package, "cat", SETTINGS_DATASTORE_PATH, capture_output=False, stdout=handle, text=False)

    data = settings_path.read_bytes()
    entries = parse_preferences(data) if data else []
    updated = {key: (value_type, value) for key, value_type, value in entries}
    updated["setup_completed"] = (1, True)
    updated["setup_step"] = (3, 4)
    new_entries = [(key, value_type, value) for key, (value_type, value) in updated.items()]
    settings_path.write_bytes(encode_preferences(new_entries))

    run_adb(device, "push", str(settings_path), ADB_TMP_SETTINGS, capture_output=False)
    run_adb(device, "shell", "am", "force-stop", package, capture_output=False)
    run_adb(device, "shell", "run-as", package, "cp", ADB_TMP_SETTINGS, SETTINGS_DATASTORE_PATH, capture_output=False)
    run_adb(device, "shell", "rm", "-f", ADB_TMP_SETTINGS, capture_output=False)


def ensure_package_installed(device: str | None, package: str) -> None:
    try:
        result = run_adb(device, "shell", "pm", "path", package)
    except subprocess.CalledProcessError as error:
        stderr = error.stderr.strip() if isinstance(error.stderr, str) else ""
        if "package" in stderr.casefold() and "not found" in stderr.casefold():
            raise RuntimeError(f"Package {package} is not installed on the connected device.") from error
        raise
    if not result.stdout.strip():
        raise RuntimeError(f"Package {package} is not installed on the connected device.")


def wake_and_launch(device: str | None, activity: str) -> None:
    run_adb(device, "shell", "input", "keyevent", "KEYCODE_WAKEUP", capture_output=False)
    run_adb(device, "shell", "wm", "dismiss-keyguard", capture_output=False)
    run_adb(device, "shell", "am", "start", "-n", activity, capture_output=False)


def normalize(text: str) -> str:
    return " ".join(text.split()).strip().casefold()


def extract_xml(raw: str) -> str:
    start = raw.find("<?xml")
    end = raw.rfind("</hierarchy>")
    if start == -1 or end == -1:
        raise RuntimeError("Could not parse UI hierarchy dump from adb output.")
    return raw[start : end + len("</hierarchy>")]


def dump_ui(device: str | None) -> ET.Element:
    last_error: Exception | None = None
    for _ in range(6):
        try:
            raw = run_adb(device, "exec-out", "uiautomator", "dump", "/dev/tty").stdout
            return ET.fromstring(extract_xml(raw))
        except (RuntimeError, ET.ParseError, subprocess.CalledProcessError) as error:
            last_error = error
            time.sleep(0.5)
    if last_error is None:
        raise RuntimeError("Could not read UI hierarchy from adb.")
    raise RuntimeError(str(last_error))


def parse_bounds(bounds: str) -> tuple[int, int, int, int]:
    left_top, right_bottom = bounds.split("][")
    left, top = left_top.lstrip("[").split(",")
    right, bottom = right_bottom.rstrip("]").split(",")
    return int(left), int(top), int(right), int(bottom)


def center_of(bounds: tuple[int, int, int, int]) -> tuple[int, int]:
    left, top, right, bottom = bounds
    return (left + right) // 2, (top + bottom) // 2


def region_matches(region: str, center_y: int, screen_height: int) -> bool:
    if region == "bottom":
        return center_y >= int(screen_height * 0.72)
    if region == "upper":
        return center_y <= int(screen_height * 0.62)
    return True


def pick_node(root: ET.Element, labels: tuple[str, ...], region: str) -> tuple[int, int] | None:
    target_labels = {normalize(label) for label in labels}
    screen_bounds = parse_bounds(root.attrib.get("bounds", "[0,0][1280,2856]"))
    screen_height = screen_bounds[3] - screen_bounds[1]
    candidates: list[tuple[int, int, int, int]] = []

    for node in root.iter("node"):
        text = normalize(node.attrib.get("text", ""))
        desc = normalize(node.attrib.get("content-desc", ""))
        if text not in target_labels and desc not in target_labels:
            continue
        bounds_text = node.attrib.get("bounds")
        if not bounds_text:
            continue
        bounds = parse_bounds(bounds_text)
        center_x, center_y = center_of(bounds)
        if region_matches(region, center_y, screen_height):
            area = (bounds[2] - bounds[0]) * (bounds[3] - bounds[1])
            candidates.append((area, center_y, center_x, center_y))

    if not candidates and region != "any":
        return pick_node(root, labels, "any")
    if not candidates:
        return None

    if region == "bottom":
        _, _, x, y = max(candidates, key=lambda item: item[1])
    elif region == "upper":
        _, _, x, y = min(candidates, key=lambda item: item[1])
    else:
        _, _, x, y = min(candidates, key=lambda item: item[0])
    return x, y


def tap(device: str | None, x: int, y: int) -> None:
    run_adb(device, "shell", "input", "tap", str(x), str(y), capture_output=False)


def dismiss_anr_if_present(device: str | None, root: ET.Element) -> bool:
    for node in root.iter("node"):
        text = normalize(node.attrib.get("text", ""))
        if any(fragment in text for fragment in ANR_TITLE_FRAGMENTS):
            wait_center = pick_node(root, ANR_WAIT_LABELS, "any")
            if wait_center is None:
                raise RuntimeError("ANR dialog detected, but the Wait button could not be found.")
            tap(device, *wait_center)
            return True
    return False


def root_packages(root: ET.Element) -> set[str]:
    packages = set()
    for node in root.iter("node"):
        package = node.attrib.get("package", "")
        if package:
            packages.add(package)
    return packages


def dismiss_background_dialog_if_present(device: str | None, root: ET.Element) -> bool:
    packages = root_packages(root)
    if "com.android.settings" not in packages:
        return False
    for node in root.iter("node"):
        text = normalize(node.attrib.get("text", ""))
        if text == BACKGROUND_DIALOG_TITLE:
            deny_center = pick_node(root, BACKGROUND_DIALOG_DISMISS_LABELS, "any")
            if deny_center is None:
                raise RuntimeError("Background-run dialog detected, but the Deny button could not be found.")
            tap(device, *deny_center)
            return True
    return False


def main_nav_visible(root: ET.Element) -> bool:
    return any(pick_node(root, labels, "bottom") is not None for labels in MAIN_NAV_MARKERS)


def tap_step(device: str | None, step: TapStep, wait_ms: int) -> None:
    for _ in range(4):
        root = dump_ui(device)
        if dismiss_background_dialog_if_present(device, root):
            time.sleep(wait_ms / 1000.0)
            continue
        if dismiss_anr_if_present(device, root):
            time.sleep(wait_ms / 1000.0)
            continue
        center = pick_node(root, step.labels, step.region)
        if center is not None:
            tap(device, *center)
            time.sleep(wait_ms / 1000.0)
            return
        time.sleep(0.4)
    joined = ", ".join(step.labels)
    raise RuntimeError(f"Could not find UI label(s): {joined}")


def ensure_app_foregrounded(device: str | None, package: str, activity: str, wait_ms: int) -> None:
    for _ in range(8):
        root = dump_ui(device)
        if dismiss_background_dialog_if_present(device, root):
            time.sleep(wait_ms / 1000.0)
            wake_and_launch(device, activity)
            time.sleep(wait_ms / 1000.0)
            continue
        packages = root_packages(root)
        if package in packages:
            return
        wake_and_launch(device, activity)
        time.sleep(wait_ms / 1000.0)


def capture_screenshot(device: str | None, output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("wb") as handle:
        run_adb(device, "exec-out", "screencap", "-p", capture_output=False, stdout=handle, text=False)


def default_output_path(target: str) -> Path:
    return repo_root() / "tmp" / "captured-screenshots" / f"{target}.png"


def main() -> int:
    args = parse_args()
    wait_for_device_boot(args.device)
    ensure_package_installed(args.device, args.package)

    should_launch = args.launch if args.launch is not None else args.target != "current"
    if should_launch and args.complete_setup:
        patch_setup_state(args.device, args.package, repo_root() / "tmp" / "captured-screenshots")
    if should_launch:
        wake_and_launch(args.device, args.activity)
        time.sleep(max(args.wait_ms, 1500) / 1000.0)
        ensure_app_foregrounded(args.device, args.package, args.activity, args.wait_ms)

    for step in TARGETS[args.target]:
        tap_step(args.device, step, args.wait_ms)

    for _ in range(3):
        root = dump_ui(args.device)
        if not dismiss_anr_if_present(args.device, root):
            break
        time.sleep(args.wait_ms / 1000.0)

    time.sleep(args.settle_ms / 1000.0)
    output_path = args.output.resolve() if args.output else default_output_path(args.target)
    capture_screenshot(args.device, output_path)
    print(output_path)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        raise SystemExit(1)