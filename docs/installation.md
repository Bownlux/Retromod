---
title: Installation
nav_order: 2
---

# Installation

Retromod installs like a normal mod. There is no separate installer.

## Requirements

- Minecraft 1.20-26.2
- Fabric, NeoForge, or Forge
- The Java version required by Minecraft: Java 17 for 1.20-1.20.4, Java 21 for 1.20.5-1.21.x, and Java 25 for 26.x
- Fabric API on Fabric

Download the jar that matches both your loader and Minecraft version from [Modrinth](https://modrinth.com/mod/retromod) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/retromod). Most launchers can install it for you if you search for Retromod there, and they will tell you when a new build is out.

Every build is also on [GitHub Releases](https://github.com/Bownlux/Retromod/releases) if you prefer to grab it directly.

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

Retromod stores originals in `retromod-backups/` or `retromod-input/processed/`.

## Minecraft 26.2

Use **Video Settings > Graphics API > OpenGL**. Retromod selects OpenGL automatically unless you already chose a graphics backend.

## Updating

Replace the old Retromod jar with the new one. Do not keep two Retromod versions in `mods/`. Cache stamps normally invalidate old transforms automatically.

## Uninstalling

Remove the Retromod jar. Restore original mods from the backup folders if needed. Fabric users may also remove `config/fabric_loader_dependencies.json` if stale dependency overrides remain.
