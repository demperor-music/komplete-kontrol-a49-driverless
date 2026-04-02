#!/usr/bin/env python3
"""
A49 display extractor v9

Fixes for font-extraction-attempt.pcap:
- supports three extraction modes:
  * paired_frames      : old A+B pairing model
  * stateful_packets   : persistent framebuffer, emit after every display packet
  * isolated_packets   : blank framebuffer per emitted image; pair A+B when adjacent,
                         otherwise emit a single-packet image (best for font extraction)
- restores Rust-project export
- supports loading key=value config copied from the Rust tool
- uses packet length / data offset / stride / page settings from the config
- adds a preset for font-extraction-attempt.pcap

This is intentionally protocol-aware but pragmatic: the original NI pcap and the
later live-sender captures are not identical, so you should choose the preset / mode
that matches the source you are decoding.
"""
from __future__ import annotations

import argparse
import json
import re
import struct
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Dict, Iterator, List, Optional, Sequence, Tuple

try:
    from PIL import Image, ImageDraw
except ImportError as exc:
    raise SystemExit("This script needs Pillow: pip install pillow") from exc

PCAP_GLOBAL_LEN = 24
PCAP_PACKET_HDR_LEN = 16
USBMON_HEADER_LEN = 64


@dataclass
class OutPacket:
    index: int
    ts: float
    report_id: int
    payload: bytes
    raw_payload_len: int
    endpoint: int


@dataclass
class DisplayPacket:
    base: OutPacket
    header: bytes
    data: bytes
    chunk_selector: int
    start_col: Optional[int]
    end_col: Optional[int]


@dataclass
class RenderItem:
    number: int
    kind: str
    packets: List[DisplayPacket]
    image: Image.Image
    stats: List[Dict[str, Any]]


@dataclass
class DecodeConfig:
    width: int = 128
    height: int = 32
    page_height: int = 8
    framebuffer_stride: int = 128
    canvas_offset: int = 0
    visible_start: int = 0
    visible_width: int = 128

    # packet layout
    packet_length: Optional[int] = None
    data_offset: int = 9
    chunk_data_len: Optional[int] = None
    pages_per_chunk: int = 2
    chunk_a_first_page: int = 0
    chunk_b_first_page: int = 2
    chunk_selector_index: int = 3
    chunk_a_value: int = 0x00
    chunk_b_value: int = 0x02
    start_col_index: Optional[int] = 1
    end_col_index: Optional[int] = 5

    # pixel decode
    black_means_one: bool = True
    invert_bits: bool = False
    lsb_top: bool = True
    reverse_page_order: bool = False
    pad_byte: int = 0xFF

    # windowing
    window_from_header: bool = False
    header_full_mode_pairs: Tuple[Tuple[int, int], ...] = ((0x00, 0x80),)
    full_mode_force_start: int = 0
    full_mode_force_width: Optional[int] = 128
    header_window_formula: str = "end_minus_start"  # or empirical_v4
    source_page_span_mode: str = "window_width"  # or header_end

    # mode / pairing
    emit_mode: str = "paired_frames"  # paired_frames / stateful_packets / isolated_packets
    pair_time_window: float = 0.1
    send_chunk_b_expected: bool = True

    # metadata from UI
    data_length: Optional[int] = None
    header_a: Optional[bytes] = None
    header_b: Optional[bytes] = None

    def page_count(self) -> int:
        return max(1, self.height // self.page_height)

    def visible_end(self) -> int:
        return self.visible_start + self.visible_width

    def effective_chunk_data_len(self, actual_payload_len: int) -> int:
        if self.chunk_data_len is not None:
            return self.chunk_data_len
        if self.packet_length is not None:
            return max(0, self.packet_length - self.data_offset)
        return max(0, actual_payload_len - self.data_offset)


PRESETS: Dict[str, DecodeConfig] = {
    "ni_original_pcap": DecodeConfig(
        width=124,
        height=32,
        page_height=8,
        framebuffer_stride=124,
        canvas_offset=0,
        visible_start=0,
        visible_width=124,
        packet_length=256,
        data_offset=8,
        chunk_data_len=248,
        pages_per_chunk=2,
        chunk_a_first_page=0,
        chunk_b_first_page=2,
        chunk_selector_index=3,
        start_col_index=1,
        end_col_index=5,
        black_means_one=True,
        invert_bits=False,
        lsb_top=True,
        reverse_page_order=False,
        pad_byte=0xFF,
        window_from_header=True,
        header_full_mode_pairs=((0x00, 0x80),),
        full_mode_force_start=0,
        full_mode_force_width=124,
        header_window_formula="empirical_v4",
        source_page_span_mode="window_width",
        emit_mode="paired_frames",
        pair_time_window=0.1,
    ),
    "working_sender_confirmed": DecodeConfig(
        width=128,
        height=32,
        page_height=8,
        framebuffer_stride=128,
        canvas_offset=0,
        visible_start=0,
        visible_width=128,
        packet_length=265,
        data_offset=9,
        chunk_data_len=256,
        pages_per_chunk=2,
        chunk_a_first_page=0,
        chunk_b_first_page=2,
        chunk_selector_index=3,
        start_col_index=1,
        end_col_index=5,
        black_means_one=True,
        invert_bits=False,
        lsb_top=True,
        reverse_page_order=False,
        send_chunk_b_expected=True,
        pad_byte=0xFF,
        window_from_header=False,
        header_full_mode_pairs=((0x00, 0x80),),
        full_mode_force_start=0,
        full_mode_force_width=128,
        header_window_formula="end_minus_start",
        source_page_span_mode="window_width",
        emit_mode="paired_frames",
        pair_time_window=0.1,
        data_length=320,
        header_a=bytes.fromhex("E0 00 00 00 00 80 00 02 00"),
        header_b=bytes.fromhex("E0 00 00 02 00 80 00 02 00"),
    ),
    # For font-extraction-attempt.pcap:
    # - capture payloads are 256 bytes total, with 9-byte headers
    # - later packets are mostly single chunk-A partial updates
    # - using isolated packet rendering is more useful than stale stateful bottom pages
    "font_extraction_attempt": DecodeConfig(
        width=128,
        height=32,
        page_height=8,
        framebuffer_stride=128,
        canvas_offset=0,
        visible_start=0,
        visible_width=128,
        packet_length=256,
        data_offset=9,
        chunk_data_len=247,
        pages_per_chunk=2,
        chunk_a_first_page=0,
        chunk_b_first_page=2,
        chunk_selector_index=3,
        start_col_index=1,
        end_col_index=5,
        black_means_one=True,
        invert_bits=False,
        lsb_top=True,
        reverse_page_order=False,
        send_chunk_b_expected=True,
        pad_byte=0xFF,
        window_from_header=True,
        header_full_mode_pairs=((0x00, 0x80),),
        full_mode_force_start=0,
        full_mode_force_width=124,
        header_window_formula="empirical_v4",
        source_page_span_mode="header_end",
        emit_mode="isolated_packets",
        pair_time_window=0.1,
    ),
}


def parse_hex_bytes(text: str) -> bytes:
    cleaned = re.sub(r"[^0-9A-Fa-f]", "", text or "")
    if len(cleaned) % 2 != 0:
        raise ValueError(f"Invalid hex byte string: {text!r}")
    return bytes.fromhex(cleaned)


def load_key_value_config(path: Path) -> Dict[str, Any]:
    out: Dict[str, Any] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or "=" not in line:
            continue
        key, value = line.split("=", 1)
        out[key.strip()] = value.strip()
    return out


def apply_overrides(cfg: DecodeConfig, overrides: Dict[str, Any]) -> DecodeConfig:
    for key, value in overrides.items():
        if not hasattr(cfg, key):
            continue
        current = getattr(cfg, key)
        if key in ("header_a", "header_b"):
            setattr(cfg, key, parse_hex_bytes(value) if value else None)
        elif isinstance(current, bool):
            setattr(cfg, key, str(value).strip().lower() in ("1", "true", "yes", "on"))
        elif isinstance(current, int):
            setattr(cfg, key, int(str(value), 0))
        elif current is None:
            if key in ("packet_length", "chunk_data_len", "data_length"):
                setattr(cfg, key, int(str(value), 0) if str(value).strip() else None)
            else:
                setattr(cfg, key, value)
        else:
            setattr(cfg, key, value)
    return cfg


def iter_pcap_packets(path: Path) -> Iterator[Tuple[float, bytes]]:
    with path.open("rb") as f:
        global_hdr = f.read(PCAP_GLOBAL_LEN)
        if len(global_hdr) != PCAP_GLOBAL_LEN:
            raise ValueError("Not a pcap file or truncated")
        magic = struct.unpack_from("<I", global_hdr, 0)[0]
        if magic != 0xA1B2C3D4:
            raise ValueError(f"Unsupported pcap magic: 0x{magic:08x}")
        while True:
            pkt_hdr = f.read(PCAP_PACKET_HDR_LEN)
            if not pkt_hdr:
                break
            if len(pkt_hdr) != PCAP_PACKET_HDR_LEN:
                raise ValueError("Truncated packet header")
            ts_sec, ts_usec, incl_len, _ = struct.unpack("<IIII", pkt_hdr)
            payload = f.read(incl_len)
            if len(payload) != incl_len:
                raise ValueError("Truncated packet payload")
            yield ts_sec + ts_usec / 1_000_000.0, payload


def parse_usbmon(pkt: bytes) -> Dict[str, Any]:
    if len(pkt) < USBMON_HEADER_LEN:
        raise ValueError("usbmon packet too short")
    hdr = pkt[:USBMON_HEADER_LEN]
    return {
        "type": chr(hdr[8]),
        "xfer": hdr[9],
        "ep": hdr[10],
        "payload": pkt[USBMON_HEADER_LEN:],
    }


def extract_out_packets(path: Path) -> List[OutPacket]:
    out: List[OutPacket] = []
    for index, (ts, raw) in enumerate(iter_pcap_packets(path)):
        pkt = parse_usbmon(raw)
        payload = pkt["payload"]
        if pkt["type"] != "S" or pkt["xfer"] != 1 or pkt["ep"] != 0x01:
            continue
        if not payload:
            continue
        out.append(OutPacket(
            index=index,
            ts=ts,
            report_id=payload[0],
            payload=payload,
            raw_payload_len=len(payload),
            endpoint=pkt["ep"],
        ))
    return out


def as_display_packet(pkt: OutPacket, cfg: DecodeConfig) -> Optional[DisplayPacket]:
    if pkt.report_id != 0xE0:
        return None
    if pkt.raw_payload_len < cfg.data_offset:
        return None

    payload = pkt.payload
    if cfg.packet_length is not None and pkt.raw_payload_len > cfg.packet_length:
        payload = payload[:cfg.packet_length]

    header = payload[:cfg.data_offset]
    data = payload[cfg.data_offset:]
    target_len = cfg.effective_chunk_data_len(len(payload))
    if len(data) < target_len:
        data = data + bytes([cfg.pad_byte]) * (target_len - len(data))
    elif len(data) > target_len:
        data = data[:target_len]

    chunk_selector = header[cfg.chunk_selector_index] if cfg.chunk_selector_index < len(header) else -1
    start_col = header[cfg.start_col_index] if cfg.start_col_index is not None and cfg.start_col_index < len(header) else None
    end_col = header[cfg.end_col_index] if cfg.end_col_index is not None and cfg.end_col_index < len(header) else None
    return DisplayPacket(
        base=pkt,
        header=header,
        data=data,
        chunk_selector=chunk_selector,
        start_col=start_col,
        end_col=end_col,
    )


def bit_is_on(byte_value: int, bit: int, cfg: DecodeConfig) -> bool:
    raw = ((byte_value >> bit) & 1) if cfg.lsb_top else ((byte_value >> (cfg.page_height - 1 - bit)) & 1)
    if cfg.invert_bits:
        raw ^= 1
    return bool(raw) if cfg.black_means_one else not bool(raw)


def determine_window(packet: DisplayPacket, cfg: DecodeConfig) -> Tuple[int, int]:
    if not cfg.window_from_header:
        return cfg.visible_start, min(cfg.framebuffer_stride - cfg.visible_start, cfg.visible_width)

    start_b1 = packet.start_col or 0
    start_b2 = packet.header[2] if len(packet.header) > 2 else 0
    start = start_b1 | start_b2
    end = packet.end_col or start

    if (packet.start_col, packet.end_col) in cfg.header_full_mode_pairs:
        width = cfg.full_mode_force_width or cfg.visible_width
        return cfg.full_mode_force_start, min(width, cfg.framebuffer_stride - cfg.full_mode_force_start)

    if cfg.header_window_formula == "empirical_v4":
        width = max(1, end - 4)
    else:
        width = max(1, end - start)

    start = max(0, min(start, cfg.framebuffer_stride - 1))
    width = min(width, cfg.framebuffer_stride - start)
    return start, width


def determine_source_page_span(packet: DisplayPacket, cfg: DecodeConfig, window_width: int, data_len: int) -> int:
    if cfg.source_page_span_mode == "header_end" and packet.end_col is not None:
        span = int(packet.end_col)
    else:
        span = int(window_width)
    span = max(1, span)
    max_span = max(1, data_len // max(1, cfg.pages_per_chunk))
    return min(span, max_span)


def make_blank_framebuffer(cfg: DecodeConfig) -> bytearray:
    fill = cfg.pad_byte if cfg.pad_byte is not None else 0xFF
    return bytearray([fill] * (cfg.framebuffer_stride * cfg.page_count()))


def decode_chunk_into_framebuffer(packet: DisplayPacket, cfg: DecodeConfig, framebuffer: bytearray) -> Dict[str, Any]:
    page_order = list(range(cfg.pages_per_chunk))
    if cfg.reverse_page_order:
        page_order.reverse()

    first_page = cfg.chunk_a_first_page if packet.chunk_selector == cfg.chunk_a_value else cfg.chunk_b_first_page
    window_start, window_width = determine_window(packet, cfg)
    data = packet.data
    source_page_span = determine_source_page_span(packet, cfg, window_width, len(data))

    consumed = 0
    copy_width = min(window_width, source_page_span)
    for logical_page_offset, page_slot in enumerate(page_order):
        page = first_page + page_slot
        if page < 0 or page >= cfg.page_count():
            continue
        base = page * cfg.framebuffer_stride
        page_src_base = logical_page_offset * source_page_span
        for dx in range(copy_width):
            src_index = page_src_base + dx
            if src_index >= len(data):
                break
            x = window_start + dx
            if 0 <= x < cfg.framebuffer_stride:
                framebuffer[base + x] = data[src_index]
                consumed = max(consumed, src_index + 1)
    return {
        "packet_index": packet.base.index,
        "chunk_selector": packet.chunk_selector,
        "window_start": window_start,
        "window_width": window_width,
        "source_page_span": source_page_span,
        "copy_width": copy_width,
        "data_len": len(data),
        "bytes_consumed": consumed,
    }


def framebuffer_to_image(framebuffer: bytearray, cfg: DecodeConfig) -> Image.Image:
    img = Image.new("L", (cfg.width, cfg.height), 255)
    px = img.load()
    vis_start = max(0, cfg.visible_start)
    vis_end = min(cfg.width, cfg.visible_end())
    x_shift = cfg.canvas_offset
    for page in range(cfg.page_count()):
        base = page * cfg.framebuffer_stride
        for x in range(vis_start, vis_end):
            fb_x = x + x_shift
            if not (0 <= fb_x < cfg.framebuffer_stride):
                continue
            value = framebuffer[base + fb_x]
            for bit in range(cfg.page_height):
                y = page * cfg.page_height + bit
                on = bit_is_on(value, bit, cfg)
                px[x, y] = 0 if on else 255
    return img.convert("1")


def image_to_rust_bundle(image: Image.Image, cfg: DecodeConfig) -> Dict[str, Any]:
    img = image.convert("1")
    pixels: List[int] = []
    for y in range(cfg.height):
        for x in range(cfg.width):
            pixels.append(1 if img.getpixel((x, y)) == 0 else 0)
    return {
        "width": cfg.width,
        "height": cfg.height,
        "pixels": pixels,
        "encoding": {
            "page_height": cfg.page_height,
            "framebuffer_stride": cfg.framebuffer_stride,
            "canvas_offset": cfg.canvas_offset,
            "black_means_one": cfg.black_means_one,
            "invert_bits": cfg.invert_bits,
            "lsb_top": cfg.lsb_top,
            "reverse_page_order": cfg.reverse_page_order,
        },
    }


def find_pair(display_packets: Sequence[DisplayPacket], i: int, cfg: DecodeConfig) -> Optional[int]:
    pkt = display_packets[i]
    if i + 1 >= len(display_packets):
        return None
    nxt = display_packets[i + 1]
    same_window = pkt.start_col == nxt.start_col and pkt.end_col == nxt.end_col and len(pkt.header) == len(nxt.header)
    if nxt.chunk_selector == cfg.chunk_b_value and pkt.chunk_selector == cfg.chunk_a_value and same_window:
        if (nxt.base.ts - pkt.base.ts) <= cfg.pair_time_window:
            return i + 1
    return None


def build_render_items(display_packets: Sequence[DisplayPacket], cfg: DecodeConfig) -> List[RenderItem]:
    items: List[RenderItem] = []
    if cfg.emit_mode == "paired_frames":
        i = 0
        n = 0
        while i < len(display_packets):
            pkt = display_packets[i]
            if pkt.chunk_selector != cfg.chunk_a_value:
                i += 1
                continue
            fb = make_blank_framebuffer(cfg)
            stats = [decode_chunk_into_framebuffer(pkt, cfg, fb)]
            pair_idx = find_pair(display_packets, i, cfg)
            packets_used = [pkt]
            if pair_idx is not None:
                pktb = display_packets[pair_idx]
                stats.append(decode_chunk_into_framebuffer(pktb, cfg, fb))
                packets_used.append(pktb)
                i += 2
            else:
                i += 1
            items.append(RenderItem(number=n, kind="paired_frame", packets=packets_used, image=framebuffer_to_image(fb, cfg), stats=stats))
            n += 1
        return items

    if cfg.emit_mode == "stateful_packets":
        fb = make_blank_framebuffer(cfg)
        for n, pkt in enumerate(display_packets):
            stats = [decode_chunk_into_framebuffer(pkt, cfg, fb)]
            items.append(RenderItem(number=n, kind="stateful_packet", packets=[pkt], image=framebuffer_to_image(fb, cfg), stats=stats))
        return items

    if cfg.emit_mode == "isolated_packets":
        i = 0
        n = 0
        while i < len(display_packets):
            pkt = display_packets[i]
            fb = make_blank_framebuffer(cfg)
            stats = [decode_chunk_into_framebuffer(pkt, cfg, fb)]
            packets_used = [pkt]
            pair_idx = find_pair(display_packets, i, cfg)
            if pair_idx is not None:
                pktb = display_packets[pair_idx]
                stats.append(decode_chunk_into_framebuffer(pktb, cfg, fb))
                packets_used.append(pktb)
                kind = "isolated_pair"
                i += 2
            else:
                kind = "isolated_packet"
                i += 1
            items.append(RenderItem(number=n, kind=kind, packets=packets_used, image=framebuffer_to_image(fb, cfg), stats=stats))
            n += 1
        return items

    raise ValueError(f"Unknown emit mode: {cfg.emit_mode}")


def save_contact_sheet(images: List[Tuple[str, Image.Image]], out_path: Path, scale: int = 1, cols: int = 2, labels: bool = True) -> None:
    if not images:
        return
    tiles: List[Image.Image] = []
    label_margin = 18 if labels else 0
    for label, img in images:
        tile = img.convert("L")
        if scale != 1:
            tile = tile.resize((tile.width * scale, tile.height * scale), Image.NEAREST)
        if labels:
            boxed = Image.new("L", (tile.width, tile.height + label_margin), 255)
            boxed.paste(tile, (0, label_margin))
            ImageDraw.Draw(boxed).text((2, 2), label, fill=0)
            tile = boxed
        tiles.append(tile)
    tile_w, tile_h = tiles[0].size
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new("L", (cols * tile_w, rows * tile_h), 255)
    for idx, tile in enumerate(tiles):
        sheet.paste(tile, ((idx % cols) * tile_w, (idx // cols) * tile_h))
    sheet.save(out_path)


def dump_other_reports(packets: Sequence[OutPacket], outdir: Path) -> None:
    rows = []
    for pkt in packets:
        if pkt.report_id in (0x80, 0xE0):
            continue
        rows.append({
            "index": pkt.index,
            "timestamp": pkt.ts,
            "report_id": f"0x{pkt.report_id:02X}",
            "length": pkt.raw_payload_len,
            "hex": pkt.payload.hex(),
        })
    if rows:
        (outdir / "other_reports.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")


def main() -> None:
    ap = argparse.ArgumentParser(description="Extract A49 display traffic with protocol-aware presets and mixed update modes.")
    ap.add_argument("pcap", help="usbmon-format pcap capture")
    ap.add_argument("-o", "--outdir", default="a49_display_frames_v9", help="output directory")
    ap.add_argument("--preset", default="font_extraction_attempt", choices=sorted(PRESETS.keys()))
    ap.add_argument("--config-file", help="key=value config copied from the Rust tool UI")
    ap.add_argument("--emit-mode", choices=("paired_frames", "stateful_packets", "isolated_packets"))
    ap.add_argument("--window-formula", choices=("empirical_v4", "end_minus_start"))
    ap.add_argument("--packet-length", type=int)
    ap.add_argument("--data-offset", type=int)
    ap.add_argument("--chunk-data-len", type=int)
    ap.add_argument("--pair-time-window", type=float)
    ap.add_argument("--max-frames", type=int, default=0)
    ap.add_argument("--scale", type=int, default=4)
    ap.add_argument("--cols", type=int, default=2)
    ap.add_argument("--contact-sheet-labels", action="store_true")
    ap.add_argument("--write-rust-projects", action="store_true")
    args = ap.parse_args()

    outdir = Path(args.outdir)
    raw_dir = outdir / "frames"
    rust_dir = outdir / "rust_projects"
    raw_dir.mkdir(parents=True, exist_ok=True)
    outdir.mkdir(parents=True, exist_ok=True)

    cfg = DecodeConfig(**asdict(PRESETS[args.preset]))
    if args.config_file:
        cfg = apply_overrides(cfg, load_key_value_config(Path(args.config_file)))
    if args.emit_mode:
        cfg.emit_mode = args.emit_mode
    if args.window_formula:
        cfg.header_window_formula = args.window_formula
    if args.packet_length is not None:
        cfg.packet_length = args.packet_length
    if args.data_offset is not None:
        cfg.data_offset = args.data_offset
    if args.chunk_data_len is not None:
        cfg.chunk_data_len = args.chunk_data_len
    if args.pair_time_window is not None:
        cfg.pair_time_window = args.pair_time_window

    packets = extract_out_packets(Path(args.pcap))
    display_packets = [dp for p in packets if (dp := as_display_packet(p, cfg)) is not None]
    items = build_render_items(display_packets, cfg)
    if args.max_frames > 0:
        items = items[:args.max_frames]

    metadata: List[Dict[str, Any]] = []
    rust_manifest: List[Dict[str, Any]] = []
    tiles: List[Tuple[str, Image.Image]] = []

    print(f"Using preset: {args.preset}")
    if args.config_file:
        print(f"Loaded settings: {args.config_file}")
    print(json.dumps({k: (v.hex() if isinstance(v, bytes) else v) for k, v in asdict(cfg).items()}, indent=2))
    print()
    print(f"{'#':>4} {'kind':>16} {'pkts':>16} {'hdrs':>28}")
    print("-" * 90)

    if args.write_rust_projects:
        rust_dir.mkdir(parents=True, exist_ok=True)

    for item in items:
        pkt_indices = [p.base.index for p in item.packets]
        headers = " | ".join(p.header.hex() for p in item.packets)
        print(f"{item.number:4d} {item.kind:>16} {str(pkt_indices):>16} {headers[:28]:>28}")

        png_name = f"frame_{item.number:03d}_{pkt_indices[0]}" + (f"_{pkt_indices[-1]}" if len(pkt_indices) > 1 else "") + ".png"
        bin_name = png_name.replace(".png", ".bin")
        meta_name = png_name.replace(".png", ".json")

        item.image.resize((item.image.width * args.scale, item.image.height * args.scale), Image.NEAREST).save(raw_dir / png_name)

        # framebuffer export as repacked raw image bytes for convenience
        bundle = image_to_rust_bundle(item.image, cfg)
        (raw_dir / meta_name).write_text(json.dumps({
            "number": item.number,
            "kind": item.kind,
            "packet_indices": pkt_indices,
            "headers": [p.header.hex() for p in item.packets],
            "stats": item.stats,
            "bundle": bundle,
        }, indent=2), encoding="utf-8")

        if args.write_rust_projects:
            rust_name = f"frame_{item.number:03d}.json"
            (rust_dir / rust_name).write_text(json.dumps(bundle, indent=2), encoding="utf-8")
            rust_manifest.append({
                "frame": item.number,
                "kind": item.kind,
                "packet_indices": pkt_indices,
                "file": rust_name,
            })

        metadata.append({
            "number": item.number,
            "kind": item.kind,
            "packet_indices": pkt_indices,
            "headers": [p.header.hex() for p in item.packets],
            "stats": item.stats,
            "png": png_name,
            "json": meta_name,
        })
        tiles.append((f"{item.number}:{item.kind}", item.image))

    (outdir / "frames.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    (outdir / "config.json").write_text(json.dumps({k: (v.hex() if isinstance(v, bytes) else v) for k, v in asdict(cfg).items()}, indent=2), encoding="utf-8")
    if args.write_rust_projects:
        (outdir / "rust_manifest.json").write_text(json.dumps(rust_manifest, indent=2), encoding="utf-8")

    dump_other_reports(packets, outdir)
    save_contact_sheet(tiles, outdir / "contact_sheet.png", scale=args.scale, cols=args.cols, labels=args.contact_sheet_labels)

    print()
    print(f"Extracted {len(items)} rendered images -> {outdir}/")
    if args.write_rust_projects:
        print(f"Rust projects written -> {rust_dir}/")


if __name__ == "__main__":
    main()
