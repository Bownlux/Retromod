---
title: CLI tool
nav_order: 5
---

# CLI

The CLI transforms mods without launching Minecraft. It is useful for servers, modpacks, CI, and compatibility checks.

Download `retromod-1.3.0-snapshot.9-cli.jar` from the GitHub release. It bundles its dependencies and has an executable manifest:

```bash
java -jar retromod-1.3.0-snapshot.9-cli.jar <command> <args>
```

From a repository checkout, Maven is a useful fallback:

```bash
mvn exec:java \
  -Dexec.mainClass="com.retromod.cli.RetromodCli" \
  -Dexec.args="<command> <args>" -q
```

## Common Commands

```bash
# Inspect a mod
java -jar retromod-1.3.0-snapshot.9-cli.jar analyze path/to/mod.jar

# Transform one mod and use the exact target jar for safe Mixin repairs
java -jar retromod-1.3.0-snapshot.9-cli.jar transform path/to/mod.jar \
  --target 26.2 --mc-jar path/to/minecraft-26.2-client.jar --verify

# Transform a folder
java -jar retromod-1.3.0-snapshot.9-cli.jar batch path/to/mods \
  --aot --mc-jar path/to/minecraft-26.2-client.jar --verify

# Prepare a Minecraft instance
java -jar retromod-1.3.0-snapshot.9-cli.jar prepare path/to/.minecraft --aot

# List registered shims
java -jar retromod-1.3.0-snapshot.9-cli.jar shims

# Compare two version points
java -jar retromod-1.3.0-snapshot.9-cli.jar diff fabric 1.21.1 26.2
```

Run `--help` for the full command and option list:

```bash
java -jar retromod-1.3.0-snapshot.9-cli.jar --help
```

## Useful Flags

- `--target <version>` selects the output Minecraft version.
- `--target-loader neoforge` enables the selected offline Forge-to-NeoForge migration paths.
- `--output <path>` chooses the output jar for supported commands.
- `--mc-jar <target.jar>` indexes the exact target Minecraft jar for conservative automatic Mixin translation. `transform`, `aot`, and `batch` accept it.
- `--verify` checks generated output jars and writes unresolved-reference reports. With `--mc-jar`, it checks game-owned classes and members against that exact jar instead of the standalone CLI classpath, including those links inside recursively bundled libraries. Minecraft runtime libraries that live beside the client jar, plus optional third-party links used only by nested libraries, are intentionally ignored. In a batch, each generated jar is checked separately.
- `--aot` prepares cached transforms during batch work.
- `--force` allows a transform despite complexity warnings.

If `--target` is omitted, `--mc-jar` also tries to infer the target from the jar's `version.json`. A standard filename such as `minecraft-26.2-client.jar` is the fallback. An explicit `--target` always wins. Without `--mc-jar`, registered shims and mapping tables still run, but the exact target-method analysis is unavailable.

Automatic Mixin translation is intentionally narrow. It can follow a unique current method when the return type is unchanged and the old parameters remain in order with no more than three additions. It can also repair selected zero-capture injections, exact-prefix handler captures, and `@ModifyReturnValue` or `@ModifyExpressionValue` handlers that capture every old target argument after the intercepted value. When a Fabric refmap is the only link between the source selector in an annotation and the current target method, Retromod carries the exact parameter-addition proof from the refmap pass into the class repair without replacing the source selector. It refuses ambiguous overloads, constructors, partial captures, reordered or removed parameters, return-type changes, semantic local captures, unsafe parameter annotations, staticness or owner mismatches, and every `remap = false` scope.

The CLI leaves source jars untouched unless a command explicitly documents an in-place operation. `transform` defaults to a sibling `-transformed.jar`, `batch` defaults to `<input>/retromod-output/`, and `aot` writes its cache under `config/retromod/aot-cache/` unless `--output` is supplied. At startup the CLI initializes `retromod-input/`, `retromod-input/processed/`, and `retromod-backups/` in the current working directory. Reports, full-AOT state, and downloaded API archives also use that working directory, including `config/retromod/verify-reports/`, `retromod-cache/full-aot/`, and `config/retromod/api-archive/`.

See [Verify Transforms]({{ '/verify-transforms' | relative_url }}) for report details.
