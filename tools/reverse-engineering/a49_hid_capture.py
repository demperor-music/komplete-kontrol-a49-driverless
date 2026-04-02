#!/usr/bin/env python3
"""
Capture raw HID reports from a Native Instruments Komplete Kontrol A49 (or any
USB HID device matching a VID:PID), and interactively label newly-seen reports.

This is meant for reverse-engineering proprietary button/transport traffic on
Linux without needing extra Python packages.

What it does:
- finds matching /dev/hidraw* devices via /sys/class/hidraw
- reads raw HID input reports
- logs every report to a JSONL file
- prompts you to name each newly-seen non-idle report
- stores labels in a JSON database so you are not asked twice

Typical workflow:
  1. Start the script.
  2. Press one control at a time.
  3. When prompted, type a label like PLAY, STOP, BROWSE, SHIFT+ENCODER_RIGHT.
  4. For combinations, hold the buttons together and name the combo when asked.
  5. Send me the resulting labels JSON and/or raw log.

If the A49 does not expose hidraw on your machine, this script will tell you so.
At that point the next step is bus-level capture (usbmon), not this script.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import select
import signal
import sys
import time
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

DEFAULT_VID = "17cc"
DEFAULT_PID = "1740"
DEFAULT_DB = "a49_labels.json"
DEFAULT_RAW_LOG = "a49_raw_log.jsonl"


@dataclass
class HidrawDevice:
    node: str
    hidraw_sysfs: str
    usb_sysfs: Optional[str]
    interface_sysfs: Optional[str]
    manufacturer: Optional[str]
    product: Optional[str]
    serial: Optional[str]
    interface_number: Optional[str]
    busnum: Optional[str]
    devnum: Optional[str]


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def read_text(path: pathlib.Path) -> Optional[str]:
    try:
        return path.read_text(encoding="utf-8", errors="ignore").strip()
    except Exception:
        return None


def fmt_hex(data: bytes) -> str:
    return " ".join(f"{b:02X}" for b in data)


def parse_hex(s: str) -> bytes:
    cleaned = s.replace(" ", "").replace(":", "")
    return bytes.fromhex(cleaned)


def is_all_zero(data: bytes) -> bool:
    return len(data) > 0 and all(b == 0 for b in data)


def summarize_data(data: bytes) -> str:
    return f"len={len(data):02d} hex=[{fmt_hex(data)}]"


def find_nearest_with_files(start: pathlib.Path, filenames: Tuple[str, ...]) -> Optional[pathlib.Path]:
    for p in [start] + list(start.parents):
        if all((p / name).exists() for name in filenames):
            return p
    return None


def find_matching_hidraw_nodes(vid: str, pid: str) -> List[HidrawDevice]:
    base = pathlib.Path("/sys/class/hidraw")
    if not base.exists():
        return []

    matches: List[HidrawDevice] = []
    for hid in sorted(base.glob("hidraw*")):
        dev_link = hid / "device"
        try:
            resolved = dev_link.resolve()
        except Exception:
            continue

        usb_dev = None
        for p in [resolved] + list(resolved.parents):
            idv = p / "idVendor"
            idp = p / "idProduct"
            if idv.exists() and idp.exists():
                pv = read_text(idv)
                pp = read_text(idp)
                if pv and pp and pv.lower() == vid.lower() and pp.lower() == pid.lower():
                    usb_dev = p
                    break

        if usb_dev is None:
            continue

        interface_dir = find_nearest_with_files(resolved, ("bInterfaceNumber",))
        matches.append(
            HidrawDevice(
                node=f"/dev/{hid.name}",
                hidraw_sysfs=str(hid),
                usb_sysfs=str(usb_dev),
                interface_sysfs=str(interface_dir) if interface_dir else None,
                manufacturer=read_text(usb_dev / "manufacturer"),
                product=read_text(usb_dev / "product"),
                serial=read_text(usb_dev / "serial"),
                interface_number=read_text(interface_dir / "bInterfaceNumber") if interface_dir else None,
                busnum=read_text(usb_dev / "busnum"),
                devnum=read_text(usb_dev / "devnum"),
            )
        )

    return matches


class LabelDB:
    def __init__(self, path: str, vid: str, pid: str) -> None:
        self.path = path
        self.vid = vid.lower()
        self.pid = pid.lower()
        self.data = {
            "meta": {
                "vid": self.vid,
                "pid": self.pid,
                "created_at": utc_now(),
                "updated_at": utc_now(),
            },
            "labeled_reports": [],
            "ignored_reports": [],
        }
        self.index: Dict[Tuple[str, str], dict] = {}
        self.ignored: Dict[Tuple[str, str], dict] = {}
        self.load()

    def load(self) -> None:
        if not os.path.exists(self.path):
            return
        with open(self.path, "r", encoding="utf-8") as f:
            self.data = json.load(f)
        self.index = {}
        self.ignored = {}
        for item in self.data.get("labeled_reports", []):
            key = (item["source"], item["hex"])
            self.index[key] = item
        for item in self.data.get("ignored_reports", []):
            key = (item["source"], item["hex"])
            self.ignored[key] = item

    def save(self) -> None:
        self.data.setdefault("meta", {})["updated_at"] = utc_now()
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.data, f, indent=2, sort_keys=False)
        os.replace(tmp, self.path)

    def get_label(self, source: str, hex_string: str) -> Optional[str]:
        entry = self.index.get((source, hex_string))
        return entry.get("label") if entry else None

    def is_ignored(self, source: str, hex_string: str) -> bool:
        return (source, hex_string) in self.ignored

    def touch_seen(self, source: str, hex_string: str) -> None:
        entry = self.index.get((source, hex_string))
        if entry:
            entry["seen_count"] = entry.get("seen_count", 0) + 1
            entry["last_seen_at"] = utc_now()

    def add_label(self, source: str, hex_string: str, label: str) -> None:
        key = (source, hex_string)
        existing = self.index.get(key)
        if existing:
            existing["label"] = label
            existing["seen_count"] = existing.get("seen_count", 0) + 1
            existing["last_seen_at"] = utc_now()
        else:
            entry = {
                "source": source,
                "hex": hex_string,
                "label": label,
                "first_seen_at": utc_now(),
                "last_seen_at": utc_now(),
                "seen_count": 1,
            }
            self.data.setdefault("labeled_reports", []).append(entry)
            self.index[key] = entry
        self.save()

    def add_ignore(self, source: str, hex_string: str) -> None:
        key = (source, hex_string)
        if key not in self.ignored:
            entry = {
                "source": source,
                "hex": hex_string,
                "ignored_at": utc_now(),
            }
            self.data.setdefault("ignored_reports", []).append(entry)
            self.ignored[key] = entry
            self.save()


class RawLogger:
    def __init__(self, path: str) -> None:
        self.path = path

    def write(self, payload: dict) -> None:
        with open(self.path, "a", encoding="utf-8") as f:
            f.write(json.dumps(payload, sort_keys=False) + "\n")


class CaptureSession:
    def __init__(
        self,
        devices: List[HidrawDevice],
        db: LabelDB,
        raw_logger: RawLogger,
        read_size: int = 256,
        dedupe_window: float = 0.15,
        reprompt_snooze: float = 1.5,
        ignore_zero: bool = True,
        nosave: bool = False,
    ) -> None:
        self.devices = devices
        self.db = db
        self.raw_logger = raw_logger
        self.read_size = read_size
        self.dedupe_window = dedupe_window
        self.reprompt_snooze = reprompt_snooze
        self.ignore_zero = ignore_zero
        self.nosave = nosave
        self.stop_requested = False
        self.fd_map: Dict[int, HidrawDevice] = {}
        self.last_seen: Dict[str, Tuple[str, float]] = {}
        self.unlabeled_snooze: Dict[Tuple[str, str], float] = {}

    def request_stop(self, *_args) -> None:
        self.stop_requested = True

    def open_all(self) -> None:
        errors: List[str] = []
        for dev in self.devices:
            try:
                fd = os.open(dev.node, os.O_RDONLY | os.O_NONBLOCK)
                self.fd_map[fd] = dev
            except PermissionError:
                errors.append(
                    f"Permission denied opening {dev.node}. Try running with sudo, or temporarily grant read access."
                )
            except FileNotFoundError:
                errors.append(f"Missing node: {dev.node}")
            except OSError as e:
                errors.append(f"Could not open {dev.node}: {e}")
        if not self.fd_map:
            for line in errors:
                print(line, file=sys.stderr)
            raise SystemExit(1)
        for line in errors:
            print(line, file=sys.stderr)

    def close_all(self) -> None:
        for fd in list(self.fd_map):
            try:
                os.close(fd)
            except OSError:
                pass
            self.fd_map.pop(fd, None)

    def prompt_for_label(self, source: str, data: bytes) -> Optional[str]:
        hex_string = fmt_hex(data)
        while True:
            print()
            print("New report detected")
            print(f"  source : {source}")
            print(f"  report : {summarize_data(data)}")
            print("  name it with the control or combo, e.g. PLAY, STOP, SHIFT+BROWSE")
            response = input("Label [Enter=skip, !=ignore forever, q=quit]: ").strip()
            if response.lower() == "q":
                self.stop_requested = True
                return None
            if response == "":
                self.unlabeled_snooze[(source, hex_string)] = time.time() + self.reprompt_snooze
                return None
            if response == "!":
                self.db.add_ignore(source, hex_string)
                print(f"Ignored {hex_string} from {source}")
                return None
            return response

    def handle_packet(self, dev: HidrawDevice, data: bytes) -> None:
        now = time.time()
        source = dev.node
        hex_string = fmt_hex(data)

        self.raw_logger.write(
            {
                "timestamp_utc": utc_now(),
                "source": source,
                "busnum": dev.busnum,
                "devnum": dev.devnum,
                "interface_number": dev.interface_number,
                "hex": hex_string,
                "bytes": list(data),
                "known_label": self.db.get_label(source, hex_string),
            }
        )

        if self.ignore_zero and is_all_zero(data):
            return
        if self.db.is_ignored(source, hex_string):
            return

        prev = self.last_seen.get(source)
        if prev and prev[0] == hex_string and (now - prev[1]) < self.dedupe_window:
            return
        self.last_seen[source] = (hex_string, now)

        label = self.db.get_label(source, hex_string)
        if label is not None:
            self.db.touch_seen(source, hex_string)
            print(f"[{source}] {label:<24} {hex_string}")
            return

        snooze_until = self.unlabeled_snooze.get((source, hex_string), 0.0)
        if now < snooze_until:
            return
        
        if self.nosave:
            print(f"[{source}] {hex_string}")
        else:
            label = self.prompt_for_label(source, data)
            if label:
                self.db.add_label(source, hex_string, label)
                print(f"Saved: {label} -> {hex_string}")

    def run(self) -> None:
        signal.signal(signal.SIGINT, self.request_stop)
        signal.signal(signal.SIGTERM, self.request_stop)
        self.open_all()
        try:
            print("Listening for raw HID reports.")
            print("Press controls on the A49 one at a time.")
            print("For combinations, hold them together and give the combo a combined name.")
            print("Press Ctrl+C or enter q at a prompt to stop.")
            print()
            while not self.stop_requested:
                ready, _, _ = select.select(list(self.fd_map.keys()), [], [], 0.5)
                for fd in ready:
                    dev = self.fd_map[fd]
                    while True:
                        try:
                            data = os.read(fd, self.read_size)
                        except BlockingIOError:
                            break
                        except OSError as e:
                            print(f"Read error on {dev.node}: {e}", file=sys.stderr)
                            break
                        if not data:
                            break
                        self.handle_packet(dev, data)
        finally:
            self.close_all()
            self.db.save()
            print()
            print(f"Saved labels to: {self.db.path}")
            print(f"Saved raw log to: {self.raw_logger.path}")


def print_devices(devices: List[HidrawDevice]) -> None:
    if not devices:
        print("No matching hidraw devices found.")
        return
    for i, dev in enumerate(devices, start=1):
        print(f"[{i}] {dev.node}")
        print(f"    product      : {dev.product or '-'}")
        print(f"    manufacturer : {dev.manufacturer or '-'}")
        print(f"    serial       : {dev.serial or '-'}")
        print(f"    bus/dev      : {dev.busnum or '?'} / {dev.devnum or '?'}")
        print(f"    interface    : {dev.interface_number or '-'}")
        print(f"    usb sysfs    : {dev.usb_sysfs or '-'}")
        print(f"    hidraw sysfs : {dev.hidraw_sysfs}")
        print()


def choose_devices(devices: List[HidrawDevice], explicit_device: Optional[str]) -> List[HidrawDevice]:
    if explicit_device:
        selected = [d for d in devices if d.node == explicit_device]
        if not selected:
            print(f"Requested device {explicit_device} was not found among matching hidraw nodes.", file=sys.stderr)
            raise SystemExit(2)
        return selected
    return devices


def main() -> None:
    parser = argparse.ArgumentParser(description="Capture and label raw HID reports from the A49.")
    parser.add_argument("--vid", default=DEFAULT_VID, help=f"USB vendor ID, default {DEFAULT_VID}")
    parser.add_argument("--pid", default=DEFAULT_PID, help=f"USB product ID, default {DEFAULT_PID}")
    parser.add_argument("--device", help="Specific /dev/hidrawX to open if multiple are found")
    parser.add_argument("--list", action="store_true", help="List matching hidraw nodes and exit")
    parser.add_argument("--db", default=DEFAULT_DB, help=f"Label database JSON path, default {DEFAULT_DB}")
    parser.add_argument("--raw-log", default=DEFAULT_RAW_LOG, help=f"Raw JSONL log path, default {DEFAULT_RAW_LOG}")
    parser.add_argument("--read-size", type=int, default=256, help="Bytes to read per report, default 256")
    parser.add_argument(
        "--dedupe-window",
        type=float,
        default=0.15,
        help="Suppress identical repeats from the same hidraw node inside this many seconds",
    )
    parser.add_argument(
        "--reprompt-snooze",
        type=float,
        default=1.5,
        help="If you skip a report with Enter, wait this many seconds before asking again for the same report",
    )
    parser.add_argument(
        "--keep-zero",
        action="store_true",
        help="Do not ignore all-zero reports for prompting",
    )
    parser.add_argument("--no-save", action="store_true", help="Don't label anything, just read HID")

    args = parser.parse_args()

    devices = find_matching_hidraw_nodes(args.vid, args.pid)
    if args.list:
        print_devices(devices)
        if not devices:
            print(
                "No hidraw nodes matched that VID:PID. If the missing controls are not exposed through HID on your system,"
            )
            print("the next step is usbmon/Wireshark capture rather than this script.")
        return

    if not devices:
        print("No matching hidraw nodes were found for that VID:PID.", file=sys.stderr)
        print("This usually means one of these:", file=sys.stderr)
        print("  - the device is not exposing the missing controls through HID", file=sys.stderr)
        print("  - the hidraw module is not loaded", file=sys.stderr)
        print("  - you need a lower-level USB capture path instead", file=sys.stderr)
        print(file=sys.stderr)
        print("Check /sys/class/hidraw and run with --list first.", file=sys.stderr)
        raise SystemExit(1)

    selected = choose_devices(devices, args.device)
    print_devices(selected)

    db = LabelDB(args.db, args.vid, args.pid)
    raw_logger = RawLogger(args.raw_log)
    session = CaptureSession(
        devices=selected,
        db=db,
        raw_logger=raw_logger,
        read_size=args.read_size,
        dedupe_window=args.dedupe_window,
        reprompt_snooze=args.reprompt_snooze,
        ignore_zero=not args.keep_zero,
        nosave=args.no_save,
    )
    session.run()


if __name__ == "__main__":
    main()
