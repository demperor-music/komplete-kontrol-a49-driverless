# Changelog

All notable changes to this project are documented here. The format is based
on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Unit tests for the HID decoder, protocol mappings and LED state builder.
- Automatic reconnect after the A49 is unplugged and replugged.
- Display-asset provenance notes in `THIRD-PARTY-NOTICES.md`.

### Changed

- All HID output now runs on a single writer thread; controller-thread
  notifications and LED refreshes no longer block on display packet delays.
- HID-only button action CCs moved from 29–59 to 60–90 to avoid a collision
  between PLAY (CC 29) and the eighth knob on CC page 2. **Existing MIDI
  mappings for those buttons must be updated.**
- Track type, volume and group state now use direct Bitwig API 25 calls instead
  of reflection. `isMaster` remains reflective because it is not in API 25.

### Fixed

- `stop()` blanks the OLED before shutting it down; the display was previously
  never blanked because the initialized flag was cleared too early.
- Encoder decoding now uses a signed modulo-16 delta, so fast spins that skip a
  count no longer reverse direction.
- Quantize actions are invoked through the guarded action helper.
- Documented the actual font and icon provenance instead of claiming the
  display assets were generated for this project.

## [0.1.0] - 2026-11-22

### Added

- Initial open-source release.
- Transport controls: play, stop, record, restart, count-in, loop, metronome,
  tap tempo, undo/redo, quantize, clear and automation write.
- Track selection, mute, solo and Mix view toggle.
- Device navigation, add-before/add-after insertion targets and popup browser
  integration.
- 128×32 OLED display with track status cards, action messages and octave
  indication.
- Long track names and message details scroll horizontally with a ping-pong
  motion instead of wrapping onto a second line.
- LED feedback with dim/bright states and a reduced Shift layer.
- Four switchable knob CC pages and CC output for HID-only actions.
- Bitwig arpeggiator editing for mode, octaves, rate, gate, shuffle and
  overlapping notes.
- Linux udev rule for raw HID access.
- Build, license and third-party attribution files.
- Reverse-engineering notes and AI disclosure.
- `tools/display-paint`, a Rust/egui paint program for the 128×32 OLED,
  including the working A49 transport preset and HID send controls.
- `tools/reverse-engineering`, cleaned-up HID capture, probing and display
  extraction utilities.
- Automated release workflow triggered by `x.y.z release: <summary>` commit
  titles, publishing the `.bwextension` and a documentation zip.

[0.1.0]: ../../releases/tag/v0.1.0
