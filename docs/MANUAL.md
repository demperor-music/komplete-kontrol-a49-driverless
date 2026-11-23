# User manual

This manual covers installation, the controller layout, MIDI/CC behaviour,
display feedback and troubleshooting.

## 1. What the extension does

The extension turns a Native Instruments Komplete Kontrol A49 into a Bitwig
Studio controller without installing the Komplete Kontrol software or a vendor
driver. It combines two transports:

- **USB-MIDI** for notes and normal keyboard MIDI;
- **raw HID** for the side-panel buttons, browser encoder, button LEDs and the
  128×32 OLED display.

The extension is a normal Bitwig controller extension. It does not modify the
keyboard's firmware or upload templates.

## 2. Requirements

| Item | Requirement |
| --- | --- |
| Bitwig Studio | 6.0 or newer, Extension API 25 |
| Keyboard | Native Instruments Komplete Kontrol A49 (`17cc:1740`) |
| Linux | Access to the A49 `/dev/hidraw*` node |
| Build (optional) | JDK 17+, Maven 3.6.3+ |

Only the A49 has been tested. The A25 and A61 may share part of the protocol
but are not supported. The S-series uses a different integration and is not
supported by this extension.

> Make sure Native Instruments' own A49 controller integration and any other
> A49 extension are disabled. Only one program should drive the HID interface
> at a time.

## 3. Installation

### 3.1 Install the `.bwextension`

Build from source:

```bash
git clone <repository-url>
cd <repository-directory>
mvn clean package
```

The build produces:

```text
target/KompleteKontrolA49Driverless.bwextension
```

Copy it to Bitwig's extension folder:

| Platform | Folder |
| --- | --- |
| Linux | `~/Bitwig Studio/Extensions/` |
| macOS | `~/Documents/Bitwig Studio/Extensions/` |
| Windows | `%USERPROFILE%\Documents\Bitwig Studio\Extensions\` |

On Bitwig 4.2.5 and newer you can also drag the `.bwextension` file onto the
Bitwig window.

If you previously installed an older development build named
`KompleteKontrolA49driverless.bwextension`, remove it first so Bitwig does not
load two copies of the same extension ID.

### 3.2 Linux device permissions

The extension opens `/dev/hidraw*` directly. The device node is root-only by
default, so install the included udev rule
([`packaging/linux/99-komplete-kontrol-a49.rules`](../packaging/linux/99-komplete-kontrol-a49.rules)).
(On systems that use devfs, the same access concept applies; Bitwig's supported
Linux build uses udev.)

```bash
sudo install -Dm644 packaging/linux/99-komplete-kontrol-a49.rules \
  /etc/udev/rules.d/99-komplete-kontrol-a49.rules
sudo udevadm control --reload-rules
sudo udevadm trigger --subsystem-match=hidraw
```

Unplug and reconnect the keyboard afterwards. Verify the node and ACL:

```bash
lsusb -d 17cc:1740
ls -l /dev/hidraw*
getfacl /dev/hidrawX   # replace X with the A49 node
```

If `uaccess` is not available on your system, use the group alternative in the
rule file:

```udev
SUBSYSTEM=="hidraw", ATTRS{idVendor}=="17cc", ATTRS{idProduct}=="1740", MODE="0660", GROUP="plugdev"
```

Then add your user to `plugdev`, log out and back in, and reload the rules.

### 3.3 Flatpak

Install the udev rule on the host first. Then grant the Bitwig Flatpak access
to devices:

```bash
flatpak override --user --device=all com.bitwig.BitwigStudio
```

If the device still cannot be opened, use the native Bitwig package or grant
the device explicitly with Flatseal.

### 3.4 Add the controller in Bitwig

1. Open **Settings → Controllers**.
2. Click **Add Controller**.
3. Choose:
   - **Hardware Vendor:** Native Instruments
   - **Hardware Model:** Komplete Kontrol A49 (driverless)
   - **MIDI Input:** `KOMPLETE KONTROL A49 MIDI 1` (or the matching port on
     your system)
   - **MIDI Output:** none
4. Play a few notes and select a track. The OLED should show the selected
   track and the transport buttons should work.

Auto-detection is currently enabled for Linux. On macOS and Windows, add the
controller manually.

## 4. Controls

### 4.1 Button map

| Control | Press | Shift + press |
| --- | --- | --- |
| Play | Play | Restart |
| Stop | Stop | Clear / remove |
| Record | Record | Toggle count-in (one bar) |
| Loop | Toggle arranger loop | — |
| Metronome | Toggle metronome | — |
| Tempo | Tap tempo | — |
| Undo | Undo | Redo |
| Quantize | Quantize again (MIDI + audio) | Toggle arranger automation write |
| Scale | Cycle Arrange / Clip Launcher | Toggle Edit view |
| Arp | Toggle arpeggiator | Toggle ARP edit mode |
| Ideas | Toggle Mix view | — |
| Browser | Open add-device browser / close browser | — |
| Plug-in | Toggle device panel | Open MIDI editor |
| Track | Toggle mixer | Toggle selected device window |
| Left | Previous knob CC page | Mute selected track |
| Right | Next knob CC page | Solo selected track |
| Preset up / down | Open preset browser; scroll results while the browser is open; change an ARP value in ARP edit mode | — |
| Knob press | Commit browser selection; exit ARP edit mode; browse at the selected insertion point | — |
| Encoder turn | Track selection, browser results, device navigation, ARP setting selection, or loop length while Loop is held | — |

#### Encoder behaviour

The encoder has context-sensitive behaviour, checked in this order:

1. **Popup browser open** — scroll the result list.
2. **Loop held** — resize the arranger loop in one-beat steps (minimum one
   beat).
3. **ARP edit mode** — select the arpeggiator setting.
4. **Device panel active** (and not in Mix view) — move through devices and the
   add-before/add-after targets.
5. **Otherwise** — select the previous/next track.

#### ARP edit mode

Press **Shift + Arp** to enter ARP edit mode. The encoder selects one of:

- enabled;
- mode;
- octaves;
- rate;
- gate;
- shuffle;
- overlapping notes.

Use **Preset up/down** to change the selected value. The OLED shows the current
setting. Press **Shift + Arp** again (or the knob press) to exit.

### 4.2 Knob CC pages

The eight knobs are remapped to one of four CC ranges. Use the **Left** and
**Right** buttons to switch pages.

| Page | CC range |
| --- | --- |
| 1 | 14–21 |
| 2 | 22–29 |
| 3 | 102–109 |
| 4 | 110–117 |

Incoming knob CCs that match any of those ranges are translated to the active
page. All other MIDI is passed through unchanged. Unshifted Left/Right are not
emitted as MIDI because they are used for page switching; Shift+Left/Right
become Mute/Solo and are emitted as CC 89/90.

### 4.3 Extra MIDI CCs for mapping

Each named button action is also emitted as a CC on MIDI channel 1. This makes
the HID-only controls available to Bitwig's MIDI mapping system. The value is
127 on press and 0 on release.

| Action | CC | Action | CC |
| --- | ---: | --- | ---: |
| Play | 60 | Record | 61 |
| Stop | 62 | Loop | 63 |
| Metronome | 64 | Tempo | 65 |
| Preset up | 66 | Preset down | 67 |
| Ideas | 68 | Quantize | 69 |
| Undo | 70 | Arp | 71 |
| Scale | 72 | Shift | 73 |
| Restart | 74 | Count-in | 75 |
| Clear | 76 | Redo | 77 |
| Automation write | 78 | Edit scale | 79 |
| Edit arp | 80 | Browser | 81 |
| Plug-in | 82 | MIDI editor | 83 |
| Track | 84 | Device window | 85 |
| Knob press | 86 | Left | 87 (not emitted) |
| Right | 88 (not emitted) | Mute | 89 |
| Solo | 90 | — | — |

The 60–90 block is deliberately separate from the knob CC ranges, so no
button action shares a CC with a knob.

## 5. Display and LEDs

### 5.1 OLED

The A49's 128×32 monochrome OLED shows:

- a track card with type icon, track name, record-arm/solo/mute state and
  volume;
- transient action cards for transport, browser, device and ARP changes;
- a transient octave indicator when the keyboard's octave offset changes.

Long track names and message details scroll horizontally instead of wrapping
onto a second line. The display ping-pongs between the text ends, pausing
briefly at each end before reversing direction.

The display is rendered locally by `A49DisplayRenderer` and sent over the
keyboard's `0xE0` display report. It is not read back from the device.

### 5.2 LEDs

Known button LEDs are dim by default. Buttons that are physically held or
latched by Bitwig are bright. While Shift is held, only buttons with a shifted
function stay lit. The octave LEDs are managed by the keyboard firmware and
are mirrored by the extension.

## 6. Troubleshooting

### `A49 HID unavailable`

The udev rule is missing or not active, the keyboard was not reconnected after
installing it, or another program has exclusive access.

```bash
sudo udevadm control --reload-rules
sudo udevadm trigger --subsystem-match=hidraw
```

Reconnect the A49 and restart Bitwig. Check `lsusb -d 17cc:1740` and the
permissions/ACL of the matching `/dev/hidraw*` node.

### The extension does not appear in Bitwig

- Put the `.bwextension` in the correct `Extensions` folder.
- Check that the file is not zero bytes.
- Restart Bitwig completely.
- Make sure the Bitwig version supports Extension API 25.

### No display output

The display initializes on the first status message or track selection.

- Confirm that the HID device can be opened.
- Disable any other A49 extension or Native Instruments controller
  integration.
- Restart Bitwig and reselect the controller.

### Knobs do not change CC pages

The incoming knob CC must match one of the four page ranges (14–21, 22–29,
102–109 or 110–117). Use a MIDI monitor to see what the knobs actually send.
Other CCs are passed through without remapping.

### Notes do not play

The extension does not replace Bitwig's MIDI input handling. Make sure the
controller's MIDI input is set to the A49 MIDI port and that no other
controller entry is consuming the same port.

### macOS: HID access denied

macOS may block raw HID access until Bitwig Studio is allowed under **System
Settings → Privacy & Security → Input Monitoring**. Grant access, then quit
and reopen Bitwig.

### Conflicts with Native Instruments software

Do not run the Komplete Kontrol software and this extension at the same time
while testing. Both may try to own the HID interface, LEDs and display.

## 7. Known limitations

- Only the A49 has been tested. A25, A61, M32 and S-series keyboards are not
  supported.
- Linux is the primary platform. macOS and Windows code paths are best-effort.
- The display font and icon set are fixed bitmap data; characters outside the
  bundled set fall back to `?`.
- The extension does not implement Native Instruments' browser, preset
  database, touch strip or hardware arpeggiator UI.
- The protocol is reverse-engineered and is not covered by a stability
  guarantee.
- Unit tests cover the decoder, mappings and LED builder; there are no
  automated hardware-in-the-loop tests.
