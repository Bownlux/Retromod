---
title: Installation
nav_order: 2
---

# Installation

Retromod installs like a normal mod. There is no separate installer.

## Requirements

- A supported host build: Fabric or Quilt on Minecraft 1.20 through 26.3, Forge on 1.20 through 26.3, or NeoForge on 1.20.1 through 26.3
- The Java version required by Minecraft: Java 17 for 1.20-1.20.4, Java 21 for 1.20.5-1.21.x, and Java 25 for 26.x
- Fabric API on Fabric
- The matching Fabric API on Quilt. On older hosts where Quilted Fabric API is available, it is also supported.

Download the jar that matches both your loader and Minecraft version from [Modrinth](https://modrinth.com/mod/retromod) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/retromod). Most launchers can install it for you if you search for Retromod there, and they will tell you when a new build is out.

Every build is also on [GitHub Releases](https://github.com/Bownlux/Retromod/releases) if you prefer to grab it directly.

Choose a jar from the matching loader and Minecraft folder. Quilt uses the Fabric jar for the same Minecraft version. That artifact carries both `fabric.mod.json` and `quilt.mod.json`. There is no separate Quilt jar. The release matrix contains 24 shared Fabric and Quilt jars, 24 Forge jars, and 23 NeoForge jars. The separate `retromod-1.4.0-snapshot.3-cli.jar` is a command-line tool, not a mod, and does not belong in `mods/`. That is 68 loader jars and 1 CLI artifact, or 69 artifacts total.

Before testing a translated mod, copy the instance or back up every world you care about. Retromod keeps your original mod jars, not Minecraft saves. Start with a new test world when the mod changes world generation, registries, inventories, entities, or save data.

## Fabric and Quilt

1. Put Retromod in your instance's `mods/` folder.
2. Launch once, then close the game.
3. Put old mods directly in `retromod-input/`.
4. Launch again and restart when prompted.

Fabric and Quilt check mod versions before Retromod runs. Old mods placed directly in `mods/` may stop the game before they can be transformed. Retromod moves each translated jar to `mods/` and keeps the original in `retromod-input/processed/`. The `write_translated_jars` setting has no effect on Fabric and Quilt.

## Forge and NeoForge

1. Put Retromod in `mods/`.
2. Put old mods in `mods/` as well. `retromod-input/` also works.
3. Launch, and restart when prompted.

Retromod leaves your old jars unchanged. It translates each one into `retromod-cache/translated/` and gives the loader that copy in place of the original. The first launch with a new old mod translates it and asks for one restart. Later launches load the cached copy with no restart. A Retromod or loader update, or a change to `polyfills_enabled` or `transform_mixins`, translates every old mod again and asks for one more restart.

A failed translation is not tried again until Retromod or the jar changes, so a broken mod cannot ask for a restart on every launch. `logs/latest.log` names the jar and the reason. Removing an old mod from `mods/` also removes its translation.

A current mod that requires an old mod Retromod has not translated yet can stop the loader before Retromod runs. Move the current mod out of `mods/` for one launch, then put it back after the restart.

The translations live only in `retromod-cache/`. Delete that folder while the game is closed to translate everything again on the next launch.

### Writing Translated Jars Instead

Set `write_translated_jars` to `true` in `config/retromod/config.json`, or turn on **Write translated jars to mods/** in Retromod's settings, to use the behavior of earlier versions. The change takes effect on the next launch. Retromod then writes translated jars into `mods/`, moves originals from `retromod-input/` to `retromod-input/processed/`, and keeps originals of jars it rewrote inside `mods/` in `mods/retromod-backups/`. Use it if in-place loading misbehaves, or when you want standalone jars. For one launch, such as on a server, start Java with `-Dretromod.writeTranslatedJars=true` instead.

In this mode Forge and NeoForge can reject an old Minecraft range while scanning `mods/`, before Retromod runs. Put such a jar in `retromod-input/`. Retromod can also create `retromod-backups/` at the game-directory root for health recovery.

### Moving to In-Place Loading

Jars that Retromod wrote into `mods/` itself, in rewrite mode or through its file picker, keep loading. Retromod recognizes that output and does not translate it again, so nothing has to change. When an original and its translated jar are both in `mods/`, Retromod loads the translated jar and leaves the original out.

Jars made with the CLI `transform` or `batch` commands carry no such mark. In-place loading translates them a second time into `retromod-cache/`. Retromod does not test a second pass over translated code, so put the original jar in `mods/` instead.

To let in-place loading handle one of those mods:

1. Delete its translated jar from `mods/`. That is `<name>-retromod.jar`, or the jar Retromod rewrote under its original name.
2. Move the original into `mods/`. Earlier versions kept it in `retromod-input/processed/` or `mods/retromod-backups/`.
3. Launch, and restart when prompted.

`java -jar retromod-1.4.0-snapshot.3-cli.jar cache status <game-dir>` lists the translated jars in `mods/` and where each original is.

### Sharing a Translated Mod

`java -jar retromod-1.4.0-snapshot.3-cli.jar export <game-dir>` copies every translated mod from the cache to `<game-dir>/retromod-output/mods/`. Each jar is translated for one loader and Minecraft version. A game you share it with needs that same loader and version, with Retromod installed for them, because a translated jar can call Retromod's code. Put the jar in that game's `mods/`. See [CLI]({{ '/cli' | relative_url }}) for choosing single mods.

## Resource and Data Packs

Put an old resource pack in `retromod-input/resourcepacks/`. Retromod writes the prepared pack to `resourcepacks/` and moves the source to `retromod-input/resourcepacks/processed/` after a successful copy or transform.

Put an old data pack in `retromod-input/datapacks/`. The world is not known during loader startup, so Retromod writes it to `retromod-output/datapacks/`. After a successful copy or transform, the source moves to `retromod-input/datapacks/processed/`. Copy the output into the world's `datapacks/` folder, then run `/reload`.

Pack processing runs on Fabric, Quilt, Forge, and NeoForge. On 26.x targets, data packs receive compatible content migration even when `pack.mcmeta` already advertises the target. Legacy resource packs receive item-definition and content migration only during an actual format upgrade. Retromod does not port arbitrary resource schemas, world generation, overlays, or custom code. Unsupported or newer-only packs stay in the input folder.

## Minecraft 26.2

Use **Video Settings > Graphics API > OpenGL**. Retromod selects OpenGL automatically unless you already chose a graphics backend.

## Updating

Replace the old Retromod jar with the new one. Do not keep two Retromod versions in `mods/`. Cache stamps invalidate old transforms automatically. On Forge and NeoForge, the first launch after an update translates old mods again and asks for one restart.

## Uninstalling

Remove the Retromod jar. On Forge and NeoForge, your old jars in `mods/` were never changed, so without Retromod they are untranslated again. Remove those too, and delete `retromod-cache/` to free the space. Restore original mods from `retromod-input/processed/`, `mods/retromod-backups/`, or the game-directory `retromod-backups/` folder if needed. Fabric users may also remove `config/fabric_loader_dependencies.json` if stale dependency overrides remain.

See [Build Integrity]({{ '/authenticity' | relative_url }}) to compare a downloaded jar with the release SHA-256 manifest.
