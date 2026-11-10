# Third-Party Notices

Retromod is released under the MIT License. It bundles four libraries, ships name tables built from published mappings, and rebuilds removed loader APIs in its own code. Each source keeps its own terms. The license and notice texts that travel with Retromod ship inside its jars under `META-INF/licenses/`.

## Bundled Libraries

Retromod copies these libraries into its jars. Gson, SLF4J, and toml4j are moved under `com.retromod.shaded`, so they never clash with a copy the loader or another mod brings.

| Library | Version | License | License text in the jar |
|---|---|---|---|
| [ASM](https://asm.ow2.io/) | 9.11.1 | BSD-3-Clause | `META-INF/licenses/asm/LICENSE` |
| [Gson](https://github.com/google/gson) | 2.14.0 | Apache-2.0 | `META-INF/licenses/gson/LICENSE` |
| [SLF4J API](https://www.slf4j.org/) | 2.0.20 | MIT | `META-INF/licenses/slf4j/LICENSE` |
| [toml4j](https://github.com/mwanji/toml4j) | 0.7.2 | MIT | `META-INF/licenses/toml4j/LICENSE` |

Copyright holders, as each library states them:

- ASM: Copyright (c) 2000-2011 INRIA, France Telecom. All rights reserved.
- Gson: Copyright (C) 2008 Google Inc.
- SLF4J: Copyright (c) 2004-2023 QOS.ch. All rights reserved.
- toml4j: Copyright (c) 2013-2015 Moandji Ezana.

ASM ships only in the standalone CLI jar. The Fabric, Forge, and NeoForge jars leave it out and use the copy their loader provides. None of the four library jars carries a NOTICE file.

## Mapping and Name Data

Retromod ships tables that match old class, member, and registry names to current ones, so it can translate mods built for older versions. The tables hold names, ids, and short facts. They hold no code and no game assets.

| Source | Used in | Terms as the source publishes them |
|---|---|---|
| [Fabric Intermediary](https://github.com/FabricMC/intermediary) | `intermediary-to-mojang.tsv` | CC0 1.0 Universal |
| Mojang's official mappings | the Mojang names in `intermediary-to-mojang.tsv`, `mojang-class-moves-26.1.tsv`, and the `retromod/srg-*` and `retromod/target-srg-*` tables | the Microsoft notice quoted below |
| [MCPConfig](https://github.com/MinecraftForge/MCPConfig) | the SRG ids in `retromod/srg-to-mojang.tsv`, `retromod/srg-version-drift.tsv`, and `retromod/target-srg-*.tsv` | Copyright (c) 2018 Forge Development LLC, text at `META-INF/licenses/mcpconfig/LICENSE` |
| MCP 1.12.2 names: member names from MCPBot's stable exports, class names from MCP | the 1.12.2 names in `retromod/srg-1.12.2-to-mojang.tsv` and `retromod/forge-1.12.2-class-moves*.tsv` | not recorded in this repository |
| Minecraft, Forge, NeoForge, Fabric API, and GeckoLib jars | event, tag, registry id, texture, model, and class move tables in `retromod/`, such as `forge-eventbus7-cancellable.tsv` and `vanilla-registry-ids.tsv` | names and facts read with `javap` and the scripts under `scripts/` |

Each Mojang mapping file starts with this notice:

> (c) 2020 Microsoft Corporation. These mappings are provided "as-is" and you bear the risk of using them. You may copy and use the mappings for development purposes, but you may not redistribute the mappings complete and unmodified. Microsoft makes no warranties, express or implied, with respect to the mappings provided here. Use and modification of this document or the source code (in any form) of Minecraft: Java Edition is governed by the Minecraft End User License Agreement available at https://account.mojang.com/documents/minecraft_eula.

Retromod's tables keep only the names it needs, joined to other id sets and checked against the game versions it supports.

## Fabric API

Fabric API is developed by FabricMC and released under the Apache License 2.0. The license text ships in every Retromod jar at `META-INF/licenses/fabric-api/LICENSE`. Fabric API publishes no NOTICE file.

Each adapted file keeps its upstream license header and adds one line in this form:

```
Adapted for Retromod from FabricMC/fabric <module>/<path> at <Fabric API version> (Apache-2.0). Changes: <one sentence>.
```

Code that calls Minecraft is never copied. It is written again against the 26.x API.

Every adapted file is listed below. `ThirdPartyAttributionTest` fails when a file carries the adaptation line but is missing here, or the reverse.

| Retromod file | Upstream file | Fabric API version | License |
|---|---|---|---|

No Fabric API file is adapted yet.

## Rebuilt APIs

Retromod rebuilds APIs that newer Minecraft versions and loaders removed, so old mods still find the classes and methods they call:

- Forge: the old event base and event bus, capabilities, registries, `DistExecutor`, `FakePlayer`, HUD overlays, render stages, block and item colors, render type sets, baked models, and ingredient serializers, under `com.retromod.legacyapi.forge`.
- NeoForge: capabilities and the old energy, fluid, and item handlers, under `com.retromod.legacyapi.neoforge`.
- Fabric API: old modules on 26.x hosts, under `com.retromod.loaderapi.fabric`. Quilt mods run through the same Fabric artifact.
- Minecraft members that newer versions removed, and stand-ins for old mod APIs such as Baubles, CoFH, NEI, and Waila.

These classes keep the old names and signatures so old mods link against them. Retromod writes their code from the public signatures and the documented and observed behavior. No code from Minecraft, Forge, NeoForge, Fabric API, or Quilt is included. Forge and NeoForge are licensed under LGPL-2.1, so Retromod reimplements their behavior instead of copying it. A few short error messages use the same wording as Forge's, so old mods that show or match them behave the same.

Retromod ships a `quilt.mod.json` so Quilt Loader can load the Fabric artifact. It includes no Quilt code.

## Build-Only Stubs

The source tree holds compile-only stubs of Minecraft, Forge, NeoForge, and Fabric types in `src/legacyapi/stubs/`, `src/boot/stubs/`, and a few files under `src/main/java` that `pom.xml` excludes from the jar. They declare signatures only, so Retromod builds without those jars, and they are never packaged.
