---
title: Troubleshooting
nav_order: 9
---

# Troubleshooting

Start with `logs/latest.log`. The first Retromod, loader, or mixin error is usually more useful than the final crash.

## Mods Were Added but Nothing Loaded

Restart after transformation. Fabric needs one launch to transform the jars and another to load them.

Old Fabric mods belong directly in `retromod-input/`, not `mods/` or `retromod-input/processed/`. Successfully transformed jars move to `mods/`.

Use `retromod-input/` for Forge and NeoForge mods whose declared Minecraft range does not include the host. Those loaders can reject an incompatible jar during discovery, before Retromod can transform anything in place. A jar already accepted by the loader may still use the in-place path.

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

## Forge or NeoForge Module Errors

`Modules X and Y export package Z` usually means two jars provide the same library. Remove the duplicate standalone dependency when another mod already bundles it.

If a transformed jar name contains spaces or unusual punctuation, update Retromod. Current builds sanitize names for NeoForge's module loader.

## Old Forge Mod on NeoForge

Forge 1.20.1 and modern NeoForge use substantially different APIs. Retromod bridges selected metadata, registration, event-bus, networking, and removed-class paths, but some mods still need a Forge host. If a mod fails during registry lifecycle, packet delivery, data generation, rendering, or another deep Forge subsystem, try the matching Forge build and include the NeoForge log in a report.

## Cache Looks Stale

Current builds stamp AOT caches and invalidate mismatches automatically. During same-version development runs, delete `config/retromod/aot-cache/` to force a rebuild.

## Reporting a Bug

Include:

1. `logs/latest.log`
2. The verify report, if present
3. A link to the exact mod version
4. `config/retromod/config.json`
5. Minecraft, loader, loader version, Java, and Retromod versions
6. What you expected and what happened

[Open an issue](https://github.com/Bownlux/Retromod/issues/new).
