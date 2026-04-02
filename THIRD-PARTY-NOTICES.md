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

## Display assets

`src/main/resources/icons/` contains bitmap glyph and icon data used to render
the A49's 128x32 display. These data files are bundled for hardware
interoperability and were generated/curated for this project. If you
redistribute a modified version of the project, review the provenance of these
assets for your jurisdiction.
