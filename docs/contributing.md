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

See [Adding SRG Mappings]({{ '/srg-mappings' | relative_url }}) and the workflows in `.claude/skills/`.

## Tests

```bash
# Full suite
mvn test -Dexec.skip=true

# One test class
mvn test -Dexec.skip=true -Dtest=SomeTest
```

Bug fixes need a focused unit test. For user-reported issues, also test the affected loader's test mod and the original failing mod when possible. A passing initialization summary is not enough; wait until the title screen or server is stable and check for a new crash report.

Use a loader-specific jar from `build-all.sh` for in-game testing. The raw `-all.jar` can conflict with Fabric's bundled libraries.

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
