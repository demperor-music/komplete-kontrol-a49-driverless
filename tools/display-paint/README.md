# A49 Display Paint

A small Rust/egui paint program for the Komplete Kontrol A49's 128×32
monochrome OLED. It was used during development to design display frames,
experiment with packet layouts and send images to the keyboard over raw HID.

This is a **developer tool**, not part of the Bitwig extension runtime. The
extension has its own renderer and does not depend on this program. It is part
of this project and is covered by the MIT license.

## Features

- Paint on a 1-bit 128×32 canvas with black, white or toggle brushes.
- Load PNG/JPEG/BMP/GIF/TIFF/WebP images and threshold them onto the canvas.
- Rectangular selection, crop, copy/cut/paste as JSON, clear and invert.
- Save/load project JSON.
- Export the canvas as PNG.
- Export the packed framebuffer as binary or hex.
- Live packet preview showing the exact two `0xE0` display packets.
- Adjustable page height, framebuffer stride, canvas offset, bit order, page
  order and inversion.
- Adjustable HID transport settings (VID/PID, headers, packet length, data
  offset, pages per chunk, chunk page offsets, inter-packet delay and padding).
- "Preset: working A49" restores the known-good A49 display transport.
- Sends prepare, enable, current frame and shutdown commands directly over HID.

## Requirements

- Rust 1.87 or newer (edition 2021).
- Linux: a working desktop toolchain plus HID access. On Debian/Ubuntu the
  usual egui/X11 dependencies and `libhidapi-dev` are needed, for example:

  ```bash
  sudo apt install libhidapi-dev libxkbcommon-dev libx11-xcb-dev \
    libxcb-render0-dev libxcb-shape0-dev libxcb-xfixes0-dev
  ```

- The same udev rule as the Bitwig extension, so the A49 `hidraw` node is
  writable by your user:

  ```bash
  sudo install -Dm644 ../../packaging/linux/99-komplete-kontrol-a49.rules \
    /etc/udev/rules.d/99-komplete-kontrol-a49.rules
  sudo udevadm control --reload-rules
  sudo udevadm trigger --subsystem-match=hidraw
  ```

  Reconnect the keyboard afterwards. See
  [`../../README.md`](../../README.md#2-linux-device-permissions-udev--devfs)
  for details.

## Build and run

```bash
cd tools/display-paint
cargo run --release
```

The release binary is written to `target/release/a49-display-paint`.

## A49 defaults

The tool starts with the A49 canvas and transport preset:

| Setting | Value |
| --- | --- |
| Canvas | 128×32 |
| Page height | 8 |
| Framebuffer stride | 128 |
| Canvas offset | 0 |
| Pixel polarity | `blackMeansOne = true` |
| Bit order | LSB at top |
| Packet length | 265 bytes |
| Data offset | 9 bytes |
| Data length | 320 bytes |
| Pages per chunk | 2 |
| Chunk A | selector `0x00`, pages 0–1 |
| Chunk B | selector `0x02`, pages 2–3 |
| Inter-packet delay | 40 ms |
| VID/PID | `17CC:1740` |
| Prepare | `A0 07 00` |
| Enable | `A0 03 04` |
| Shutdown | `F8 80 00 20 00 01 00 00 00 64 00` |

`Init + Send` primes the display feature reports, sends the prepare command,
replays the ten startup LED frames, sends the enable command, waits 120 ms and
then sends the current frame. The `Send Shutdown` button uses a HID feature
report, matching the extension's shutdown path.

## Workflow

1. Close Bitwig Studio and Native Instruments software so they do not claim the
   HID interface.
2. Start the tool, click **Preset: working A49** if needed.
3. Paint, or use **Open Image** to load an image and threshold it.
4. Click **Init + Send** to initialize the display and show the frame.
5. Use **Copy Hex** / **Save Binary** to move the packed framebuffer into other
   tools.
6. Click **Send Shutdown** when done.

## Canvas semantics

- `black = 1`, `white = 0` in the project model.
- The extracted A49 framebuffer stores a set bit for a black pixel and writes
  `0xFF` for a blank (black) frame.
- Left click/drag uses the selected brush; right click/drag paints the
  opposite.
- The Select tool creates a rectangular selection; use Copy, Cut, Paste, Crop
  or Clear Selection from the toolbar.
- Project JSON stores the canvas, encoding options and transport settings.

## Notes

- The OLED is effectively write-only; the tool cannot read the current
  keyboard display back.
- This tool writes raw HID reports. Do not run it while the Bitwig extension or
  Native Instruments software is driving the same keyboard.
- The transport and encoding controls are intentionally exposed for
  reverse-engineering experiments. The **working A49** preset is the safe
  starting point for normal use.
- For the protocol details and how the display transport was recovered, see
  [`../../docs/PROTOCOL.md`](../../docs/PROTOCOL.md) and
  [`../../docs/REVERSE_ENGINEERING.md`](../../docs/REVERSE_ENGINEERING.md).
