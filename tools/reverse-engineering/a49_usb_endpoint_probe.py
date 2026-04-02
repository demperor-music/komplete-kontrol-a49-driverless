#!/usr/bin/env python3
"""
Guided USB endpoint probe for Native Instruments Komplete Kontrol A49 (17cc:1740).

Requires: pyusb  (python -m pip install pyusb)
Potentially also libusb installed on the system.

Purpose:
- Enumerate all USB interfaces/endpoints on the device
- Read from interrupt/bulk IN endpoints
- Send structured test packets to specific OUT endpoints
- Prompt the user to annotate visible effects

This is intended to probe non-HID interfaces (for display/text/backlight control)
after HID/light probing has already shown that report 0x80 appears to affect LEDs.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
import time
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import List, Optional

try:
    import usb.core
    import usb.util
except Exception as e:
    print("pyusb is required: python -m pip install pyusb", file=sys.stderr)
    raise

VID = 0x17CC
PID = 0x1740


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def hex_bytes(data: bytes) -> str:
    return " ".join(f"{b:02X}" for b in data)


@dataclass
class ProbeRow:
    timestamp: str
    interface: int
    altsetting: int
    endpoint: str
    direction: str
    transfer_type: str
    packet_hex: str
    packet_len: int
    readback_hex: str
    result: str
    note: str


TRANSFER_TYPES = {
    usb.util.ENDPOINT_TYPE_CTRL: "control",
    # usb.util.ENDPOINT_TYPE_ISOCHRONOUS: "isochronous",
    usb.util.ENDPOINT_TYPE_BULK: "bulk",
    usb.util.ENDPOINT_TYPE_INTR: "interrupt",
}


def find_dev() -> usb.core.Device:
    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        raise SystemExit(f"Device {VID:04x}:{PID:04x} not found")
    return dev


def endpoint_desc(ep) -> dict:
    addr = ep.bEndpointAddress
    return {
        "address": f"0x{addr:02X}",
        "direction": "IN" if usb.util.endpoint_direction(addr) == usb.util.ENDPOINT_IN else "OUT",
        "transfer_type": TRANSFER_TYPES.get(usb.util.endpoint_type(ep.bmAttributes), f"unknown({ep.bmAttributes})"),
        "max_packet_size": ep.wMaxPacketSize,
        "interval": ep.bInterval,
    }


def list_topology(dev: usb.core.Device) -> None:
    print(f"Device: {dev.idVendor:04x}:{dev.idProduct:04x}")
    try:
        manu = usb.util.get_string(dev, dev.iManufacturer)
        prod = usb.util.get_string(dev, dev.iProduct)
        print(f"Strings: manufacturer={manu!r} product={prod!r}")
    except Exception:
        pass
    for cfg in dev:
        print(f"Configuration {cfg.bConfigurationValue}")
        for intf in cfg:
            print(
                f"  Interface {intf.bInterfaceNumber}, alt {intf.bAlternateSetting}, "
                f"class=0x{intf.bInterfaceClass:02X}, subclass=0x{intf.bInterfaceSubClass:02X}, protocol=0x{intf.bInterfaceProtocol:02X}"
            )
            for ep in intf:
                d = endpoint_desc(ep)
                print(
                    f"    Endpoint {d['address']} {d['direction']} {d['transfer_type']} "
                    f"maxpkt={d['max_packet_size']} interval={d['interval']}"
                )


def claim_interface(dev: usb.core.Device, iface: int, alt: int = 0):
    try:
        if dev.is_kernel_driver_active(iface):
            try:
                dev.detach_kernel_driver(iface)
            except usb.core.USBError:
                pass
    except (NotImplementedError, usb.core.USBError):
        pass
    usb.util.claim_interface(dev, iface)
    try:
        dev.set_interface_altsetting(interface=iface, alternate_setting=alt)
    except usb.core.USBError:
        pass


def release_interface(dev: usb.core.Device, iface: int):
    try:
        usb.util.release_interface(dev, iface)
    except Exception:
        pass


def locate_endpoint(dev: usb.core.Device, iface: int, ep_addr: int, alt: int = 0):
    cfg = dev.get_active_configuration()
    intf = usb.util.find_descriptor(cfg, bInterfaceNumber=iface, bAlternateSetting=alt)
    if intf is None:
        raise SystemExit(f"Interface {iface} alt {alt} not found")
    ep = usb.util.find_descriptor(intf, bEndpointAddress=ep_addr)
    if ep is None:
        raise SystemExit(f"Endpoint 0x{ep_addr:02X} not found on interface {iface} alt {alt}")
    return intf, ep


def maybe_read_all_in_eps(dev: usb.core.Device, iface: int, alt: int = 0, timeout_ms: int = 40) -> List[str]:
    cfg = dev.get_active_configuration()
    intf = usb.util.find_descriptor(cfg, bInterfaceNumber=iface, bAlternateSetting=alt)
    if intf is None:
        return []
    reads = []
    for ep in intf:
        if usb.util.endpoint_direction(ep.bEndpointAddress) != usb.util.ENDPOINT_IN:
            continue
        if usb.util.endpoint_type(ep.bmAttributes) not in (usb.util.ENDPOINT_TYPE_BULK, usb.util.ENDPOINT_TYPE_INTR):
            continue
        try:
            data = bytes(ep.read(ep.wMaxPacketSize, timeout=timeout_ms))
            if data:
                reads.append(f"0x{ep.bEndpointAddress:02X}:{hex_bytes(data)}")
        except usb.core.USBError:
            pass
    return reads


def prompt_result() -> tuple[str, str]:
    while True:
        raw = input("Result? [n]one / [v]isible / [w]eird-freeze / [q]uit: ").strip().lower()
        if raw in ("n", "", "none"):
            note = input("Note (optional): ").strip()
            return "none", note
        if raw in ("v", "visible"):
            note = input("What changed?: ").strip()
            return "visible_change", note
        if raw in ("w", "weird", "freeze"):
            note = input("Describe the weirdness: ").strip()
            return "weird_or_freeze", note
        if raw in ("q", "quit"):
            raise KeyboardInterrupt


def write_rows(rows: List[ProbeRow], jsonl_path: Path, csv_path: Path) -> None:
    with jsonl_path.open("a", encoding="utf-8") as jf:
        for row in rows:
            jf.write(json.dumps(asdict(row), ensure_ascii=False) + "\n")
    new_file = not csv_path.exists()
    with csv_path.open("a", newline="", encoding="utf-8") as cf:
        writer = csv.DictWriter(cf, fieldnames=list(asdict(rows[0]).keys()))
        if new_file:
            writer.writeheader()
        for row in rows:
            writer.writerow(asdict(row))


def parse_hex_or_dec(s: str) -> int:
    return int(s, 0)


def build_packet(report_id: int, length: int, byte_index: Optional[int] = None, value: int = 0) -> bytes:
    if length < 1:
        raise ValueError("length must be >= 1")
    pkt = bytearray([0] * length)
    pkt[0] = report_id & 0xFF
    if byte_index is not None:
        if not 0 <= byte_index < length:
            raise ValueError(f"byte index {byte_index} out of range for length {length}")
        pkt[byte_index] = value & 0xFF
    return bytes(pkt)


def send_probe(dev, iface, alt, ep_addr, packet: bytes, timeout_ms: int = 120) -> List[str]:
    claim_interface(dev, iface, alt)
    _, ep = locate_endpoint(dev, iface, ep_addr, alt)
    ep.write(packet, timeout=timeout_ms)
    time.sleep(0.05)
    reads = maybe_read_all_in_eps(dev, iface, alt, timeout_ms=40)
    release_interface(dev, iface)
    return reads


def main() -> int:
    ap = argparse.ArgumentParser(description="Guided USB endpoint probe for NI A49")
    ap.add_argument("--list", action="store_true", help="List interfaces and endpoints")
    ap.add_argument("--iface", type=int, help="Interface number to probe")
    ap.add_argument("--alt", type=int, default=0, help="Alternate setting (default 0)")
    ap.add_argument("--endpoint", help="OUT endpoint address, e.g. 0x03 or 0x02")
    ap.add_argument("--report-id", type=parse_hex_or_dec, default=0x84, help="First byte of packet")
    ap.add_argument("--length", type=int, default=64, help="Packet length")
    ap.add_argument("--bytes", default="1-8", help="Byte positions to vary, e.g. 1-8,16,20")
    ap.add_argument("--values", default="0x00,0x20,0xFF", help="Values to try, comma-separated (0x00=off for LEDs)")
    ap.add_argument("--single-packet-hex", help="Send one exact packet, e.g. '84 00 00 00'")
    ap.add_argument("--jsonl", default="a49_usb_probe.jsonl")
    ap.add_argument("--csv", default="a49_usb_probe.csv")
    args = ap.parse_args()

    dev = find_dev()

    if args.list:
        list_topology(dev)
        return 0

    if args.iface is None or args.endpoint is None:
        ap.error("--iface and --endpoint are required unless using --list")

    ep_addr = parse_hex_or_dec(args.endpoint)
    rows: List[ProbeRow] = []
    jsonl_path = Path(args.jsonl)
    csv_path = Path(args.csv)

    try:
        if args.single_packet_hex:
            packet = bytes(int(x, 16) for x in args.single_packet_hex.split())
            reads = send_probe(dev, args.iface, args.alt, ep_addr, packet)
            print(f"Sent: {hex_bytes(packet)}")
            print(f"Readbacks: {reads or ['<none>']}")
            result, note = prompt_result()
            rows.append(
                ProbeRow(
                    timestamp=now_iso(),
                    interface=args.iface,
                    altsetting=args.alt,
                    endpoint=f"0x{ep_addr:02X}",
                    direction="OUT",
                    transfer_type="bulk_or_interrupt",
                    packet_hex=hex_bytes(packet),
                    packet_len=len(packet),
                    readback_hex=" | ".join(reads),
                    result=result,
                    note=note,
                )
            )
        else:
            positions: List[int] = []
            for part in args.bytes.split(","):
                part = part.strip()
                if not part:
                    continue
                if "-" in part:
                    a, b = part.split("-", 1)
                    positions.extend(range(int(a), int(b) + 1))
                else:
                    positions.append(int(part))
            values = [parse_hex_or_dec(v.strip()) for v in args.values.split(",") if v.strip()]

            for pos in positions:
                for val in values:
                    packet = build_packet(args.report_id, args.length, pos, val)
                    try:
                        reads = send_probe(dev, args.iface, args.alt, ep_addr, packet)
                    except usb.core.USBError as e:
                        reads = [f"USBError:{e}"]
                    print(f"Sent pos={pos} val=0x{val:02X}: {hex_bytes(packet[:min(len(packet),32)])}{' ...' if len(packet)>32 else ''}")
                    print(f"Readbacks: {reads or ['<none>']}")
                    result, note = prompt_result()
                    row = ProbeRow(
                        timestamp=now_iso(),
                        interface=args.iface,
                        altsetting=args.alt,
                        endpoint=f"0x{ep_addr:02X}",
                        direction="OUT",
                        transfer_type="bulk_or_interrupt",
                        packet_hex=hex_bytes(packet),
                        packet_len=len(packet),
                        readback_hex=" | ".join(reads),
                        result=result,
                        note=note,
                    )
                    rows.append(row)
                    write_rows([row], jsonl_path, csv_path)
                    if result == "weird_or_freeze":
                        print("Keyboard may need replugging before continuing.")
    except KeyboardInterrupt:
        print("Stopped.")
    finally:
        usb.util.dispose_resources(dev)

    if rows:
        print(f"Wrote {len(rows)} rows to {csv_path} and {jsonl_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
