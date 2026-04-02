# Reverse-engineering tools

These are the historical development tools used to recover the A49 HID
protocol. They are **not** required to build or run the Bitwig extension, and
they are not supported as end-user applications. They are part of this project
and are covered by the MIT license.

The process they supported is described in
[`../../docs/REVERSE_ENGINEERING.md`](../../docs/REVERSE_ENGINEERING.md).

## Warning

These tools write raw HID/USB reports to a real keyboard. Probing unknown
report families can leave the device in an unexpected state. Before using
them:

- Save your work and close Bitwig Studio and Native Instruments software.
- Disconnect anything else that may claim the A49 HID interface.
- Be ready to unplug the keyboard.
- Start with passive capture, then use the guided/staged probes. Do not run a
  brute-force sweep over the whole report space.

## Dependencies

Most tools use only the Python standard library plus Linux `hidraw`/`usbmon`.
Additional tools need:

```bash
# USB endpoint probing and qKontrol-style experiments
python3 -m pip install pyusb

# Display pcap extraction (Pillow)
python3 -m pip install pillow

# MIDI/SysEx probes
python3 -m pip install mido python-rtmidi
```

The display mirror uses Tkinter, which is part of most Python distributions.

## Tools

| Tool | Purpose |
| --- | --- |
| `a49_hid_capture.py` | Enumerate the A49 `hidraw` nodes, log raw input reports to JSONL and interactively label them. |
| `a49_hid_guided_probe.py` | Staged output-report discovery: report family/length sweep, bitwalk, byte sweep, with interactive effect annotation. |
| `a49_light_mapper.py` | Focused probe for the `0x80` LED output report; maps individual button/octave LED bytes. |
| `a49_usb_endpoint_probe.py` | Enumerate USB interfaces and endpoints and send structured test packets to non-HID endpoints. |
| `a49_display_probe.py` | PyUSB experiments for the display path, including a qKontrol-style RGB565 image packet hypothesis. |
| `a49_extract_display_protocol_v10.py` | Protocol-aware usbmon pcap decoder for the `0xE0` display stream. |
| `a49_display_mirror.py` | Live 128x32 mirror reconstructed from outgoing `0xE0` packets (UDP, stdin or usbmon). |
| `a49_midi_template_lab.py` | Inspect and send MIDI/SysEx on the A49 MIDI port; early template-sync experiments. |
| `a49_midi_sysex_probe.py` | Guided SysEx payload sweep with JSONL/CSV logging. |

The original development workspace also contained the `a49_qkontrol_lite`
userspace driver prototypes, the `a49-charmap-tool` application, the
`font-extraction-attempt-10` frames, and the `usb11.pcap` /
`a49 wireshark.pcapng` captures. They are described in the
reverse-engineering document. Raw captures are intentionally not committed.

## Related tools

- [`../display-paint/`](../display-paint/) — Rust/egui paint program for the
  128×32 OLED, with live packet preview and HID send buttons.

The display paint tool is also a reverse-engineering tool, but it lives in its
own directory because it is useful on its own for designing display frames.

## Typical workflow

```bash
# 1. Identify the HID node
python3 a49_hid_capture.py --list

# 2. Capture input reports and label the controls
python3 a49_hid_capture.py \
  --device /dev/hidraw6 \
  --raw-log a49_raw_log.jsonl \
  --db a49_labels.json

# 3. Discover output report families (start with the LED family)
python3 a49_hid_guided_probe.py \
  --hid /dev/hidraw6 \
  --stage1 \
  --methods write,output,feature \
  --lengths 32,33,51,52,64,65,91,92 \
  --report-ids 0x00,0x01,0x80,0x84,0xA1,0xA2,0xA3,0xA4,0xF4

# 4. Map the LED bytes
python3 a49_light_mapper.py --hid /dev/hidraw6 --bytes 1-21

# 5. Capture official display traffic through usbmon (requires root)
sudo modprobe usbmon
sudo tcpdump -i usbmon1 -w a49-display.pcap

# 6. Decode the display stream
python3 a49_extract_display_protocol_v10.py \
  a49-display.pcap \
  --preset ni_original_pcap \
  -o a49_display_frames

# 7. Mirror outgoing display packets while developing
python3 a49_display_mirror.py --stdin
```

Exact command-line options are available with `--help`.
