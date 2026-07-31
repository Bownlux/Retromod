---
title: CLI tool
nav_order: 5
---

# CLI

The CLI transforms mods without launching Minecraft. It is useful for servers, modpacks, CI, and compatibility checks.

The development jar does not bundle its dependencies, so run commands from a repository checkout:

```bash
mvn exec:java \
  -Dexec.mainClass="com.retromod.cli.RetromodCli" \
  -Dexec.args="<command> <args>" -q
```

## Common Commands

```bash
# Inspect a mod
-Dexec.args="analyze path/to/mod.jar"

# Transform one mod
-Dexec.args="transform path/to/mod.jar --target 26.2 --verify"

# Transform a folder
-Dexec.args="batch path/to/mods --aot --verify"

# Prepare a Minecraft instance
-Dexec.args="prepare path/to/.minecraft --aot"

# List registered shims
-Dexec.args="shims"

# Compare two version points
-Dexec.args="diff fabric 1.21.1 26.2"
```

Run `--help` for the full command and option list:

```bash
-Dexec.args="--help"
```

## Useful Flags

- `--target <version>` selects the output Minecraft version.
- `--target-loader <loader>` selects an offline loader migration when required.
- `--output <path>` chooses the output jar for supported commands.
- `--verify` reports unresolved references.
- `--aot` prepares cached transforms during batch work.
- `--force` allows a transform despite complexity warnings.

The CLI leaves source jars untouched unless a command explicitly documents an in-place operation. Verification reports are written under `config/retromod/verify-reports/`.

See [Verify Transforms]({{ '/verify-transforms' | relative_url }}) for report details.
