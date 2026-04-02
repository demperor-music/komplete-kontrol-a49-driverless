#!/usr/bin/env python3
"""
A49 MIDI template lab

Utility to inspect and send MIDI/SysEx messages on the Native Instruments
Komplete Kontrol A49 MIDI port. Intended for reverse-engineering the A-series
MIDI template sync path.

Requires:
    pip install mido python-rtmidi
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Iterable, List, Optional

try:
    import mido
except ImportError:
    print("This tool needs mido + python-rtmidi in your venv: python -m pip install mido python-rtmidi", file=sys.stderr)
    raise


def list_ports() -> None:
    print("Inputs:")
    for name in mido.get_input_names():
        print(f"  {name}")
    print("Outputs:")
    for name in mido.get_output_names():
        print(f"  {name}")


def find_port(kind: str, needle: str) -> str:
    names = mido.get_input_names() if kind == "input" else mido.get_output_names()
    for name in names:
        if needle.lower() in name.lower():
            return name
    raise SystemExit(f"No {kind} port matched {needle!r}. Available: {names}")


def format_msg(msg: mido.Message) -> str:
    data = list(msg.bytes())
    hexs = " ".join(f"{b:02X}" for b in data)
    return f"{msg!r} bytes=[{', '.join(str(b) for b in data)}] hex=[{hexs}]"


def dump_input(port_name: str, out_path: Optional[Path], sysex_only: bool) -> None:
    print(f"Listening on input: {port_name}")
    fh = out_path.open("a", encoding="utf-8") if out_path else None
    try:
        with mido.open_input(port_name) as inp:
            while True:
                for msg in inp.iter_pending():
                    if sysex_only and msg.type != "sysex":
                        continue
                    line = format_msg(msg)
                    print(line)
                    if fh:
                        rec = {
                            "ts": time.time(),
                            "type": msg.type,
                            "bytes": list(msg.bytes()),
                            "repr": repr(msg),
                        }
                        fh.write(json.dumps(rec) + "\n")
                        fh.flush()
                time.sleep(0.005)
    except KeyboardInterrupt:
        pass
    finally:
        if fh:
            fh.close()


def parse_hex_stream(text: str) -> List[int]:
    cleaned = text.replace(",", " ").replace("0x", " ")
    vals: List[int] = []
    for tok in cleaned.split():
        vals.append(int(tok, 16))
    return vals


def send_hex(output_name: str, hex_bytes: Iterable[int], repeat: int, delay_ms: int) -> None:
    raw = list(hex_bytes)
    if not raw:
        raise SystemExit("No bytes supplied")
    print(f"Sending to output: {output_name}")
    print("Hex:", " ".join(f"{b:02X}" for b in raw))
    with mido.open_output(output_name) as outp:
        if raw[0] == 0xF0 and raw[-1] == 0xF7:
            msg = mido.Message('sysex', data=raw[1:-1])
        else:
            msg = mido.Message.from_bytes(raw)
        for i in range(repeat):
            outp.send(msg)
            print(f"Sent {i+1}/{repeat}: {format_msg(msg)}")
            if i + 1 < repeat:
                time.sleep(delay_ms / 1000.0)


def main() -> None:
    ap = argparse.ArgumentParser(description="Inspect/send A49 MIDI template traffic")
    ap.add_argument("--list", action="store_true", help="List MIDI input/output ports")
    ap.add_argument("--dump-input", metavar="NAME", help="Open matching MIDI input port and print messages")
    ap.add_argument("--dump-output-loopback", metavar="NAME", help="Alias for --dump-input; useful when monitoring a virtual loopback port")
    ap.add_argument("--sysex-only", action="store_true", help="During dump, only show SysEx messages")
    ap.add_argument("--log-jsonl", metavar="FILE", help="Append dumped messages as JSONL")
    ap.add_argument("--send-output", metavar="NAME", help="Open matching MIDI output port and send bytes")
    ap.add_argument("--send-hex", metavar="HEX", help="Hex bytes to send, e.g. 'F0 00 20 09 01 F7'")
    ap.add_argument("--send-file", metavar="FILE", help="Read hex bytes from file")
    ap.add_argument("--repeat", type=int, default=1, help="Repeat sends")
    ap.add_argument("--delay-ms", type=int, default=100, help="Delay between repeated sends")
    args = ap.parse_args()

    if args.list:
        list_ports()
        return

    dump_name = args.dump_input or args.dump_output_loopback
    if dump_name:
        port = find_port("input", dump_name)
        dump_input(port, Path(args.log_jsonl) if args.log_jsonl else None, args.sysex_only)
        return

    if args.send_output:
        if not (args.send_hex or args.send_file):
            raise SystemExit("--send-output requires --send-hex or --send-file")
        port = find_port("output", args.send_output)
        if args.send_file:
            text = Path(args.send_file).read_text(encoding="utf-8")
        else:
            text = args.send_hex
        data = parse_hex_stream(text)
        send_hex(port, data, args.repeat, args.delay_ms)
        return

    ap.print_help()


if __name__ == "__main__":
    main()
