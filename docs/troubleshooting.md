---
title: Troubleshooting
nav_order: 9
---

# Troubleshooting

Start with `logs/latest.log`. The first Retromod, loader, or mixin error is usually more useful than the final crash.

## Mods Were Added but Nothing Loaded

Restart after transformation. Every loader needs one launch to translate a new old mod and another to load it.

Old Fabric mods belong directly in `retromod-input/`, not `mods/` or `retromod-input/processed/`. Successfully transformed jars move to `mods/`.

On Forge and NeoForge, old mods can stay in `mods/`. Search `latest.log` for `In-place loading:`. That line counts the mods served from the cache, the ones waiting for translation, the failed ones, and the originals held back. A line starting `Not translating` names a mod whose translation failed before. Retromod does not try it again until Retromod or the jar changes. Update the mod or Retromod, or report the first error from the launch that failed.

With `write_translated_jars` set to `true`, use `retromod-input/` for Forge and NeoForge mods whose declared Minecraft range does not include the host. Those loaders can reject an incompatible jar during discovery, before Retromod can rewrite anything in `mods/`.

Do not put an old Fabric API jar in the input folder as a substitute for the host library. Install one Fabric API build made for the host version in `mods/`. Current Retromod builds archive a staged old Fabric API instead of translating and installing a duplicate.

## Graphics Break on Minecraft 26.2

Choose **Video Settings > Graphics API > OpenGL**. Old mods that render directly through OpenGL may fail on Vulkan.

## The Retromod Button Is Missing

Retromod did not finish loading, the jar is in another instance, or a title-screen mod conflicted with it. Search `latest.log` for `Retromod`. Fabric also requires Fabric API.

## Java Version Errors

- Minecraft 1.20-1.20.4 needs Java 17.
- Minecraft 1.20.5-1.21.x needs Java 21.
- Minecraft 26.x needs Java 25.

`UnsupportedClassVersionError` means the current JVM is older than the class file it tried to load.

## Quilt

Use the Fabric build with the matching Fabric API. On older hosts where Quilted Fabric API is available, it is also supported. The Retromod artifact carries both `fabric.mod.json` and `quilt.mod.json`. There is no separate Quilt jar. Retromod reads Quilt metadata first and updates its declared Minecraft dependency during a transform.

## Verification Reports Missing a Class or Member

Reports live in `config/retromod/verify-reports/`.

- `class_XXXX`, `method_XXXX`, or `field_XXXX` usually means a missing Fabric intermediary mapping.
- `m_NNNNN_` or `f_NNNNN_` usually means a missing Forge SRG mapping.
- A readable missing method may need a version shim.
- A removed class may need a polyfill.

Attach the report to an issue. Contributors can use [Adding SRG Mappings]({{ '/srg-mappings' | relative_url }}) or [Verify Transforms]({{ '/verify-transforms' | relative_url }}).

## Mixin Errors

Errors such as `MixinApplyError`, `InvalidInjectionException`, missing `@Shadow` fields, or `mixinextras$bridge` verification failures mean the target bytecode changed more than a name.

Retromod can redirect many targets and update selected handler signatures. Loader transforms index the installed Minecraft jar automatically. Offline transforms need `--mc-jar <target.jar>` for the same exact target-method checks. Ambiguous overloads, constructors, reordered or removed parameters, changed returns, semantic local captures, and `remap = false` scopes are left for a reviewed bridge. When Retromod cannot repair a known handler safely, it may disable that feature so the rest of the mod can load. File an issue with the first mixin error and the affected mod jar.

For a known fatal handler, advanced users can extend `config/retromod/mixin-blocklist.json`:

```json
{
  "blocked": [
    {
      "mixin": "com/example/mod/mixin/SomeMixin",
      "methods": ["brokenHandler"]
    }
  ]
}
```

Omit `methods` only when the entire mixin must be disabled.

## A Mod Extends a Class Minecraft Removed

`IncompatibleClassChangeError`, or a Retromod log line saying a mod class `extends` something that
does not exist, is final, or is an interface.

Minecraft keeps folding hardcoded subclasses into data components and deleting the old base. A mod
that extended one has nothing left to inherit from, and a rename cannot substitute for a class that
is gone. Retromod names the mod class, the base, and why the base cannot be used.

Retromod supplies a replacement base for some of these, such as the old music disc and enchanted
book item classes. Those mods load, and the behavior the old base carried in its constructor does
not come back: a converted music disc registers and does not play in a jukebox. Where there is no
replacement, the mod needs a real port. Attach the log line to an issue with the mod and its
version.

Offline transforms read this from the Minecraft jar. The CLI uses an installed copy of the target
version when it finds one, and `--mc-jar <target.jar>` names it explicitly.

## Forge or NeoForge Module Errors

`Modules X and Y export package Z` usually means two jars provide the same library. Remove the duplicate standalone dependency when another mod already bundles it.

If a transformed jar name contains spaces or unusual punctuation, update Retromod. Current builds sanitize names for NeoForge's module loader.

## Old Forge Mod on NeoForge

Forge 1.20.1 and modern NeoForge use substantially different APIs. Retromod bridges selected metadata, registration, event-bus, networking, config, menu, and capability paths, but some mods still need a Forge host. If a mod fails in data generation, rendering, enchantments, or another deep Forge subsystem, try the matching Forge build and include the NeoForge log in a report.

## A Very Large Mod Is Skipped

Retromod refuses a jar whose contents exceed 500 MB unpacked, or that holds a single file over 50 MB. The log shows `ZIP total extracted size exceeds limit` or `ZIP entry exceeds`. The limit stops a crafted archive from exhausting memory during a transform. A jar above it stays untransformed, so the loader then rejects it for its declared Minecraft version, which looks like Retromod having done nothing. Very large jars are usually a mod plus bundled assets. Try a build without the bundled resource pack, or an earlier version below the limit.

## Cache Looks Stale

Current builds stamp AOT caches and invalidate mismatches automatically. During same-version development runs, delete `config/retromod/aot-cache/` to force a rebuild.

The Forge and NeoForge translation cache is rebuilt after a Retromod or loader update, or a change to `polyfills_enabled` or `transform_mixins`. To force it, close the game and run `java -jar retromod-1.4.0-snapshot.3-cli.jar cache clear <game-dir>`. `cache status <game-dir>` shows what the cache holds and why a mod failed.

## A Current Mod Requires an Old Mod

On Forge and NeoForge, the first launch can stop with a missing dependency. A current mod requires an old mod that Retromod holds back until it is translated, and the loader checks dependencies before Retromod runs. `latest.log` names the current mod. Move it out of `mods/` for one launch, then put it back after the restart. After three launches that stop this way, Retromod shows the old mod to the loader once, so the loader's own message names it.

## First Launch Stops on an Old Mod

On Forge and NeoForge, the loader can reject an old mod in `mods/` before Retromod runs. Retromod keeps an old mod away from the loader when its metadata shows the loader would reject it, but it cannot check every rule. Launch again. Retromod holds that mod back on the next launch, translates it, and asks for one restart. Old jars that are symbolic links are left to the loader as they are, because Retromod only translates real files.

## Restart After Every Retromod Update

This is expected on Forge and NeoForge. An update translates every old mod in `mods/` again, and the copies load after one restart.

## Translated for Another Minecraft Version

`latest.log` says a jar was translated by Retromod for another Minecraft version. The jar came from another game, or this instance moved to a new version after Retromod wrote it. Retromod leaves it as it is. A jar translated for a newer version is rejected by the loader. One translated for an older version can load with that version's fixes and fail later. Put its original jar in `mods/` instead, so Retromod translates it for this game.

## Turning In-Place Loading Off

Set `write_translated_jars` to `true` in `config/retromod/config.json`, or start Java with `-Dretromod.writeTranslatedJars=true` for one launch. Retromod then rewrites jars in `mods/` as earlier versions did. Old originals that in-place mode handled are rewritten on that launch.

## Reporting a Bug

Include:

1. `logs/latest.log`
2. The verify report, if present
3. On Forge and NeoForge, the CLI output of `cache status <game-dir>`
4. A link to the exact mod version
5. `config/retromod/config.json`
6. Minecraft, loader, loader version, Java, and Retromod versions
7. What you expected and what happened

[Open an issue](https://github.com/Bownlux/Retromod/issues/new).
