---
title: Contributing
nav_order: 12
---

# Contributing

Small, focused changes are welcome. Open a discussion before starting a large new subsystem.

Contributions are distributed under the MIT license.

## Setup

Retromod requires Java 25 and Maven 3.8+.

```bash
git clone https://github.com/Bownlux/Retromod.git
cd Retromod
mvn package -Dexec.skip=true
```

Always pass `-Dexec.skip=true` during builds so Maven does not run the CLI entry point.

## Good First Changes

- Correct an SRG mapping
- Add a missing class or member redirect
- Improve a failing error message
- Add a regression test for a reported mod

See [Adding SRG Mappings]({{ '/srg-mappings' | relative_url }}) and the development workflows in `.agents/skills/`.

## Tests

```bash
# Full suite
mvn test -Dexec.skip=true

# One test class
mvn test -Dexec.skip=true -Dtest=SomeTest
```

Bug fixes need a focused unit test. For user-reported issues, also test the affected loader's test mod and the original failing mod when possible. A passing initialization summary is not enough; wait until the title screen or server is stable and check for a new crash report.

Use a loader-specific jar from `build-all.sh` for in-game testing. The raw `-all.jar` can conflict with Fabric's bundled libraries.

The published standalone CLI is executable because it keeps the bundled dependencies:

```bash
java -jar dist/CLI/retromod-1.3.0-snapshot.7-cli.jar --help
```

From a source checkout, `mvn exec:java` remains the fallback while developing.

## Release Distribution

Keep `SignatureVerifier.EXPECTED_SELF_HASH` empty during development. Finish the release text and every code, provider, and bundled transformation-data edit first. Then build the final shaded jar, compute the self-hash, update the constant programmatically, and rebuild. Generate the distribution from that exact jar:

```bash
mvn clean package -Dexec.skip=true
python3 scripts/compute-self-hash.py target/retromod-1.3.0-snapshot.7-all.jar
# Update EXPECTED_SELF_HASH programmatically, then rebuild.
mvn clean package -Dexec.skip=true
bash build-all.sh --skip-build --require-self-hash
```

The required matrix is 23 Fabric host jars for 1.20 through 26.2, 23 Forge host jars for 1.20 through 26.2, 22 NeoForge host jars for 1.20.1 through 26.2, and one standalone CLI jar. That is 68 loader jars plus the CLI, or 69 artifacts total. `dist/SHA256SUMS.txt` must have one row per artifact.

Validate the complete tree before publishing:

```bash
cd dist
# Linux
sha256sum --check SHA256SUMS.txt
# macOS
shasum -a 256 --check SHA256SUMS.txt
```

Upload only the 68 loader jars to loader-specific Modrinth or CurseForge versions. Publish `dist/CLI/retromod-1.3.0-snapshot.7-cli.jar` and `dist/SHA256SUMS.txt` with the GitHub release.

## Style

- Prefer clear names and short methods.
- Comments should explain why, not repeat the code.
- Keep user-facing messages direct and actionable.
- Do not hardcode the host Minecraft version. Use `Retromod.TARGET_MC_VERSION`.
- Keep old shims. Long version chains still depend on them.
- Avoid unrelated cleanup in a bug-fix pull request.

## Pull Requests

Describe the failing behavior, the fix, and how it was tested. Include the source mod version, source Minecraft version, host version, and loader when compatibility is involved.

Use a short imperative commit subject, for example:

```text
Add 1.21.5 MobEffect signature guards
```

Questions and design proposals belong in [GitHub Discussions](https://github.com/Bownlux/Retromod/discussions).
