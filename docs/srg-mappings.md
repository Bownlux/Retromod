---
title: Adding SRG Mappings
nav_order: 13
---

# Adding SRG Mappings

Old Forge mods reference fields such as `f_50069_` and methods such as `m_237113_`. Modern Mojang-named hosts need those identifiers translated.

The generated dictionary covers Forge targets from 1.16.5 through 1.21.8. Contributions are still useful for corrections and version-specific gaps.

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
