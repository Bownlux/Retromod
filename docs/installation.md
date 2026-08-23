---
title: Installation
nav_order: 2
---

# Installation

Retromod installs like a normal mod. There is no separate installer.

## Requirements

- A supported host build: Fabric, Quilt, or Forge on Minecraft 1.20 through 26.2, or NeoForge on Minecraft 1.20.1 through 26.2
- The Java version required by Minecraft: Java 17 for 1.20-1.20.4, Java 21 for 1.20.5-1.21.x, and Java 25 for 26.x
- Fabric API on Fabric
- The matching Fabric API on Quilt. On older hosts where Quilted Fabric API is available, it is also supported.

Download the jar that matches both your loader and Minecraft version from [Modrinth](https://modrinth.com/mod/retromod) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/retromod). Most launchers can install it for you if you search for Retromod there, and they will tell you when a new build is out.

Every build is also on [GitHub Releases](https://github.com/Bownlux/Retromod/releases) if you prefer to grab it directly.

Choose a jar from the matching loader and Minecraft folder. Quilt uses the Fabric jar for the same Minecraft version. That artifact carries both `fabric.mod.json` and `quilt.mod.json`. There is no separate Quilt jar. The release matrix contains 23 shared Fabric and Quilt jars, 23 Forge jars, and 22 NeoForge jars. The separate `retromod-1.3.0-snapshot.9-cli.jar` is a command-line tool, not a mod, and does not belong in `mods/`. That is 68 loader jars and 1 CLI artifact, or 69 artifacts total.

Before testing a translated mod, copy the instance or back up every world you care about. Retromod backs up mod jars, not Minecraft saves. Start with a new test world when the mod changes world generation, registries, inventories, entities, or save data.

## Fabric and Quilt

1. Put Retromod in your instance's `mods/` folder.
2. Launch once, then close the game.
3. Put old mods directly in `retromod-input/`.
4. Launch again and restart when prompted.

Fabric and Quilt check mod versions before Retromod runs. Old mods placed directly in `mods/` may stop the game before they can be transformed.

## Forge and NeoForge

1. Put Retromod in `mods/`.
2. Put old mods in `retromod-input/`.
3. Launch and restart when prompted.

Forge and NeoForge can reject an old Minecraft version range while scanning `mods/`, before Retromod's entry point runs. Use `retromod-input/` for an incompatible jar or prepare it with the CLI first. In-place transformation from `mods/` remains available only when the loader already accepts the source metadata.

Mods transformed from `retromod-input/` keep their originals in `retromod-input/processed/`. Forge and NeoForge in-place transforms keep originals in `mods/retromod-backups/`. Retromod can also create `retromod-backups/` at the game-directory root for health recovery. These jar backups do not include worlds or configuration files.

## Resource and Data Packs

Put an old resource pack in `retromod-input/resourcepacks/`. Retromod writes the prepared pack to `resourcepacks/` and moves the source to `retromod-input/resourcepacks/processed/` after a successful copy or transform.

Put an old data pack in `retromod-input/datapacks/`. The world is not known during loader startup, so Retromod writes it to `retromod-output/datapacks/`. After a successful copy or transform, the source moves to `retromod-input/datapacks/processed/`. Copy the output into the world's `datapacks/` folder, then run `/reload`.

Pack processing runs on Fabric, Quilt, Forge, and NeoForge. On 26.x targets, data packs receive compatible content migration even when `pack.mcmeta` already advertises the target. Legacy resource packs receive item-definition and content migration only during an actual format upgrade. Retromod does not port arbitrary resource schemas, world generation, overlays, or custom code. Unsupported or newer-only packs stay in the input folder.

## Minecraft 26.2

Use **Video Settings > Graphics API > OpenGL**. Retromod selects OpenGL automatically unless you already chose a graphics backend.

## Updating

Replace the old Retromod jar with the new one. Do not keep two Retromod versions in `mods/`. Cache stamps normally invalidate old transforms automatically.

## Uninstalling

Remove the Retromod jar. Restore original mods from `retromod-input/processed/`, `mods/retromod-backups/`, or the game-directory `retromod-backups/` folder if needed. Fabric users may also remove `config/fabric_loader_dependencies.json` if stale dependency overrides remain.

See [Build Integrity]({{ '/authenticity' | relative_url }}) to compare a downloaded jar with the release SHA-256 manifest.
