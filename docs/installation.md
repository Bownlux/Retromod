---
title: Installation
nav_order: 2
---

# Installation

Retromod installs like a normal mod. There is no separate installer.

## Requirements

- A supported host build: Fabric or Forge on Minecraft 1.20 through 26.2, or NeoForge on Minecraft 1.20.1 through 26.2
- The Java version required by Minecraft: Java 17 for 1.20-1.20.4, Java 21 for 1.20.5-1.21.x, and Java 25 for 26.x
- Fabric API on Fabric

Download the jar that matches both your loader and Minecraft version from [Modrinth](https://modrinth.com/mod/retromod) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/retromod). Most launchers can install it for you if you search for Retromod there, and they will tell you when a new build is out.

Every build is also on [GitHub Releases](https://github.com/Bownlux/Retromod/releases) if you prefer to grab it directly.

Choose a jar from the matching loader and Minecraft folder. The release matrix contains 23 Fabric jars, 23 Forge jars, and 22 NeoForge jars. The separate `retromod-1.3.0-snapshot.6-cli.jar` is a command-line tool, not a mod, and does not belong in `mods/`.

Before testing a translated mod, copy the instance or back up every world you care about. Retromod backs up mod jars, not Minecraft saves. Start with a new test world when the mod changes world generation, registries, inventories, entities, or save data.

## Fabric

1. Put Retromod in your instance's `mods/` folder.
2. Launch once, then close the game.
3. Put old mods directly in `retromod-input/`.
4. Launch again and restart when prompted.

Fabric checks mod versions before Retromod runs. Old mods placed directly in `mods/` may stop the game before they can be transformed.

## Forge and NeoForge

1. Put Retromod in `mods/`.
2. Put old mods in `mods/` or `retromod-input/`.
3. Launch and restart when prompted.

Mods transformed from `retromod-input/` keep their originals in `retromod-input/processed/`. Forge and NeoForge in-place transforms keep originals in `mods/retromod-backups/`. Retromod can also create `retromod-backups/` at the game-directory root for health recovery. These jar backups do not include worlds or configuration files.

## Minecraft 26.2

Use **Video Settings > Graphics API > OpenGL**. Retromod selects OpenGL automatically unless you already chose a graphics backend.

## Updating

Replace the old Retromod jar with the new one. Do not keep two Retromod versions in `mods/`. Cache stamps normally invalidate old transforms automatically.

## Uninstalling

Remove the Retromod jar. Restore original mods from `retromod-input/processed/`, `mods/retromod-backups/`, or the game-directory `retromod-backups/` folder if needed. Fabric users may also remove `config/fabric_loader_dependencies.json` if stale dependency overrides remain.

See [Build Integrity]({{ '/authenticity' | relative_url }}) to compare a downloaded jar with the release SHA-256 manifest.
