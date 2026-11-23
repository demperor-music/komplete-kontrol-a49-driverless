# How the A49 protocol was reverse-engineered

This document describes how the HID protocol used by the Bitwig extension was
recovered. It is based on the original development workspace, which contained
the capture/probe scripts, pcap captures, display extractors, display mirror,
font/icon tooling and the first userspace MIDI prototypes.

> **AI disclosure:** AI tools were used to generate the reverse-engineering
> utilities in the development workspace: HID capture, staged probing, pcap
> decoding, display painting and mirroring, and font/icon extraction. They
> were **not** used to write the actual Bitwig extension/driver code. The
> protocol hypotheses,
> hardware experiments, verification and final driver implementation were done
> by the project author.

The cleaned-up capture/probe tools are included in
[`../tools/reverse-engineering/`](../tools/reverse-engineering/), and the
display paint program is in [`../tools/display-paint/`](../tools/display-paint/).
Raw capture files are intentionally not committed; the commands below show how
to produce new ones. For the distilled report layouts and packet formats, see
[`PROTOCOL.md`](PROTOCOL.md).

## 1. Scope and starting point

The target is the Native Instruments Komplete Kontrol A49, USB `17cc:1740`. It
exposes two relevant interfaces:

- a standard USB-MIDI interface, which Bitwig already understands;
- a vendor HID interface (interface 2 on Linux), which carries the side-panel
  buttons, the browser encoder, the button LEDs and the OLED display.

The keyboard works as a normal MIDI keyboard without any vendor driver. The
missing parts are the side-panel controls, the display and the LED feedback.
Native Instruments' Komplete Kontrol software normally provides these.

### Timeline

| Date | Milestone |
| --- | --- |
| 2026-03-28 | MIDI bridge and SysEx experiments; raw HID capture and labelling; staged HID probe; LED and USB endpoint probes; first `qkontrol_lite` userspace driver prototypes. |
| 2026-03-30 – 31 | usbmon display captures and the first display extractors (`flex` v1–v4, protocol-aware v5+). |
| 2026-04-01 | Interactive display-paint tool, charmap tool, live display mirror and icon converter. |
| 2026-04-02 | Final HID capture and the first complete Bitwig extension build. |

The A-series documentation mentions that MIDI templates can be synchronized
through the `KOMPLETE KONTROL A25/A49/A61 MIDI` port. That was investigated
first, because a MIDI-only solution would have been much easier to ship.

## 2. First attempt: MIDI and SysEx

The first tools were ordinary MIDI experiments:

| Tool | Purpose |
| --- | --- |
| `a49_bridge.py` | Hardware MIDI input → virtual output with JSON mapping rules, passthrough and logging. |
| `a49_midi_template_lab.py` | Inspect and send messages on the A49 MIDI port; early template-sync work. |
| `a49_midi_sysex_probe.py` | Guided SysEx payload sweep with JSONL/CSV logging. |

The MIDI path is useful for notes and normal MIDI traffic, but it does not
expose the side-panel buttons as general-purpose CCs on this firmware. It also
cannot drive the display or the full LED state. The template/SysEx path was
partially explored but not turned into a general mapping mechanism; the project
moved to the HID interface instead.

The early `a49_bridge.py` and `a49_qkontrol_lite` prototypes were still useful:
they proved the HID-only controls could be translated to MIDI CCs and merged
with the keyboard's normal MIDI stream in a DAW.

## 3. Capturing raw HID input

`a49_hid_capture.py` finds matching `hidraw` nodes through
`/sys/class/hidraw`, reads reports with `select()`, logs them as JSONL and
prompts for a label for each newly seen report. Labels are stored in a JSON
database so the same report is not asked twice.

Typical workflow:

```bash
python3 tools/reverse-engineering/a49_hid_capture.py --list
python3 tools/reverse-engineering/a49_hid_capture.py \
  --device /dev/hidraw6 \
  --raw-log a49_raw_log.jsonl \
  --db a49_labels.json
```

The controls were pressed one at a time, then in combinations (for example
Shift + Play). The resulting input layout:

| Report byte | Meaning |
| ---: | --- |
| 0 | Report ID `0x01` |
| 1 | Shift, Scale, Arp, Undo, Quantize, Ideas, Loop, Metronome |
| 2 | Tempo, Play, Record, Stop, Preset up/down, Left, Right |
| 3 | Browser, Plug-in, Track |
| 5 | Browser knob press |
| 28 | Encoder counter |
| 29 | Octave indicator |

The captured reports are 30 bytes long and arrive on interface 2. A later
capture contained 12,129 reports (all 30 bytes) and was used to check the
decoder against idle traffic. The raw data also showed that the firmware sends
the complete button state in every report, so the decoder keeps the previous
state and emits press/release events for the difference.

Shift combinations were inferred from the labelled captures and then verified
one by one on the device:

```text
Shift + Play      → Restart
Shift + Record    → Count-in
Shift + Stop      → Clear
Shift + Undo      → Redo
Shift + Quantize  → Automation write
Shift + Scale     → Edit scale view
Shift + Arp       → Edit arp
Shift + Plug-in   → MIDI editor
Shift + Track     → Device window
Shift + Left      → Mute
Shift + Right     → Solo
```

The octave indicator is centred on `0x24` with a step of `0x0C`, which is why
the extension can show `-1`, `0`, `+1` transiently without asking Bitwig.

## 4. Finding the LED output

The next problem was output. An exhaustive search of HID report space is not
practical, so `a49_hid_guided_probe.py` uses staged probing:

1. **Stage 1 — report family discovery.** Try plausible report IDs, packet
   lengths and output methods, then ask the operator what changed.
2. **Stage 2 — bitwalk.** Set one bit at a time in a selected payload range.
3. **Stage 3 — byte sweep.** Sweep one byte across a range of values.

The default Stage 1 candidates included report IDs `0x00`, `0x01`, `0x80`,
`0x84`, `0xA1`–`0xA4` and `0xF4`, with lengths 32, 33, 51, 52, 64, 65, 91 and
92. The probe log recorded 58 Stage 1 packets; 7 produced a visible change.
Every useful result was in the `0x80` family:

```text
80 00 00 ...  → all button LEDs off
```

`a49_light_mapper.py` then targeted report `0x80` and swept individual bytes
with values including `0x20`, `0xFF` and `0x00`. The light probe recorded 272
tests, 42 of which produced a visible change, and mapped the individual button
and octave LEDs. The observed values were:

| Value | Meaning |
| ---: | --- |
| `0x00` | Off |
| `0x20` | Dim |
| `0xFF` | Bright |

The final LED report is 91 bytes including the report ID, or 90 bytes of
payload. Packet byte indices 1–21 (payload indices 0–20) cover the buttons and
the firmware-managed octave LEDs.

## 5. Ruling out the MK2/qKontrol image path

The Komplete Kontrol MK2 and qKontrol tools use a different, richer image
protocol. It was worth testing whether the A49 accepted something similar.

`a49_usb_endpoint_probe.py` enumerates the A49's USB interfaces and endpoints,
sends structured packets to non-HID OUT endpoints, reads any IN endpoints and
lets the operator annotate the result. The probe log contains 50 packets on
interface 2, endpoint `0x01`; none produced a visible change on the test unit.

`a49_display_probe.py` separately tested a qKontrol-style packet family
(`0x84`, with RGB565 image data and x/y/width/height fields) through PyUSB. That
path was not adopted: the A49's working display transport turned out to be the
HID `0xE0` report family described below. The original official-app pcap also
contained non-HID OUT traffic (`0x53 0x01 ...` on EP `0x04`, and `0xE0 0x21
...` on EP `0x01`), but it was not needed for the proven Linux-side path.

## 6. Capturing the display

The OLED is effectively **write-only** for this purpose. There is no reliable
way to read the current framebuffer back from the keyboard. The practical
approach was:

1. Capture the official Komplete Kontrol software driving the display through
   Linux `usbmon` and Wireshark.
2. Extract the display packets from the pcap.
3. Reconstruct the frames off-line.
4. Send candidate packets to the keyboard and compare the result with a live
   mirror of the outgoing stream.

The original captures were `a49 wireshark.pcapng`, `usb11.pcap` and
`usb11_only.pcapng`. Capturing needs root or the appropriate `usbmon`
permissions:

```bash
sudo modprobe usbmon
sudo tcpdump -i usbmon1 -w a49-display.pcap
```

### 6.1 The extractor iterations

The display extractor went through several deliberately separate revisions
(`a49_extract_display_flex.py` v1–v4, then
`a49_extract_display_protocol_v5.py` … `v10.py`). This was necessary because
the official capture and the later known-working sender did not use exactly
the same packet layout.

| Version | Purpose / finding |
| --- | --- |
| v1 | Parse usbmon pcap, group `0xE0` chunk A/B packets into frames. |
| v2 | Handle partial/windowed updates; read the visible column count from the header; keep a persistent framebuffer. |
| v3 | Test a four-bank / 31-column segmented layout hypothesis; kept as an experimental decoder. |
| v4 | Integrate the manual post-processing heuristic used to repair early frames. |
| v5 | Replace heuristics with a protocol-aware decoder and presets. |
| v6 | Add per-frame JSON export for the Rust paint tool. |
| v7–v8 | Make packet length, data offset, stride, page order and chunk selectors configurable. |
| v9–v10 | Add stateful, paired and isolated packet modes; add the font-extraction preset. |

The important layouts:

| Property | Official-app pcap | Working full-width sender |
| --- | ---: | ---: |
| Visible width | 124 columns (windowed updates) | 128 columns |
| Packet length | 256 bytes | 265 bytes |
| Header / data offset | 8 bytes | 9 bytes |
| Data bytes per packet | 248 | 256 |
| Pages per chunk | 2 | 2 |
| Chunk A selector | `0x00` (pages 0–1) | `0x00` (pages 0–1) |
| Chunk B selector | `0x02` (pages 2–3) | `0x02` (pages 2–3) |

The final packets used by the extension are 265 bytes:

```text
E0 00 00 00 00 80 00 02 00   [2 pages × 128 bytes]   chunk A
E0 00 00 02 00 80 00 02 00   [2 pages × 128 bytes]   chunk B
```

Each byte is one column in one 8-pixel page. A zero bit is a lit pixel; `0xFF`
is black. A full 128×32 frame is 512 bytes, four pages of 128 bytes, sent as
two packets with a 40 ms gap.

### 6.2 The display paint tool

[`tools/display-paint/`](../tools/display-paint/) is a small egui application
that lets you paint a 1-bit 128×32 canvas, load an image, inspect the packed
bytes, and send the frame over `hidapi`. Its transport settings were the test
bench for the final layout:

- prepare command `A0 07 00`;
- enable command `A0 03 04`;
- shutdown feature `F8 80 00 20 00 01 00 00 00 64 00`;
- chunk A header `E0 00 00 00 00 80 00 02 00`;
- chunk B header `E0 00 00 02 00 80 00 02 00`;
- 265-byte packets, 9-byte data offset, 2 pages per chunk, 40 ms delay.

The paint tool's **Init + Send** button performs the full sequence: read
feature reports `0xF8` and `0xD0`, send `A0 07 00`, replay the ten startup LED
seed frames with 8 ms gaps, send `A0 03 04`, wait 120 ms, then send the current
framebuffer. The final Java service does the same during display initialization.

### 6.3 The display mirror

`a49_display_mirror.py` is a Tkinter application that reconstructs the display
from outgoing `0xE0` packets. It accepts packets over UDP
(`127.0.0.1:17400`), stdin, or live `usbmon`. This was essential for iterating
on the renderer because there is no readback path from the hardware.

The mirror's confirmed settings match the extension: width 128, height 32,
page height 8, stride 128, packet length 265, data offset 9, two pages per
chunk, chunk A selector `0x00`, chunk B selector `0x02`, black means one, LSB
at the top.

## 7. Rebuilding the font and icons

The display font is a bitmap font. It was recovered by pausing the official
software on a character-map screen, capturing the display stream, and cutting
the glyph strip into individual glyphs.

- `a49_extract_display_protocol_v10.py` with the
  `font_extraction_attempt` preset produced 36 isolated frames from the
  capture, including `stitched.json` at 737×13 pixels.
- `a49-charmap-tool-rust` was written to select rectangular regions from the
  stitched strip, label them as glyphs, mark ignored regions, and export the
  final JSON.
- The shipped font contains 95 glyphs in a 756×13 source strip. The renderer
  uses one-pixel letter spacing and falls back to `?` for unknown characters.
- The icons were converted from a flat SVG/PNG icon pack with
  `bitwig_flat_iconpack_to_a49_json.py`. The converter rasterizes, crops,
  scales to 14×14 or 15×14, and writes the monochrome JSON format used by the
  display renderer (`1` = black background, `0` = lit pixel). The original
  manifest lists source paths such as `resources/icons/...`,
  `device_bitwig.svg` and `u-he-preset.svg`, and no license file was supplied
  with the pack. See `THIRD-PARTY-NOTICES.md` for the redistribution caveat.

## 8. From prototype to the Bitwig extension

Before the Java extension, several userspace prototypes validated the mappings
in a DAW:

- `a49_qkontrol_lite_v2.py` read HID, emitted MIDI CCs to a rawmidi device and
  drove the `0x80` LED report.
- v3 added non-blocking shutdown and merged the keyboard's normal MIDI input
  into the same output.
- v4 moved to a mido virtual ALSA port, fixed encoder masking and rawmidi
  blocking behaviour.
- v4.1/v5 continued the merge and routing work.

The Java extension then moved the same logic into Bitwig's controller API:
`A49Decoder` handles the input report, `A49HidService` owns the HID connection,
and `A49DisplayRenderer` renders the OLED. The extension keeps the HID work off
the Bitwig UI thread and drains a small event queue on the controller thread
every 5 ms.

## 9. Tool index

The cleaned-up copies live in
[`../tools/reverse-engineering/`](../tools/reverse-engineering/) and
[`../tools/display-paint/`](../tools/display-paint/).

| File in this repository | Original workspace file | Role |
| --- | --- | --- |
| `display-paint/` | `a49-display-paint-rust/` | Interactive 128×32 paint and HID sender |
| `a49_hid_capture.py` | `a49-bridge/a49_hid_capture.py` | Raw HID input capture and labelling |
| `a49_hid_guided_probe.py` | `a49-bridge/a49_hid_guided_probe.py` | Staged output report probing |
| `a49_light_mapper.py` | `a49-bridge/a49_light_mapper.py` | LED byte mapping for report `0x80` |
| `a49_usb_endpoint_probe.py` | `a49-bridge/a49_usb_endpoint_probe.py` | USB endpoint discovery and probing |
| `a49_display_probe.py` | `a49-bridge/a49_display_probe.py` | qKontrol-style display experiments |
| `a49_extract_display_protocol_v10.py` | `a49-bridge/a49_extract_display_protocol_v10.py` | usbmon display frame extraction |
| `a49_display_mirror.py` | `a49-display-mirror/a49_display_mirror.py` | Live outgoing-packet display mirror |
| `a49_midi_template_lab.py` | `a49-bridge/a49_midi_template_lab.py` | Early MIDI/SysEx template experiments |
| `a49_midi_sysex_probe.py` | `a49-bridge/a49_midi_sysex_probe.py` | Guided SysEx probing |

Additional original workspace material, not copied into this repository:

| Item | Role |
| --- | --- |
| `a49_bridge.py` | Early MIDI bridge with JSON mapping rules |
| `a49_qkontrol_lite_v2.py` … `v5.py` | Userspace HID → MIDI driver prototypes |
| `a49-charmap-tool-rust/` | Glyph selection and charmap export |
| `font-extraction-attempt-10/` | Captured frames, stitched font strip and widths |
| `bitwig_flat_icons_json_14_with_converter_and_pngs/` | Icon rasterizer and converted icons |
| `usb11.pcap`, `usb11_only.pcapng`, `a49 wireshark.pcapng` | USB captures of the official software |

## 10. Reproducing a capture

This is the shortest path to re-derive the mappings. It assumes Linux and a
user with permission to open the A49 `hidraw` node.

1. Close Bitwig and any Native Instruments software.
2. List the device:

   ```bash
   python3 tools/reverse-engineering/a49_hid_capture.py --list
   ```

3. Capture and label input reports:

   ```bash
   python3 tools/reverse-engineering/a49_hid_capture.py \
     --device /dev/hidraw6 \
     --raw-log a49_raw_log.jsonl \
     --db a49_labels.json
   ```

4. Probe output reports in stages. Start with the LED family:

   ```bash
   python3 tools/reverse-engineering/a49_hid_guided_probe.py \
     --hid /dev/hidraw6 --stage1 \
     --methods write,output,feature \
     --lengths 32,33,51,52,64,65,91,92 \
     --report-ids 0x00,0x01,0x80,0x84,0xA1,0xA2,0xA3,0xA4,0xF4
   ```

5. Map the LED bytes:

   ```bash
   python3 tools/reverse-engineering/a49_light_mapper.py \
     --hid /dev/hidraw6 --bytes 1-21
   ```

6. Capture the official display traffic with `usbmon` and decode it:

   ```bash
   sudo modprobe usbmon
   sudo tcpdump -i usbmon1 -w a49-display.pcap
   python3 tools/reverse-engineering/a49_extract_display_protocol_v10.py \
     a49-display.pcap --preset ni_original_pcap -o a49_display_frames
   ```

7. Mirror a live sender while developing:

   ```bash
   python3 tools/reverse-engineering/a49_display_mirror.py --stdin
   ```

The exact options for every tool are available with `--help`. The tools are
development utilities, not supported end-user software. Probing unknown report
families can put the keyboard in an unexpected state; see the warning in
[`../tools/reverse-engineering/README.md`](../tools/reverse-engineering/README.md).

## 11. What the extension does not use

- No Native Instruments code, driver or firmware is bundled or redistributed.
- No firmware upload, template programming or vendor service is used.
- The display is driven from the host; the extension does not read the OLED
  back.
- The A-series template/SysEx path was investigated but is not required by the
  final driver.
- The MK2/qKontrol RGB image protocol is not used.
