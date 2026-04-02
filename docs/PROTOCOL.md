# A49 HID protocol notes

These notes document the parts of the keyboard protocol used by the extension.
They were derived from USB captures and are not an official Native Instruments
specification. Firmware updates may change the behavior.

For the capture/probe tooling and the story of how these values were recovered,
see [`REVERSE_ENGINEERING.md`](REVERSE_ENGINEERING.md).

Device: `17cc:1740` (Native Instruments Komplete Kontrol A49), vendor HID
interface. The standard USB-MIDI interface is separate and is not described
here.

## Input report `0x01`

The report is 30 bytes long.

| Offset | Meaning |
| ---: | --- |
| 0 | Report ID `0x01` |
| 1 | Shift, Scale, Arp, Undo, Quantize, Ideas, Loop, Metronome |
| 2 | Tempo, Play, Record, Stop, Preset up, Preset down, Left, Right |
| 3 | Browser, Plug-in, Track |
| 4 | Unknown / unused |
| 5 | Knob press (`0x02`) |
| 6–27 | Not used by the extension (keyboard/firmware data) |
| 28 | Encoder position (4-bit wrapping counter) |
| 29 | Octave indicator |

Buttons are active-high bit masks. The complete bit map is in
`A49Mappings.PHYSICAL_BITS`.

The encoder is decoded by comparing the new 4-bit value with the previous
one. If the new value is the previous value plus one (modulo 16), the encoder
moved one direction; otherwise it is treated as the other direction. This is
sufficient for the A49 because the firmware reports a single-step counter.

The octave indicator is centered on `0x24` with a step of `0x0C`:

```text
offset = (indicator - 0x24) / 0x0C
```

So `0x18` is one octave down, `0x24` is zero, and `0x30` is one octave up.

## LED output report `0x80`

The output report has a 90-byte payload. Values are:

| Value | Meaning |
| ---: | --- |
| `0x00` | Off |
| `0x20` | Dim |
| `0xFF` | Bright |

The action-to-index map is in `A49Mappings.LED_PAYLOAD_INDEX_BY_ACTION`.
Indices 19 and 20 are managed by the firmware (octave LEDs). The extension
sets them to `0x20` by default and to `0xFF` when the octave offset is
negative or positive.

While Shift is held, the extension turns off LEDs that do not have a shifted
function. The set of LEDs that stay visible is
`A49Mappings.SHIFT_LAYER_LED_INDICES`.

On macOS, PureJavaHidApi expects the report ID as part of the data passed to
`setOutputReport`; the service adds it back before writing.

## Display output report `0xE0`

The display is a 128×32 monochrome OLED addressed as four pages of eight
pixels. The framebuffer is 512 bytes, one byte per column per page. A zero bit
is a lit pixel; `0xFF` is black.

A full frame is sent as two packets:

| Packet | Chunk selector | Pages |
| --- | ---: | --- |
| A | `0x00` | 0–1 |
| B | `0x02` | 2–3 |

Each packet is 265 bytes:

```text
[0]     0xE0             report ID
[1]     0x00
[2]     0x00
[3]     chunk selector
[4]     0x00
[5]     0x80
[6]     0x00
[7]     0x02
[8]     0x00
[9..]   two pages of 128 bytes
```

The second packet is sent 40 ms after the first one.

## Display initialization and shutdown

The extension performs this sequence the first time it needs to draw:

1. Read feature report `0xF8` (11 bytes).
2. Read feature report `0xD0` (33 bytes) twice.
3. Send output report `0xA0` with payload `07 00`.
4. Wait 8 ms.
5. Replay the ten startup LED frames (`DISPLAY_STARTUP_LED_SEQUENCE`), 8 ms
   apart.
6. Send output report `0xA0` with payload `03 04`.
7. Wait 120 ms.
8. Send a blank framebuffer.

On shutdown, the extension sends a blank framebuffer and then feature report
`0xF8` with payload:

```text
80 00 20 00 01 00 00 00 64 00
```

## Shift combinations

The physical Shift button is resolved by `A49Decoder` into the logical actions
in `A49Mappings.SHIFT_COMBOS`:

| Shift + | Logical action |
| --- | --- |
| Play | Restart |
| Record | Count-in |
| Stop | Clear |
| Undo | Redo |
| Quantize | Automation write |
| Scale | Edit scale view |
| Arp | Edit arp mode |
| Plug-in | MIDI editor |
| Track | Device window |
| Left | Mute |
| Right | Solo |

## Knob CC pages

The eight knobs can be remapped to one of four CC ranges:

| Page | CCs |
| ---: | --- |
| 1 | 14–21 |
| 2 | 22–29 |
| 3 | 102–109 |
| 4 | 110–117 |

Incoming CCs that match any page range are translated to the currently active
page. All other MIDI messages are passed through.
