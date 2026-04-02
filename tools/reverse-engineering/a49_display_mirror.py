
#!/usr/bin/env python3
import argparse
import json
import os
import queue
import re
import socket
import subprocess
import sys
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

try:
    import tkinter as tk
    from tkinter import filedialog, messagebox
except Exception as exc:
    print(f"Failed to import tkinter: {exc}", file=sys.stderr)
    sys.exit(1)

USBMON_RE = re.compile(r'\b([BCIZ])([io]):(\d+):(\d+):(\d+)\b')

@dataclass
class MirrorConfig:
    width: int = 128
    height: int = 32
    page_height: int = 8
    framebuffer_stride: int = 128
    canvas_offset: int = 0
    packet_length: int = 265
    data_offset: int = 9
    pages_per_chunk: int = 2
    chunk_a_first_page: int = 0
    chunk_b_first_page: int = 2
    black_means_one: bool = True
    invert_bits: bool = False
    lsb_top: bool = True
    reverse_page_order: bool = False

    @property
    def page_count(self) -> int:
        return self.height // self.page_height

    @property
    def framebuffer_size(self) -> int:
        return self.framebuffer_stride * self.page_count

    @property
    def chunk_data_len(self) -> int:
        return self.packet_length - self.data_offset


class A49DisplayState:
    def __init__(self, cfg: MirrorConfig) -> None:
        self.cfg = cfg
        self.fb = bytearray([0xFF if cfg.black_means_one else 0x00] * cfg.framebuffer_size)
        self.packet_count = 0
        self.last_packet_summary = "No packets yet"
        self.last_updated_pages = []

    def clear(self) -> None:
        fill = 0xFF if self.cfg.black_means_one else 0x00
        self.fb[:] = bytes([fill]) * len(self.fb)
        self.packet_count = 0
        self.last_packet_summary = "Cleared"
        self.last_updated_pages = []

    def apply_packet(self, packet: bytes) -> bool:
        payload = normalize_packet(packet, self.cfg)
        if payload is None:
            return False

        selector = payload[3]
        if selector == 0x00:
            first_page = self.cfg.chunk_a_first_page
        elif selector == 0x02:
            first_page = self.cfg.chunk_b_first_page
        else:
            self.last_packet_summary = f"Ignored selector 0x{selector:02X}"
            return False

        data = payload[self.cfg.data_offset:]
        pages = []
        for page_offset in range(self.cfg.pages_per_chunk):
            page = first_page + page_offset
            if page < 0 or page >= self.cfg.page_count:
                continue
            src_start = page_offset * self.cfg.framebuffer_stride
            src_end = src_start + self.cfg.framebuffer_stride
            if src_end > len(data):
                break
            dst_start = page * self.cfg.framebuffer_stride
            dst_end = dst_start + self.cfg.framebuffer_stride
            self.fb[dst_start:dst_end] = data[src_start:src_end]
            pages.append(page)

        self.packet_count += 1
        header = payload[: self.cfg.data_offset]
        self.last_updated_pages = pages
        self.last_packet_summary = (
            f"#{self.packet_count} header={header.hex(' ').upper()} pages={pages} bytes={len(payload)}"
        )
        return True

    def pixel_on_display(self, x: int, y: int) -> int:
        page = y // self.cfg.page_height
        bit = y % self.cfg.page_height
        byte_index = page * self.cfg.framebuffer_stride + (x + self.cfg.canvas_offset)
        if byte_index < 0 or byte_index >= len(self.fb):
            return 0
        value = self.fb[byte_index]
        bit_index = bit if self.cfg.lsb_top else (self.cfg.page_height - 1 - bit)
        raw = (value >> bit_index) & 1
        if self.cfg.invert_bits:
            raw ^= 1
        if self.cfg.black_means_one:
            return 1 if raw == 1 else 0
        return 1 if raw == 0 else 0


def normalize_packet(packet: bytes, cfg: MirrorConfig) -> Optional[bytes]:
    packet = packet.strip()
    if not packet:
        return None

    if packet and packet[0] == 0xE0:
        if len(packet) >= cfg.packet_length:
            return packet[: cfg.packet_length]
        return None

    try:
        text = packet.decode("utf-8", errors="ignore").strip()
    except Exception:
        return None
    if not text:
        return None

    if text.startswith("{"):
        try:
            obj = json.loads(text)
            if isinstance(obj, dict):
                for key in ("packet", "hex", "data"):
                    if key in obj and isinstance(obj[key], str):
                        text = obj[key]
                        break
        except Exception:
            return None

    cleaned = ''.join(ch for ch in text if ch in '0123456789abcdefABCDEF')
    if len(cleaned) < 2 or len(cleaned) % 2 != 0:
        return None
    try:
        raw = bytes.fromhex(cleaned)
    except ValueError:
        return None
    if raw and raw[0] == 0xE0 and len(raw) >= cfg.packet_length:
        return raw[: cfg.packet_length]
    return None


def detect_bus_device(vid_hex: str, pid_hex: str) -> tuple[Optional[int], Optional[int], str]:
    try:
        proc = subprocess.run(
            ["lsusb", "-d", f"{vid_hex}:{pid_hex}"],
            capture_output=True,
            text=True,
            check=False,
        )
    except FileNotFoundError:
        return None, None, "lsusb not found; pass --bus and --device manually"
    out = (proc.stdout or "").strip().splitlines()
    for line in out:
        m = re.search(r"Bus\s+(\d+)\s+Device\s+(\d+):", line)
        if m:
            return int(m.group(1)), int(m.group(2)), line.strip()
    return None, None, "Could not auto-detect bus/device via lsusb"


class PacketListener:
    def __init__(self, q: queue.Queue[bytes], cfg: MirrorConfig) -> None:
        self.q = q
        self.cfg = cfg
        self.stop_event = threading.Event()
        self.threads: list[threading.Thread] = []

    def stop(self) -> None:
        self.stop_event.set()


class UdpListener(PacketListener):
    def __init__(self, q: queue.Queue[bytes], cfg: MirrorConfig, port: int, use_stdin: bool) -> None:
        super().__init__(q, cfg)
        self.port = port
        self.use_stdin = use_stdin

    def start(self) -> None:
        udp_thread = threading.Thread(target=self._udp_loop, name="udp-listener", daemon=True)
        udp_thread.start()
        self.threads.append(udp_thread)
        if self.use_stdin:
            stdin_thread = threading.Thread(target=self._stdin_loop, name="stdin-listener", daemon=True)
            stdin_thread.start()
            self.threads.append(stdin_thread)

    def _udp_loop(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("127.0.0.1", self.port))
        sock.settimeout(0.25)
        while not self.stop_event.is_set():
            try:
                data, _addr = sock.recvfrom(65535)
            except socket.timeout:
                continue
            except OSError:
                break
            self.q.put(data)
        sock.close()

    def _stdin_loop(self) -> None:
        for line in sys.stdin:
            if self.stop_event.is_set():
                break
            line = line.strip()
            if line:
                self.q.put(line.encode("utf-8"))


class UsbmonListener(PacketListener):
    def __init__(
        self,
        q: queue.Queue[bytes],
        cfg: MirrorConfig,
        vid_hex: str,
        pid_hex: str,
        usbmon_file: str,
        bus: Optional[int],
        device: Optional[int],
        require_root_hint: bool = True,
    ) -> None:
        super().__init__(q, cfg)
        self.vid_hex = vid_hex.lower()
        self.pid_hex = pid_hex.lower()
        self.usbmon_file = usbmon_file
        self.bus = bus
        self.device = device
        self.require_root_hint = require_root_hint
        self.status = ""

    def start(self) -> None:
        t = threading.Thread(target=self._usbmon_loop, name="usbmon-listener", daemon=True)
        t.start()
        self.threads.append(t)

    def _usbmon_loop(self) -> None:
        if self.bus is None or self.device is None:
            bus, device, msg = detect_bus_device(self.vid_hex, self.pid_hex)
            self.status = msg
            self.bus = bus
            self.device = device

        candidates = []
        if self.usbmon_file and self.usbmon_file != "auto":
            candidates.append(Path(self.usbmon_file))
        else:
            if self.bus is not None:
                candidates.extend([
                    Path(f"/sys/kernel/debug/usb/usbmon/{self.bus}u"),
                    Path(f"/sys/kernel/debug/usb/usbmon/{self.bus}t"),
                ])
            candidates.extend([
                Path("/sys/kernel/debug/usb/usbmon/0u"),
                Path("/sys/kernel/debug/usb/usbmon/0t"),
            ])

        mon = next((p for p in candidates if p.exists()), None)
        if mon is None:
            self.status = "usbmon file not found. Mount debugfs and load usbmon."
            return

        try:
            f = mon.open("r", encoding="utf-8", errors="ignore")
        except PermissionError:
            self.status = f"Permission denied opening {mon}. Try sudo."
            return
        except OSError as exc:
            self.status = f"Failed to open {mon}: {exc}"
            return

        self.status = f"Reading {mon} bus={self.bus} dev={self.device}"
        try:
            f.seek(0, os.SEEK_END)
        except Exception:
            pass

        while not self.stop_event.is_set():
            line = f.readline()
            if not line:
                self.stop_event.wait(0.02)
                continue
            packet = self._parse_usbmon_line(line)
            if packet is not None:
                self.q.put(packet)
        f.close()

    def _parse_usbmon_line(self, line: str) -> Optional[bytes]:
        m = USBMON_RE.search(line)
        if not m:
            return None
        transfer_type, direction, bus_s, dev_s, _ep_s = m.groups()
        if direction != "o":
            return None
        bus = int(bus_s)
        dev = int(dev_s)
        if self.bus is not None and bus != self.bus:
            return None
        if self.device is not None and dev != self.device:
            return None

        # We only care about actual interrupt/bulk/control OUT payload lines with data.
        if transfer_type not in ("I", "B", "C"):
            return None

        if "=" not in line:
            return None
        hex_part = line.split("=", 1)[1]
        cleaned = ''.join(ch for ch in hex_part if ch in '0123456789abcdefABCDEF')
        if len(cleaned) < 2 or len(cleaned) % 2 != 0:
            return None
        try:
            raw = bytes.fromhex(cleaned)
        except ValueError:
            return None
        if raw and raw[0] == 0xE0 and len(raw) >= self.cfg.packet_length:
            return raw[: self.cfg.packet_length]
        return None


class MirrorApp:
    def __init__(self, root: tk.Tk, cfg: MirrorConfig, packet_q: queue.Queue[bytes], zoom: int, mode_desc: str) -> None:
        self.root = root
        self.cfg = cfg
        self.packet_q = packet_q
        self.zoom = max(2, zoom)
        self.state = A49DisplayState(cfg)
        self.rects = []
        self.hover_var = tk.StringVar(value="x=0 y=0 pixel=0")
        self.packet_var = tk.StringVar(value="Listening…")
        self.mode_var = tk.StringVar(value=mode_desc)
        self._build_ui()
        self.root.after(15, self._poll_packets)

    def _build_ui(self) -> None:
        self.root.title("A49 Display Mirror")
        self.root.geometry(f"{self.cfg.width * self.zoom + 40}x{self.cfg.height * self.zoom + 140}")

        top = tk.Frame(self.root)
        top.pack(fill=tk.X, padx=8, pady=6)
        tk.Button(top, text="Clear", command=self._clear).pack(side=tk.LEFT)
        tk.Button(top, text="Save PNG", command=self._save_png).pack(side=tk.LEFT, padx=(6, 0))
        tk.Label(top, textvariable=self.mode_var).pack(side=tk.RIGHT)

        self.canvas = tk.Canvas(
            self.root,
            width=self.cfg.width * self.zoom,
            height=self.cfg.height * self.zoom,
            bg="#222222",
            highlightthickness=1,
            highlightbackground="#555555",
        )
        self.canvas.pack(padx=8, pady=8)
        self.canvas.bind("<Motion>", self._on_motion)

        for y in range(self.cfg.height):
            row = []
            for x in range(self.cfg.width):
                x0 = x * self.zoom
                y0 = y * self.zoom
                rect = self.canvas.create_rectangle(
                    x0, y0, x0 + self.zoom, y0 + self.zoom,
                    outline="",
                    fill="#000000"
                )
                row.append(rect)
            self.rects.append(row)

        status = tk.Frame(self.root)
        status.pack(fill=tk.X, padx=8, pady=(0, 8))
        tk.Label(status, textvariable=self.hover_var, anchor="w").pack(fill=tk.X)
        tk.Label(status, textvariable=self.packet_var, anchor="w").pack(fill=tk.X)

        self._redraw()

    def _clear(self) -> None:
        self.state.clear()
        self.packet_var.set(self.state.last_packet_summary)
        self._redraw()

    def _save_png(self) -> None:
        try:
            from PIL import Image
        except Exception:
            messagebox.showerror("Pillow missing", "Install pillow to save PNG snapshots.")
            return
        path = filedialog.asksaveasfilename(
            title="Save PNG",
            defaultextension=".png",
            filetypes=[("PNG image", "*.png")],
        )
        if not path:
            return
        img = Image.new("1", (self.cfg.width, self.cfg.height), 1)
        px = img.load()
        for y in range(self.cfg.height):
            for x in range(self.cfg.width):
                px[x, y] = 0 if self.state.pixel_on_display(x, y) else 1
        img.save(path)

    def _on_motion(self, event: tk.Event) -> None:
        x = max(0, min(self.cfg.width - 1, event.x // self.zoom))
        y = max(0, min(self.cfg.height - 1, event.y // self.zoom))
        page = y // self.cfg.page_height
        bit = y % self.cfg.page_height
        byte_index = page * self.cfg.framebuffer_stride + (x + self.cfg.canvas_offset)
        pixel = self.state.pixel_on_display(x, y)
        self.hover_var.set(f"x={x} y={y} pixel={pixel} page={page} bit={bit} byte={byte_index}")

    def _poll_packets(self) -> None:
        updated = False
        while True:
            try:
                pkt = self.packet_q.get_nowait()
            except queue.Empty:
                break
            if self.state.apply_packet(pkt):
                updated = True
                self.packet_var.set(self.state.last_packet_summary)
        if updated:
            self._redraw()
        self.root.after(15, self._poll_packets)

    def _redraw(self) -> None:
        for y in range(self.cfg.height):
            for x in range(self.cfg.width):
                pixel = self.state.pixel_on_display(x, y)
                color = "#FFFFFF" if pixel else "#000000"
                self.canvas.itemconfig(self.rects[y][x], fill=color)


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Mirror the A49 display by sniffing outgoing packets.")
    p.add_argument("--zoom", type=int, default=8)
    p.add_argument("--port", type=int, default=17400)
    p.add_argument("--stdin", action="store_true", help="Also read hex/JSON packets from stdin in UDP mode.")
    p.add_argument("--source", choices=("udp", "usbmon"), default="usbmon")
    p.add_argument("--vid", default="17cc")
    p.add_argument("--pid", default="1740")
    p.add_argument("--bus", type=int, default=None)
    p.add_argument("--device", type=int, default=None)
    p.add_argument("--usbmon-file", default="auto", help="Path to /sys/kernel/debug/usb/usbmon/*u, or auto.")
    return p


def main() -> int:
    args = build_arg_parser().parse_args()

    cfg = MirrorConfig()
    packet_q: queue.Queue[bytes] = queue.Queue()

    if args.source == "udp":
        listener: PacketListener = UdpListener(packet_q, cfg, args.port, args.stdin)
        mode_desc = f"UDP 127.0.0.1:{args.port}" + (" + stdin" if args.stdin else "")
    else:
        listener = UsbmonListener(packet_q, cfg, args.vid, args.pid, args.usbmon_file, args.bus, args.device)
        if args.bus is not None and args.device is not None:
            mode_desc = f"usbmon bus={args.bus} dev={args.device}"
        else:
            mode_desc = f"usbmon VID:PID={args.vid}:{args.pid} (auto-detect)"

    listener.start()

    root = tk.Tk()
    app = MirrorApp(root, cfg, packet_q, args.zoom, mode_desc)

    def on_close() -> None:
        listener.stop()
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", on_close)
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
