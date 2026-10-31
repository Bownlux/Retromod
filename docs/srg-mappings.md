---
title: Adding SRG Mappings
nav_order: 13
---

# Adding SRG Mappings

Old Forge mods reference fields such as `f_50069_` and methods such as `m_237113_`. Hosts that run Mojang names need those identifiers translated: Forge 1.20.6 and newer, NeoForge 1.20.2 and newer, and every 26.x host.

The generated dictionary covers mods built for Minecraft 1.14.4 through 1.21.8. Mods for 1.13.2 and 1.14.3 are covered only where an identifier lived on into 1.14.4, because Mojang published no mappings for those versions. Contributions are still useful for corrections and version-specific gaps.

Forge 1.20 to 1.20.4 still run SRG names, but each version numbers some members differently. Retromod translates an old identifier to its Mojang name and then to the host's own identifier, with `target-srg-1.20.1.tsv` and a changes file for each other version.

## File Format

Mappings live in:

```text
src/main/resources/retromod/srg-to-mojang.tsv
```

Each row has three tab-separated columns:

```text
FIELD	f_50069_	STONE
METHOD	m_237113_	literal
```

Use `FIELD` or `METHOD`. Keep keys unique.

## Find the Name

Start with the exact SRG identifier from the crash or verification report. Resolve it against the source Minecraft version using Forge MCPConfig and Mojang's official mappings. Linkie or Parchment can help, but verify the result from primary mapping data before submitting it.

For bulk work, use `scripts/harvest-srg-union.py`. It joins MCPConfig with client and server Mojang mappings and omits identifiers that map to conflicting names across versions.

The host tables come from `scripts/harvest-target-srg.py`. Pass `--base src/main/resources/retromod/target-srg-1.20.1.tsv` to write a version's changes against the 1.20.1 table instead of a full table.

## Verify

Check duplicate keys:

```bash
awk -F'\t' '/^[A-Z]/ {print $1, $2}' \
  src/main/resources/retromod/srg-to-mojang.tsv | sort | uniq -d
```

The command should print nothing.

Run the mapping tests:

```bash
mvn test -Dexec.skip=true -Dtest=SrgUnionCoverageTest
```

Then transform the mod that exposed the gap and confirm the original SRG error is gone.

In the pull request, include the mod, source Minecraft version, mapping source, and test result.

Constructors do not use SRG IDs. A failing `<init>` needs a version shim rather than a dictionary row.
