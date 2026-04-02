#!/usr/bin/env python3
"""
Guided SysEx probe for Native Instruments Komplete Kontrol A49.

This script works at the MIDI port layer rather than hidraw. That matches the
A-Series docs, which say MIDI templates are synchronized via the
"Komplete Kontrol A25/A49/A61 MIDI" port.

Features:
- list MIDI input/output ports
- send one-off SysEx packets from hex or structured args
- guided probe mode: sweep one payload byte at a time while the user annotates
- optional MIDI input logging in a background thread
- JSONL + CSV logs for later analysis

Dependencies:
    pip install mido python-rtmidi
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from queue import Queue, Empty
from typing import Iterable, List, Optional

try:
    import mido
except Exception as exc:
    print("This script needs mido and python-rtmidi in the current venv.")
    print(f"Import error: {exc}")
    sys.exit(1)


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).astimezone().isoformat()


def parse_hex_bytes(text: str) -> List[int]:
    text = text.strip().replace(',', ' ').replace(':', ' ')
    if not text:
        return []
    parts = [p for p in text.split() if p]
    out: List[int] = []
    for p in parts:
        base = 16 if any(c in p.lower() for c in 'abcdefx') or p.startswith('0') else 10
        v = int(p, base)
        if not (0 <= v <= 255):
            raise ValueError(f"byte out of range: {p}")
        out.append(v)
    return out


def parse_range_expr(expr: str) -> List[int]:
    expr = expr.strip()
    if not expr:
        return []
    vals: List[int] = []
    for part in expr.split(','):
        part = part.strip()
        if not part:
            continue
        if '-' in part:
            a, b = [x.strip() for x in part.split('-', 1)]
            start = int(a, 0)
            end = int(b, 0)
            step = 1 if end >= start else -1
            vals.extend(list(range(start, end + step, step)))
        else:
            vals.append(int(part, 0))
    return vals


def build_sysex(prefix: List[int], payload: List[int]) -> List[int]:
    full = prefix + payload
    for b in full:
        if not (0 <= b <= 127):
            raise ValueError(f"SysEx data bytes must be 0..127, got {b}")
    return full


@dataclass
class MidiInputLogger:
    port_name: str
    queue: Queue
    stop_flag: threading.Event

    def __post_init__(self) -> None:
        self.port = mido.open_input(self.port_name)
        self.thread = threading.Thread(target=self._run, daemon=True)

    def start(self) -> None:
        self.thread.start()

    def _run(self) -> None:
        while not self.stop_flag.is_set():
            for msg in self.port.iter_pending():
                self.queue.put({
                    "timestamp": now_iso(),
                    "type": "midi_in",
                    "message": msg.dict(),
                    "bytes": list(msg.bytes()),
                })
            time.sleep(0.01)

    def close(self) -> None:
        self.stop_flag.set()
        time.sleep(0.05)
        self.port.close()


class ProbeLogger:
    def __init__(self, jsonl_path: Path, csv_path: Path) -> None:
        self.jsonl_path = jsonl_path
        self.csv_path = csv_path
        self.csv_header_written = csv_path.exists() and csv_path.stat().st_size > 0

    def log_json(self, obj: dict) -> None:
        with self.jsonl_path.open('a', encoding='utf-8') as f:
            f.write(json.dumps(obj, ensure_ascii=False) + '\n')

    def log_csv_row(self, row: dict) -> None:
        fieldnames = [
            "timestamp", "prefix_hex", "payload_hex", "full_sysex_hex",
            "sweep_index", "sweep_value", "changed", "note"
        ]
        with self.csv_path.open('a', encoding='utf-8', newline='') as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            if not self.csv_header_written:
                writer.writeheader()
                self.csv_header_written = True
            writer.writerow(row)


def list_ports() -> None:
    ins = mido.get_input_names()
    outs = mido.get_output_names()
    print("MIDI inputs:")
    for i, name in enumerate(ins):
        print(f"  [{i}] {name}")
    print("MIDI outputs:")
    for i, name in enumerate(outs):
        print(f"  [{i}] {name}")


def choose_default_ni_port(names: Iterable[str]) -> Optional[str]:
    lowered = [(n.lower(), n) for n in names]
    for low, orig in lowered:
        if 'komplete kontrol a49 midi' in low:
            return orig
    for low, orig in lowered:
        if 'komplete kontrol' in low and 'midi' in low:
            return orig
    return None


def send_sysex(output_name: str, prefix: List[int], payload: List[int], repeat: int = 1, delay: float = 0.15) -> None:
    msg = mido.Message('sysex', data=build_sysex(prefix, payload))
    with mido.open_output(output_name) as outp:
        for _ in range(repeat):
            outp.send(msg)
            time.sleep(delay)


def print_recent_input(queue: Queue, clear: bool = True) -> None:
    seen_any = False
    while True:
        try:
            item = queue.get_nowait()
        except Empty:
            break
        seen_any = True
        if item.get('type') == 'midi_in':
            print(f"IN  bytes={item['bytes']} msg={item['message']}")
    if not seen_any and not clear:
        print("(no MIDI input seen)")


def guided_probe(args: argparse.Namespace) -> int:
    out_name = args.send_output or choose_default_ni_port(mido.get_output_names())
    if not out_name:
        print("No MIDI output selected and no obvious Komplete Kontrol MIDI output found.")
        return 2

    in_name = args.listen_input or choose_default_ni_port(mido.get_input_names())
    q: Queue = Queue()
    stop_flag = threading.Event()
    logger = None
    if in_name:
        logger = MidiInputLogger(in_name, q, stop_flag)
        logger.start()
        print(f"Listening on MIDI input: {in_name}")
    else:
        print("No MIDI input selected; responses will not be captured.")

    print(f"Sending to MIDI output: {out_name}")

    prefix = parse_hex_bytes(args.prefix)
    base_payload = parse_hex_bytes(args.payload)
    if args.payload_len is not None:
        while len(base_payload) < args.payload_len:
            base_payload.append(0)
        base_payload = base_payload[:args.payload_len]

    if args.sweep_index < 0 or args.sweep_index >= len(base_payload):
        print(f"--sweep-index must be within 0..{len(base_payload)-1}")
        return 2

    values = parse_range_expr(args.values)
    if not values:
        print("No sweep values specified.")
        return 2
    for v in values:
        if not (0 <= v <= 127):
            print(f"Invalid SysEx data byte {v}; SysEx payload bytes must be 0..127")
            return 2

    probe = ProbeLogger(Path(args.jsonl), Path(args.csv))

    print("\nGuided SysEx probe starting.")
    print("Keys: y = visible change, n = no change, s = skip rest, q = quit")
    print("A short wait follows each send so the keyboard can react.")

    try:
        with mido.open_output(out_name) as outp:
            for val in values:
                payload = list(base_payload)
                payload[args.sweep_index] = val
                msg = mido.Message('sysex', data=build_sysex(prefix, payload))
                outp.send(msg)
                sent = {
                    "timestamp": now_iso(),
                    "type": "sysex_out",
                    "prefix": prefix,
                    "payload": payload,
                    "full_sysex": [0xF0] + list(msg.data) + [0xF7],
                    "sweep_index": args.sweep_index,
                    "sweep_value": val,
                }
                probe.log_json(sent)
                time.sleep(args.wait)
                print(f"\nSent: F0 {' '.join(f'{b:02X}' for b in msg.data)} F7")
                print_recent_input(q)
                answer = input("Did anything visible happen? [y/n/s/q] ").strip().lower()
                if answer == 'q':
                    break
                if answer == 's':
                    break
                changed = answer == 'y'
                note = input("Optional note: ").strip()
                row = {
                    "timestamp": now_iso(),
                    "prefix_hex": ' '.join(f'{b:02X}' for b in prefix),
                    "payload_hex": ' '.join(f'{b:02X}' for b in payload),
                    "full_sysex_hex": ' '.join(f'{b:02X}' for b in ([0xF0] + list(msg.data) + [0xF7])),
                    "sweep_index": args.sweep_index,
                    "sweep_value": val,
                    "changed": int(changed),
                    "note": note,
                }
                probe.log_csv_row(row)
    finally:
        if logger:
            time.sleep(0.1)
            print_recent_input(q)
            logger.close()
    print(f"Wrote {args.jsonl} and {args.csv}")
    return 0


def one_shot(args: argparse.Namespace) -> int:
    out_name = args.send_output or choose_default_ni_port(mido.get_output_names())
    if not out_name:
        print("No MIDI output selected and no obvious Komplete Kontrol MIDI output found.")
        return 2

    if args.send_hex:
        raw = parse_hex_bytes(args.send_hex)
        if raw and raw[0] == 0xF0 and raw[-1] == 0xF7:
            data = raw[1:-1]
        else:
            data = raw
        for b in data:
            if not (0 <= b <= 127):
                print(f"Invalid SysEx data byte {b}; SysEx payload bytes must be 0..127")
                return 2
        msg = mido.Message('sysex', data=data)
    else:
        prefix = parse_hex_bytes(args.prefix)
        payload = parse_hex_bytes(args.payload)
        msg = mido.Message('sysex', data=build_sysex(prefix, payload))

    print(f"Sending to: {out_name}")
    print(f"Packet: F0 {' '.join(f'{b:02X}' for b in msg.data)} F7")
    with mido.open_output(out_name) as outp:
        outp.send(msg)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Guided SysEx probe for Komplete Kontrol A49 MIDI port")
    ap.add_argument('--list', action='store_true', help='List MIDI input and output ports')
    ap.add_argument('--send-output', help='Exact MIDI output port name to send to')
    ap.add_argument('--listen-input', help='Exact MIDI input port name to listen on')
    ap.add_argument('--send-hex', help='One-shot full SysEx as hex, with or without F0/F7')
    ap.add_argument('--prefix', default='', help='SysEx prefix bytes, e.g. "00 20 7F 01"')
    ap.add_argument('--payload', default='00 00 00 00 00 00 00 00', help='Initial payload bytes')
    ap.add_argument('--payload-len', type=int, help='Pad/truncate payload to this many bytes')
    ap.add_argument('--guided', action='store_true', help='Run guided one-byte sweep mode')
    ap.add_argument('--sweep-index', type=int, default=0, help='Payload byte index to vary in guided mode')
    ap.add_argument('--values', default='0-127', help='Values or ranges for guided sweep, e.g. "0-15,32,64,127"')
    ap.add_argument('--wait', type=float, default=0.25, help='Seconds to wait after each send in guided mode')
    ap.add_argument('--jsonl', default='a49_midi_sysex_probe.jsonl', help='JSONL log path for guided mode')
    ap.add_argument('--csv', default='a49_midi_sysex_probe.csv', help='CSV summary path for guided mode')
    args = ap.parse_args()

    if args.list:
        list_ports()
        return 0
    if args.guided:
        return guided_probe(args)
    return one_shot(args)


if __name__ == '__main__':
    raise SystemExit(main())
