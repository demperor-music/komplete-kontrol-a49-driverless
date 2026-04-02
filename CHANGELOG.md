# Changelog

All notable changes to this project are documented here. The format is based
on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
uses [Semantic Versioning](https://semver.org/).

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

[0.1.0]: ../../releases/tag/v0.1.0
