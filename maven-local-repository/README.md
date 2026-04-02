# Vendored Maven repository

This directory contains the one dependency that is not available from Maven
Central:

- `purejavahidapi:purejavahidapi:0.0.23`

The jar and pom are kept in the standard Maven repository layout so that a
plain `mvn package` works on a fresh machine without installing anything by
hand. See `THIRD-PARTY-NOTICES.md` and
`LICENSES/BSD-2-Clause-PureJavaHidApi.txt` for attribution and licensing.

Do not add unrelated artifacts here. Dependencies that are available on Maven
Central should be declared normally in `pom.xml`.
