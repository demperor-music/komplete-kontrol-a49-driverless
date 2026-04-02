#!/usr/bin/env python3
import argparse, sys

try:
    import usb.core
    import usb.util
except ImportError:
    print("pyusb is required: python -m pip install pyusb", file=sys.stderr)
    sys.exit(1)

VID = 0x17CC
PID = 0x1740

TRANSFER_TYPES = {
    usb.util.ENDPOINT_TYPE_CTRL: "control",
    usb.util.ENDPOINT_TYPE_BULK: "bulk",
    usb.util.ENDPOINT_TYPE_INTR: "interrupt",
}


def find_dev():
    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        raise SystemExit("A49 not found")
    return dev


def list_eps(dev):
    for cfg in dev:
        print(f"Config {cfg.bConfigurationValue}")
        for intf in cfg:
            print(
                f"  Interface {intf.bInterfaceNumber} alt {intf.bAlternateSetting} "
                f"class=0x{intf.bInterfaceClass:02x} subclass=0x{intf.bInterfaceSubClass:02x} "
                f"proto=0x{intf.bInterfaceProtocol:02x}"
            )
            for ep in intf:
                addr = ep.bEndpointAddress
                direc = "IN" if usb.util.endpoint_direction(addr) == usb.util.ENDPOINT_IN else "OUT"
                t = TRANSFER_TYPES.get(usb.util.endpoint_type(ep.bmAttributes), f"type{usb.util.endpoint_type(ep.bmAttributes)}")
                print(f"    ep 0x{addr:02x} {direc} {t} max={ep.wMaxPacketSize}")


def ensure_active_configuration(dev):
    try:
        cfg = dev.get_active_configuration()
        if cfg is not None:
            return cfg
    except usb.core.USBError:
        pass
    # Only set configuration if there is no active configuration visible.
    # Calling set_configuration() on an already-configured device often behaves like a reset,
    # and on Linux may fail with EBUSY when a kernel driver has an interface bound.
    try:
        dev.set_configuration()
    except usb.core.USBError as e:
        raise SystemExit(
            f"Could not set USB configuration: {e}\n"
            "If the device is already configured, rerun with no DAW/NI app using it, or use the active configuration path instead."
        )
    return dev.get_active_configuration()


def detach_and_claim(dev, iface: int):
    detached = False
    try:
        if dev.is_kernel_driver_active(iface):
            dev.detach_kernel_driver(iface)
            detached = True
    except (NotImplementedError, usb.core.USBError):
        pass
    try:
        usb.util.claim_interface(dev, iface)
    except usb.core.USBError as e:
        msg = f"Could not claim interface {iface}: {e}"
        if detached:
            msg += "\nThe kernel driver was detached, so another userspace process may still have the interface open."
        else:
            msg += "\nA kernel driver or another process may still be using that interface."
        raise SystemExit(msg)


def rgb565(color: str) -> bytes:
    color = color.lower()
    table = {
        'black': (0, 0, 0),
        'white': (255, 255, 255),
        'red': (255, 0, 0),
        'green': (0, 255, 0),
        'blue': (0, 0, 255),
        'yellow': (255, 255, 0),
        'cyan': (0, 255, 255),
        'magenta': (255, 0, 255),
    }
    if color.startswith('#') and len(color) == 7:
        r = int(color[1:3], 16); g = int(color[3:5], 16); b = int(color[5:7], 16)
    else:
        if color not in table:
            raise SystemExit(f"unknown color {color}")
        r, g, b = table[color]
    v = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)
    return v.to_bytes(2, 'big')


def mk_qkontrol_image_packet(screen: int, x: int, y: int, w: int, h: int, color: str) -> bytes:
    pix = rgb565(color) * (w * h)
    p = bytearray.fromhex('84 00')
    p.append(screen & 0xFF)
    p += bytes.fromhex('60 00 00 00 00')
    p += x.to_bytes(2, 'big')
    p += y.to_bytes(2, 'big')
    p += w.to_bytes(2, 'big')
    p += h.to_bytes(2, 'big')
    p += bytes.fromhex('02 00 00 00 00 00')
    p += ((w * h) // 2).to_bytes(2, 'big')
    p += pix
    p += bytes.fromhex('02 00 00 00 03 00 00 00 40 00 00 00')
    return bytes(p)


def chunked_write(dev, ep: int, payload: bytes, packet_size: int = 64, timeout: int = 1000):
    offset = 0
    while offset < len(payload):
        chunk = payload[offset: offset + packet_size]
        wrote = dev.write(ep, chunk, timeout=timeout)
        offset += wrote
    return offset


def single_report(report_id: int, length: int, payload_index: int = None, payload_value: int = 0) -> bytes:
    b = bytearray([report_id & 0xFF]) + bytearray([0] * (length - 1))
    if payload_index is not None and 0 <= payload_index < len(b):
        b[payload_index] = payload_value & 0xFF
    return bytes(b)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--list', action='store_true')
    ap.add_argument('--iface', type=int)
    ap.add_argument('--endpoint', type=lambda s: int(s, 0))
    ap.add_argument('--packet-size', type=int, default=64)
    ap.add_argument('--screen', type=int, default=0)
    ap.add_argument('--x', type=int, default=0)
    ap.add_argument('--y', type=int, default=206)
    ap.add_argument('--width', type=int, default=480)
    ap.add_argument('--height', type=int, default=18)
    ap.add_argument('--color', default='white')
    ap.add_argument('--send-qkontrol-image', action='store_true')
    ap.add_argument('--send-raw-hex')
    ap.add_argument('--send-simple', action='store_true')
    ap.add_argument('--report-id', type=lambda s: int(s, 0), default=0x84)
    ap.add_argument('--length', type=int, default=64)
    ap.add_argument('--payload-index', type=int, default=1)
    ap.add_argument('--payload-value', type=lambda s: int(s, 0), default=0x20)
    ap.add_argument('--skip-claim', action='store_true', help='Do not claim/detach the interface before writing')
    args = ap.parse_args()

    dev = find_dev()
    if args.list:
        list_eps(dev)
        return

    if args.iface is None or args.endpoint is None:
        raise SystemExit('--iface and --endpoint are required unless --list is used')

    ensure_active_configuration(dev)
    claimed = False
    if not args.skip_claim:
        detach_and_claim(dev, args.iface)
        claimed = True
    try:
        if args.send_qkontrol_image:
            payload = mk_qkontrol_image_packet(args.screen, args.x, args.y, args.width, args.height, args.color)
            print(f"Sending qKontrol-style image packet: {len(payload)} bytes to iface {args.iface} ep 0x{args.endpoint:02x}")
            chunked_write(dev, args.endpoint, payload, packet_size=args.packet_size)
        elif args.send_raw_hex:
            payload = bytes.fromhex(args.send_raw_hex)
            print(f"Sending raw payload: {len(payload)} bytes to iface {args.iface} ep 0x{args.endpoint:02x}")
            chunked_write(dev, args.endpoint, payload, packet_size=args.packet_size)
        elif args.send_simple:
            payload = single_report(args.report_id, args.length, args.payload_index, args.payload_value)
            print(f"Sending simple report: {payload.hex(' ')}")
            chunked_write(dev, args.endpoint, payload, packet_size=args.packet_size)
        else:
            raise SystemExit('choose one of --send-qkontrol-image, --send-raw-hex, or --send-simple')
    finally:
        if claimed:
            try:
                usb.util.release_interface(dev, args.iface)
            except Exception:
                pass
        usb.util.dispose_resources(dev)

if __name__ == '__main__':
    main()
