# Releasing

Releases are created automatically by
[`.github/workflows/release.yml`](../.github/workflows/release.yml).

The workflow runs on pushes to `main` and only does work when the **head commit
title** matches:

```text
x.y.z release: <summary>
```

Examples:

```text
0.2.0 release: Add track-name scrolling
1.0.0 release: First stable release
```

## Before pushing a release

1. Update the version in `pom.xml`.
2. Update `getVersion()` in
   `src/main/java/dev/demperor/bitwig/a49/KompleteKontrolA49DriverlessExtensionDefinition.java`.
3. Update `CHANGELOG.md`.
4. Commit and push the version bump as normal.
5. Create the release commit as the **last** commit of the push:

   ```bash
   git commit --allow-empty -m "0.2.0 release: Add track-name scrolling"
   git push origin main
   ```

The workflow checks the version in both the POM and the extension definition,
builds the extension, creates tag `vx.y.z`, and publishes a GitHub release with:

- `KompleteKontrolA49Driverless.bwextension`
- `KompleteKontrolA49Driverless-x.y.z.zip` containing the extension, README,
  changelog, license and documentation

The release notes use the summary from the commit title plus GitHub's
automatically generated notes.

## Notes

- Only the head commit of the push is inspected. If you push several commits
  at once, the release commit must be the last one.
- A release fails if the POM version, the extension definition version, or an
  existing tag does not match the version in the commit title.
- To retry a failed release, fix the problem and push an empty commit with the
  same release title, or create the release manually with `gh release create`.
- Non-release commits run the detection job but skip the build and release
  jobs.
