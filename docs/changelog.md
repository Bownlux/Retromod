---
title: Changelog
nav_order: 15
description: "Highlights from Retromod releases."
---

# Changelog

This page keeps the release history readable. The [full technical changelog](https://github.com/Bownlux/Retromod/blob/main/CHANGELOG.md) lists every fix and regression test.

## 1.3.0 Snapshot Line

### Snapshot 5, in development

- Fixed mod-owned stack-frame merges in the standalone CLI, full AOT worker pool, and recursively nested libraries. Each transform now reads the hierarchy from the jar it is actually rewriting.
- Fixed Patchouli 1.20.1 on Fabric 1.21.11 across its recipe accessors, screen hierarchy, sound accessor, item setup, and old Gson registration. Replaced APIs for ghost buffers, resource-pack books, the animated guide-book model, and its completion predicate are disabled safely; built-in books and normal rendering remain available.
- Fixed constructor transforms repeatedly spilling unmatched overloads and falsely hitting the five-pass redirect-cycle cap. The Arcanus report now transforms without any cap warnings.
- Fixed ENGRAM 0.8 on Fabric 1.21.11 by translating its entity registration, moved player accessor, attribute holders, raw networking channels, world render event, and verifier-sensitive frames. Its optional offscreen screenshot helper still logs a soft warning for a removed render-hand field.
- Fixed Old 2D Items crashing on Fabric 26.2 by translating its removed render-pipeline builder chain, renamed entity vertex format, and legacy custom shader interface.
- Fixed Pathmind aborting its Fabric client initializer after world tick fields became level tick fields and C2S networking events became serverbound events, plus its custom title button crashing the first menu frame after an offline transform.
- Fixed Caelum's removed NeoForge client reload event and registration call, including packaging its required bridge into offline output jars.
- Fixed exact NeoForge Minecraft ranges such as `[1.21.1]` remaining too strict after an offline transform.
- Fixed mod-owned stack-frame merges in Fabric entrypoint wrapping, Forge and NeoForge transforms, and AOT compilation.
- Added a Forge tick-event split bridge for mods whose listeners were rejected after NeoForge made the shared event abstract.
- Fixed old Forge jars with no explicit Minecraft dependency being misidentified and then receiving no shim chain on patch targets such as Forge 1.20.1.
- Restored offline SRG member remapping for Forge and NeoForge mods targeting the unobfuscated 26.x namespace.
- Added release consistency checks so the CLI, AOT cache, presence packet, POM, build script, badge, and changelog cannot silently disagree on the snapshot version.
- Fixed the release script building all 67 distribution jars twice.

### Snapshot 4, August 2, 2026

- Added real mixin bridges for legacy `MobEffect` shadows and custom `Pose` constructor invokers.
- Added a mixin bridge so custom tree decorators, placers, feature sizes, and block state providers register again.
- Fixed a mod's config screen crashing when its config library moved its own API out from under it.
- Fixed a HUD mixin being rejected because it shadowed a method the HUD rework removed.
- Fixed a Forge client tick event that was mapped to a class NeoForge does not have.
- Fixed mixin member repairs that never reached Fabric mods at runtime because they ran before the name remap.
- Fixed precompiled (AOT) mods losing every mixin, because their selectors were never remapped.
- Fixed precompiled mods skipping the refmap, access widener, and datapack repairs the other paths run.
- Fixed mixins inside a bundled library never being repaired.
- Fixed mods whose mixin config is named `modid.mixin.json` getting no mixin repairs at all.
- Fixed `retromod aot --output` being ignored.
- Added Fabric load-time regression mixins for the new repairs.
- Improved bare selector renames and expanded method-level mixin discovery.
- Expanded Forge SRG mappings and completed more 26.x class moves.
- Fixed frame rebuilding for mod-owned class hierarchies and two loader migration gaps.
- Updated CurseForge publishing and compatibility-report storage.

Some reported mods are improved but not finished. Species 2.3 still has a criterion API gap, Double Hotbar's second hotbar needs a real port because 26.x moved hotbar drawing out of the class it hooks, and a Forge mod that listens for tick events still will not start on NeoForge. The full changelog explains each one.

### Snapshot 3, July 25, 2026

- Brought offline Fabric transforms in line with runtime behavior.
- Fixed nested intermediary class names and several 26.x method and descriptor changes.
- Expanded SRG-to-Mojang coverage through Forge 1.21.8.
- Repaired bare-name mixin selectors using their declared target owner.
- Fixed stack-map frame merges for classes owned by the mod being transformed.
- Added more 26.2 class moves, signature guards, and small removed-API replacements.

This snapshot remains in development. Check the full changelog for individual mod reports and known limitations.

### Snapshot 2, July 14, 2026

- Added world-generation mixin repairs for selected 1.21.5 changes.
- Fixed the registry value getter rename used by structure mods.
- Added trailing-parameter mixin handler repair.
- Introduced mixin discovery and ranking tools for corpus work.

### Snapshot 1, July 11, 2026

- Started repairing mixin handler signatures instead of only disabling bad handlers.
- Added the ValueIO save-data adapter.
- Extended selector remapping to every loader and the CLI.

## 1.2.0, July 9, 2026

The general compatibility update:

- Added Forge 26.2 support and the EventBus 6-to-7 bridge.
- Built the first broad Forge-to-NeoForge migration layer.
- Improved 26.x world generation and jar-in-jar transformation.
- Added a transform-level baseline for Forge 1.12.2 mods.
- Expanded support for old Fabric mods on pre-26.1 hosts.
- Launched Probe and strengthened the community compatibility database.

## 1.1.0, June 17, 2026

- Added first-day Minecraft 26.2 support for Fabric and NeoForge.
- Added many Fabric event and functional-interface bridges.
- Added automatic OpenGL preference for translated mods on 26.2.
- Expanded class moves, polyfills, and loader-specific redirects.
- Launched the compatibility database.

## 1.0.1, May 26, 2026

A small maintenance release after 1.0.0. It corrected early packaging and compatibility issues without changing the overall workflow.

## 1.0.0, May 25, 2026

The first stable release:

- Runtime transformation on Fabric, NeoForge, and Forge
- Version shims and removed-API polyfills
- Metadata patching and backups
- AOT caching, CLI tools, and transform verification
- In-game mod selection and settings

Earlier beta and release-candidate details remain in the [full changelog](https://github.com/Bownlux/Retromod/blob/main/CHANGELOG.md).
