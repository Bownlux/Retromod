---
title: Contributing
nav_order: 12
---

# Contributing

Retromod is open source under the MIT license, and contributions are welcome. Bug fixes, new bridges, mapping corrections, tests, docs, and compatibility reports all help. This page covers how to set up, what a good pull request looks like, and what to keep in mind while working.

## Setup

Retromod requires Java 25 and Maven 3.8+.

```bash
git clone https://github.com/Bownlux/Retromod.git
cd Retromod
mvn package -Dexec.skip=true
```

Always pass `-Dexec.skip=true` during builds so Maven does not run the CLI entry point.

## Tests

```bash
# Full suite
mvn test -Dexec.skip=true

# One test class
mvn test -Dexec.skip=true -Dtest=SomeTest
```

Bug fixes need a focused unit test. For user-reported issues, also test the affected loader's test mod and the original failing mod when possible. A passing initialization summary is not enough. Wait until the title screen or server is stable and check for a new crash report.

Use a loader-specific jar from `build-all.sh` for in-game testing. The raw `-all.jar` can conflict with Fabric's bundled libraries.

The published standalone CLI is executable because it keeps the bundled dependencies:

```bash
java -jar dist/CLI/retromod-1.4.0-snapshot.5-cli.jar --help
```

From a source checkout, `mvn exec:java` remains the fallback while developing.

## Contribution Guidelines

These apply to every pull request.

### Open Pull Requests Against `dev`

Retromod uses three branches. Base your work on `dev` and open the pull request against `dev`.

- `dev` collects changes as they are reviewed: fixes, bridges, docs, and build changes.
- `snapshot` receives `dev` once enough has collected for the next snapshot.
- `main` receives `snapshot` when a snapshot is released. Only 1 pr gets merged into `main` as the entire snapshot.

### Keep Pull Requests Small and Focused

One fix or one feature per pull request. Small pull requests are reviewed faster and are easier to revert if something breaks. Avoid unrelated cleanup, renames, or reformatting in a fix. If you are looking for something to work on, the [issue tracker](https://github.com/Bownlux/Retromod/issues) lists open bugs and compatibility reports.

Good first changes:

- Correct an SRG mapping
- Add a missing class or member redirect
- Improve a confusing error message
- Add a regression test for a reported mod

See [Adding SRG Mappings]({{ '/srg-mappings' | relative_url }}) and the development workflows in `.agents/skills/`.

### Pull Request Titles

Start the title with a type prefix, then a short lowercase summary. The title becomes the commit subject.

| Prefix | Use it for |
|---|---|
| `feat` | A new bridge, shim, polyfill, or user-facing feature |
| `fix` | A bug fix |
| `refactor` | A code change that does not change behavior |
| `perf` | A speed or memory improvement |
| `test` | New or corrected tests only |
| `docs` | Documentation only |
| `build` | Build scripts, Maven, or release packaging |
| `ci` | GitHub Actions workflows |
| `chore` | Other maintenance |
| `revert` | Reverting an earlier change |

Add a loader in parentheses when the change belongs to one loader:

```text
fix: 1.21.5 mob effect signature guards
feat(neoforge): forge permissions api on neoforge
docs: troubleshooting for strict json errors
```

### Pull Request Descriptions

Say what changed, why, and how you tested it. When compatibility is involved, include the source mod and its version, the Minecraft version it was built for, the host version, and the loader. If the pull request fixes an issue, write `Fixes #123` so the issue closes when it is merged.

### Testing

Bug fixes need a focused unit test. For a reported issue, also add a case to the affected loader's test mod under `test-mods/` and run the original failing mod when you can. A pull request whose tests fail in CI is closed automatically, so run the suite before opening it.

## Things to Keep in Mind

### Ask Before Large Changes

Open a thread in [GitHub Discussions](https://github.com/Bownlux/Retromod/discussions) before starting a new subsystem, a large refactor, or a change to the in-game screens. It saves you from building something that does not fit. Questions about how something works, or why something is broken, belong there too.

### Don't Get Discouraged

A pull request can be declined or wait a while for reasons that have nothing to do with its quality: it may overlap other work, not fit the current snapshot, or need a different approach. Review comments say what to change. A declined pull request is not a judgment of you.

### Code Isn't the Only Way to Help

Compatibility reports, bug reports with full logs, testing snapshots, and docs fixes all help. Report bugs in the [issue tracker](https://github.com/Bownlux/Retromod/issues). Report security problems privately as described in [`SECURITY.md`](https://github.com/Bownlux/Retromod/blob/main/SECURITY.md), never in a public issue.

## Style

- Prefer clear names and short methods.
- Comments should explain why, not repeat the code.
- Keep user-facing messages direct and actionable.
- Do not hardcode the host Minecraft version. Use `Retromod.TARGET_MC_VERSION`.
- Keep old shims. Long version chains still depend on them.
- Avoid unrelated cleanup in a bug-fix pull request.

## Resource Pack Mappings

Keep block and item texture mappings in their matching tables. Use basenames without `.png`, directory components, uppercase letters, or resource namespaces. Do not add no-op mappings or send two source names to one destination because a pack containing both sources cannot be transformed safely.

Put post-Flattening full-path moves in `src/main/resources/retromod/texture-migrations.tsv`. The first two columns are the latest source format and earliest target format for the move. Add a move only when official client assets or an upstream migration prove one successor. Do not guess from similar filenames or map a texture whose layout changed.

Add a transform test for the changed names. Include a same-named texture in the other directory when the basename can overlap, and include a matching `.png.mcmeta` file for animated textures. Run `mvn test -Dexec.skip=true -Dtest=ResourcePackTextureMappingInvariantTest,VersionedTexturePathMappingsTest,CliResourcePackCommandTest` before opening the PR.

## Release Distribution

This section is for the maintainer publishing a release. Contributors do not need it.

Keep `SignatureVerifier.EXPECTED_SELF_HASH` empty during development. Finish the release text and every code, provider, and bundled transformation-data edit first. Then build the final shaded jar, compute the self-hash, update the constant programmatically, and rebuild. Generate the distribution from that exact jar:

```bash
mvn clean package -Dexec.skip=true
python3 scripts/compute-self-hash.py target/retromod-1.4.0-snapshot.5-all.jar
# Update EXPECTED_SELF_HASH programmatically, then rebuild.
mvn clean package -Dexec.skip=true
bash build-all.sh --skip-build --require-self-hash
```

The required matrix is 24 Fabric host jars for 1.20 through 26.3, 24 Forge host jars for 1.20 through 26.3, 23 NeoForge host jars for 1.20.1 through 26.3, and one standalone CLI jar. That is 71 loader jars plus the CLI, or 72 artifacts total. `dist/SHA256SUMS.txt` must have one row per artifact.

Validate the complete tree before publishing:

```bash
cd dist
# Linux
sha256sum --check SHA256SUMS.txt
# macOS
shasum -a 256 --check SHA256SUMS.txt
```

Upload only the 71 loader jars to loader-specific Modrinth or CurseForge versions. Publish `dist/CLI/retromod-1.4.0-snapshot.5-cli.jar` and `dist/SHA256SUMS.txt` with the GitHub release.
