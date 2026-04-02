#!/usr/bin/env python3
"""
Guided HID probe tool for Native Instruments Komplete Kontrol A49 on Linux.

This is *not* a literal exhaustive fuzzer. Exhaustive search over HID report
space is astronomical. Instead, this tool performs staged, structured probing:

- Stage 1: discover likely writable report families (method/report-id/length)
- Stage 2: bitwalk selected payload positions
- Stage 3: byte-sweep one selected payload position
- Passive logging of any input reports received after each transmitted packet
- Interactive annotation of visible behavior, written to JSONL + CSV

No third-party Python packages are required.

IMPORTANT:
- Disconnect the keyboard from your DAW/NI software while probing.
- Save any work first.
- Keep a hand near the USB cable in case the device wedges.
- Prefer Stage 1 before Stage 2/3.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import errno
import json
import os
import pathlib
import re
import select
import signal
import sys
import time
from dataclasses import dataclass, asdict
from typing import Iterable, List, Optional, Sequence, Tuple
import fcntl

DEFAULT_VID = "17cc"
DEFAULT_PID = "1740"
DEFAULT_WINDOW = 0.35
DEFAULT_SETTLE = 0.20
DEFAULT_JSONL = "a49_probe_log.jsonl"
DEFAULT_CSV = "a49_probe_summary.csv"

# ioctl helpers from asm-generic/ioctl.h
_IOC_NRBITS = 8
_IOC_TYPEBITS = 8
_IOC_SIZEBITS = 14
_IOC_DIRBITS = 2

_IOC_NRSHIFT = 0
_IOC_TYPESHIFT = _IOC_NRSHIFT + _IOC_NRBITS
_IOC_SIZESHIFT = _IOC_TYPESHIFT + _IOC_TYPEBITS
_IOC_DIRSHIFT = _IOC_SIZESHIFT + _IOC_SIZEBITS

_IOC_NONE = 0
_IOC_WRITE = 1
_IOC_READ = 2


def _IOC(direction: int, type_chr: str, number: int, size: int) -> int:
    return (
        (direction << _IOC_DIRSHIFT)
        | (ord(type_chr) << _IOC_TYPESHIFT)
        | (number << _IOC_NRSHIFT)
        | (size << _IOC_SIZESHIFT)
    )


def HIDIOCSFEATURE(length: int) -> int:
    return _IOC(_IOC_WRITE | _IOC_READ, "H", 0x06, length)


def HIDIOCSOUTPUT(length: int) -> int:
    return _IOC(_IOC_WRITE | _IOC_READ, "H", 0x0B, length)


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def read_text(path: pathlib.Path) -> Optional[str]:
    try:
        return path.read_text(encoding="utf-8", errors="ignore").strip()
    except Exception:
        return None


def fmt_hex(data: bytes) -> str:
    return " ".join(f"{b:02X}" for b in data)


def parse_hex_list(s: str) -> List[int]:
    out: List[int] = []
    for item in re.split(r"[,\s]+", s.strip()):
        if not item:
            continue
        if item.lower().startswith("0x"):
            out.append(int(item, 16))
        else:
            out.append(int(item, 0))
    return out


def parse_positions(s: str) -> List[int]:
    out: List[int] = []
    for part in s.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            a, b = part.split("-", 1)
            start, end = int(a), int(b)
            step = 1 if end >= start else -1
            out.extend(list(range(start, end + step, step)))
        else:
            out.append(int(part))
    return out


@dataclass
class HidrawDevice:
    node: str
    manufacturer: Optional[str]
    product: Optional[str]
    serial: Optional[str]
    interface_number: Optional[str]
    busnum: Optional[str]
    devnum: Optional[str]


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
                manufacturer=read_text(usb_dev / "manufacturer"),
                product=read_text(usb_dev / "product"),
                serial=read_text(usb_dev / "serial"),
                interface_number=read_text(interface_dir / "bInterfaceNumber") if interface_dir else None,
                busnum=read_text(usb_dev / "busnum"),
                devnum=read_text(usb_dev / "devnum"),
            )
        )
    return matches


def list_hid(vid: str, pid: str) -> int:
    devs = find_matching_hidraw_nodes(vid, pid)
    if not devs:
        print("No matching hidraw nodes found.")
        return 1
    for d in devs:
        print(
            f"{d.node}  iface={d.interface_number or '?'}  bus={d.busnum or '?'} dev={d.devnum or '?'}  "
            f"{d.manufacturer or ''} {d.product or ''}".rstrip()
        )
    return 0


def idle_drain(fd: int) -> List[bytes]:
    drained: List[bytes] = []
    while True:
        r, _, _ = select.select([fd], [], [], 0)
        if not r:
            break
        try:
            data = os.read(fd, 512)
        except BlockingIOError:
            break
        if not data:
            break
        drained.append(data)
    return drained


def collect_rx(fd: int, window_s: float) -> List[bytes]:
    until = time.monotonic() + window_s
    packets: List[bytes] = []
    while True:
        remaining = until - time.monotonic()
        if remaining <= 0:
            break
        r, _, _ = select.select([fd], [], [], remaining)
        if not r:
            break
        try:
            data = os.read(fd, 512)
        except BlockingIOError:
            continue
        if data:
            packets.append(data)
    return packets


def send_packet(fd: int, method: str, packet: bytes) -> Tuple[bool, str]:
    try:
        if method == "write":
            os.write(fd, packet)
        elif method == "output":
            buf = bytearray(packet)
            fcntl.ioctl(fd, HIDIOCSOUTPUT(len(buf)), buf, True)
        elif method == "feature":
            buf = bytearray(packet)
            fcntl.ioctl(fd, HIDIOCSFEATURE(len(buf)), buf, True)
        else:
            return False, f"unknown method {method}"
        return True, "ok"
    except OSError as e:
        return False, f"{e.__class__.__name__}: errno={e.errno} {e.strerror}"
    except Exception as e:
        return False, f"{e.__class__.__name__}: {e}"


@dataclass
class ProbeRecord:
    timestamp: str
    stage: str
    method: str
    packet_len: int
    packet_hex: str
    note: str
    effect: str
    success: bool
    status: str
    rx_hex: List[str]
    rx_count: int


class ProbeLogger:
    def __init__(self, jsonl_path: str, csv_path: str) -> None:
        self.jsonl_path = jsonl_path
        self.csv_path = csv_path
        if not os.path.exists(csv_path):
            with open(csv_path, "w", newline="", encoding="utf-8") as f:
                writer = csv.writer(f)
                writer.writerow([
                    "timestamp", "stage", "method", "packet_len", "packet_hex",
                    "success", "status", "effect", "note", "rx_count", "rx_hex_joined"
                ])

    def write(self, rec: ProbeRecord) -> None:
        with open(self.jsonl_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(asdict(rec), ensure_ascii=False) + "\n")
        with open(self.csv_path, "a", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([
                rec.timestamp, rec.stage, rec.method, rec.packet_len, rec.packet_hex,
                rec.success, rec.status, rec.effect, rec.note, rec.rx_count,
                " | ".join(rec.rx_hex)
            ])


def prompt_effect() -> Tuple[str, str, bool]:
    print("Observed behavior? [n]one / [v]isible change / [f]reeze-weird / [r]epeat / [q]uit")
    effect_raw = input("> ").strip().lower()
    if effect_raw == "q":
        return "quit", "", False
    if effect_raw == "r":
        return "repeat", "", True
    mapping = {
        "": "none",
        "n": "none",
        "v": "visible_change",
        "f": "freeze_or_weird",
    }
    effect = mapping.get(effect_raw, effect_raw or "none")
    note = input("Describe what happened (or Enter): ").strip()
    return effect, note, False


def build_packet(total_len: int, report_id: int, mutations: Sequence[Tuple[int, int]]) -> bytes:
    if total_len <= 0:
        raise ValueError("length must be > 0")
    buf = bytearray([0] * total_len)
    buf[0] = report_id & 0xFF
    for pos, value in mutations:
        if not (0 <= pos < total_len):
            raise ValueError(f"position {pos} out of range for len {total_len}")
        buf[pos] = value & 0xFF
    return bytes(buf)


def default_stage1_lengths() -> List[int]:
    return [32, 33, 51, 52, 64, 65, 91, 92]


def default_stage1_report_ids() -> List[int]:
    # 0x00 for single-report devices; qKontrol/MK2-like families included for discovery only.
    return [0x00, 0x01, 0x80, 0x84, 0xA1, 0xA2, 0xA3, 0xA4, 0xF4]


def default_stage2_values() -> List[int]:
    return [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0xFF]


def open_hid(path: str) -> int:
    fd = os.open(path, os.O_RDWR | os.O_NONBLOCK)
    return fd


def run_stage(
    fd: int,
    stage: str,
    candidates: Iterable[Tuple[str, bytes]],
    logger: ProbeLogger,
    window_s: float,
    settle_s: float,
) -> int:
    count = 0
    for method, packet in candidates:
        count += 1
        print("\n" + "=" * 72)
        print(f"#{count}  stage={stage}  method={method}  len={len(packet)}")
        print(f"TX  {fmt_hex(packet)}")
        idle_drain(fd)
        ok, status = send_packet(fd, method, packet)
        if settle_s > 0:
            time.sleep(settle_s)
        rx_packets = collect_rx(fd, window_s)
        if rx_packets:
            for p in rx_packets:
                print(f"RX  {fmt_hex(p)}")
        else:
            print("RX  <none>")
        print(f"status: {status}")

        while True:
            effect, note, repeat = prompt_effect()
            if effect == "quit":
                rec = ProbeRecord(
                    timestamp=utc_now(),
                    stage=stage,
                    method=method,
                    packet_len=len(packet),
                    packet_hex=fmt_hex(packet),
                    note=note,
                    effect="quit_requested",
                    success=ok,
                    status=status,
                    rx_hex=[fmt_hex(p) for p in rx_packets],
                    rx_count=len(rx_packets),
                )
                logger.write(rec)
                return count
            if repeat:
                print("Repeating same probe once...")
                ok, status = send_packet(fd, method, packet)
                if settle_s > 0:
                    time.sleep(settle_s)
                rx_packets = collect_rx(fd, window_s)
                if rx_packets:
                    for p in rx_packets:
                        print(f"RX  {fmt_hex(p)}")
                else:
                    print("RX  <none>")
                continue
            rec = ProbeRecord(
                timestamp=utc_now(),
                stage=stage,
                method=method,
                packet_len=len(packet),
                packet_hex=fmt_hex(packet),
                note=note,
                effect=effect,
                success=ok,
                status=status,
                rx_hex=[fmt_hex(p) for p in rx_packets],
                rx_count=len(rx_packets),
            )
            logger.write(rec)
            break
    return count


def generate_stage1(methods: Sequence[str], lengths: Sequence[int], report_ids: Sequence[int]) -> Iterable[Tuple[str, bytes]]:
    for method in methods:
        for total_len in lengths:
            for rid in report_ids:
                yield method, build_packet(total_len, rid, [])


def generate_stage2(method: str, total_len: int, report_id: int, positions: Sequence[int], values: Sequence[int]) -> Iterable[Tuple[str, bytes]]:
    for pos in positions:
        for value in values:
            yield method, build_packet(total_len, report_id, [(pos, value)])


def generate_stage3(method: str, total_len: int, report_id: int, position: int, start: int, end: int, step: int) -> Iterable[Tuple[str, bytes]]:
    for value in range(start, end + 1, step):
        yield method, build_packet(total_len, report_id, [(position, value)])


def choose_auto_hid(vid: str, pid: str) -> Optional[str]:
    matches = find_matching_hidraw_nodes(vid, pid)
    if not matches:
        return None
    # Prefer interface 02 if available, because that's where your A49 transport HID traffic appeared.
    for d in matches:
        if (d.interface_number or "").lower() == "02":
            return d.node
    return matches[0].node


def main() -> int:
    ap = argparse.ArgumentParser(description="Guided HID probe tool for NI A49")
    ap.add_argument("--vid", default=DEFAULT_VID)
    ap.add_argument("--pid", default=DEFAULT_PID)
    ap.add_argument("--list-hid", action="store_true")
    ap.add_argument("--hid", help="Path like /dev/hidraw6. If omitted, auto-detect by VID:PID.")
    ap.add_argument("--jsonl", default=DEFAULT_JSONL)
    ap.add_argument("--csv", default=DEFAULT_CSV)
    ap.add_argument("--window", type=float, default=DEFAULT_WINDOW, help="Response capture window in seconds")
    ap.add_argument("--settle", type=float, default=DEFAULT_SETTLE, help="Pause after TX before judging effect")

    stage = ap.add_mutually_exclusive_group()
    stage.add_argument("--stage1", action="store_true", help="Discover likely writable report families")
    stage.add_argument("--stage2", action="store_true", help="Bitwalk selected payload positions")
    stage.add_argument("--stage3", action="store_true", help="Sweep one payload position across a byte range")

    ap.add_argument("--methods", default="write,output,feature", help="Comma list: write,output,feature")
    ap.add_argument("--lengths", default=None, help="Comma list of total packet lengths for stage1")
    ap.add_argument("--report-ids", default=None, help="Comma list of report IDs for stage1 or selected stage2/3 base")

    ap.add_argument("--method", choices=["write", "output", "feature"], help="Single method for stage2/3")
    ap.add_argument("--length", type=int, help="Total packet length for stage2/3")
    ap.add_argument("--report-id", type=lambda x: int(x, 0), help="Report ID / first byte for stage2/3")
    ap.add_argument("--positions", default="1-8,16-18,28-31", help="Payload positions for stage2")
    ap.add_argument("--values", default=None, help="Comma list of values for stage2")
    ap.add_argument("--position", type=int, help="Single payload position for stage3")
    ap.add_argument("--start", type=lambda x: int(x, 0), default=0)
    ap.add_argument("--end", type=lambda x: int(x, 0), default=255)
    ap.add_argument("--step", type=lambda x: int(x, 0), default=1)

    args = ap.parse_args()

    if args.list_hid:
        return list_hid(args.vid, args.pid)

    hid_path = args.hid or choose_auto_hid(args.vid, args.pid)
    if not hid_path:
        print("No matching hidraw node found. Use --list-hid or specify --hid /dev/hidrawX.")
        return 1

    methods = [m.strip() for m in args.methods.split(",") if m.strip()]
    logger = ProbeLogger(args.jsonl, args.csv)

    try:
        fd = open_hid(hid_path)
    except OSError as e:
        print(f"Failed to open {hid_path}: {e}")
        print("Tip: grant your user rw access to that hidraw node; do not use sudo if you later need desktop-session visibility.")
        return 1

    print(f"Opened HID node: {hid_path}")
    print(f"Logs: {args.jsonl} and {args.csv}")
    print("Disconnect the device from DAW/NI software before probing. Unplug/replug if it wedges.")

    signal.signal(signal.SIGINT, lambda *_: sys.exit(130))

    try:
        if args.stage2:
            if not (args.method and args.length and args.report_id is not None):
                print("Stage 2 needs --method, --length and --report-id")
                return 2
            positions = parse_positions(args.positions)
            values = parse_hex_list(args.values) if args.values else default_stage2_values()
            candidates = generate_stage2(args.method, args.length, args.report_id, positions, values)
            run_stage(fd, "stage2_bitwalk", candidates, logger, args.window, args.settle)
        elif args.stage3:
            if not (args.method and args.length and args.report_id is not None and args.position is not None):
                print("Stage 3 needs --method, --length, --report-id and --position")
                return 2
            candidates = generate_stage3(args.method, args.length, args.report_id, args.position, args.start, args.end, args.step)
            run_stage(fd, "stage3_sweep", candidates, logger, args.window, args.settle)
        else:
            lengths = parse_hex_list(args.lengths) if args.lengths else default_stage1_lengths()
            report_ids = parse_hex_list(args.report_ids) if args.report_ids else default_stage1_report_ids()
            candidates = generate_stage1(methods, lengths, report_ids)
            run_stage(fd, "stage1_discover", candidates, logger, args.window, args.settle)
    finally:
        os.close(fd)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
