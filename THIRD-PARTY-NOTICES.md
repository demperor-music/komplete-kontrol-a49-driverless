# Third-Party Notices

The source code of this project is licensed under the MIT License (see
`LICENSE`). The `.bwextension` produced by `mvn package` is a shaded jar and
also contains the following third-party software.

## PureJavaHidApi 0.0.23

- Project: <https://github.com/nyholku/purejavahidapi>
- Copyright (c) 2014, Kustaa Nyholm / SpareTimeLabs
- License: BSD 2-Clause
- Full license text: `LICENSES/BSD-2-Clause-PureJavaHidApi.txt`
- A binary copy is vendored in `maven-local-repository/` because version
  0.0.23 is not published on Maven Central.

## JNA and JNA Platform 5.18.1

- Project: <https://github.com/java-native-access/jna>
- License: dual-licensed under LGPL-2.1-or-later and Apache-2.0. This project
  uses JNA under the Apache License 2.0.
- License notice: `LICENSES/JNA-LICENSE.txt`
- Full Apache License text: `LICENSES/Apache-2.0.txt`

## Bitwig Studio Extension API 25

- The `com.bitwig:extension-api` dependency is used with `provided` scope and
  is not bundled into the built extension. It is loaded from Bitwig Studio at
  runtime.
- See the Controller Scripting Guide in Bitwig Studio under
  **Help → Documentation → Developer Resources**.

## Development tool dependencies

The Rust display paint tool in `tools/display-paint/` is a developer utility
and is not part of the Bitwig extension build. Its dependencies (eframe/egui,
image, rfd, arboard, serde, serde_json and hidapi) are pinned in
`tools/display-paint/Cargo.lock` and are fetched by Cargo. They are not
vendored in this repository and retain their own licenses.

JUnit Jupiter 5.11.4 is used for unit tests with Maven `test` scope. It is not
bundled into the `.bwextension` and retains its own license.

## Display assets

The files in `src/main/resources/icons/` are bitmap data used to render the
A49's 128×32 display. Their provenance is documented here rather than implied:

- `charmap_font.json` is a 95-glyph bitmap font recovered by capturing the
  display output of Native Instruments' official Komplete Kontrol software on a
  character-map screen and cutting the resulting glyph strip into individual
  glyphs. The capture and extraction process is described in
  `docs/REVERSE_ENGINEERING.md`. No claim is made that these glyph bitmaps are
  licensed for redistribution.
- The remaining icon JSON files were rasterized from a "flat icon pack". The
  source paths in the original converter manifest reference Bitwig Studio
  resources such as `resources/icons/...`, `device_bitwig.svg` and
  `u-he-preset.svg`. No license file or grant was supplied with that pack, so
  the redistribution status of these converted icons is unresolved.

These assets are bundled only to drive the A49 hardware. If you need an
unambiguous redistribution story, replace them with original artwork or with
font/icon sets under an explicit open license (for example OFL or CC0) before
publishing derivative releases. See `docs/REVERSE_ENGINEERING.md` for the
original extraction tooling and `src/main/resources/icons/` for the shipped
data.
