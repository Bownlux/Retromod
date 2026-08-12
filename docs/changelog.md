---
title: Changelog
nav_order: 15
description: "Highlights from Retromod releases."
---

# Changelog

This page keeps the release history readable. The [full technical changelog](https://github.com/Bownlux/Retromod/blob/main/CHANGELOG.md) lists every fix and regression test.

## 1.3.0 Snapshot Line

### Snapshot 7, August 11, 2026

- Fixed parameter-capturing Fabric mixins whose annotation keeps a Yarn source selector while the refmap alone identifies the changed target method.
- Carries an exact, unique target parameter-addition proof from refmap remapping into the matching class repair, while preserving the source selector that Mixin expects.
- Refuses ambiguous targets, `remap = false`, unsafe parameter annotations, staticness mismatches, and owner mismatches instead of guessing.
- Uses the same repair context for runtime transforms, the CLI, normal and full AOT, and nested jars.

### Snapshot 6, August 11, 2026

- Added automatic mixin translation for unique target changes that only add parameters. It covers common `@Inject`, `@Redirect`, `WrapOperation`, `@Overwrite`, invoker, and Fabric refmap cases while refusing ambiguous or semantic changes.
- Added `--mc-jar <target.jar>` to offline `transform`, `batch`, and `aot` commands so they can make the same exact host-method decisions as loader transforms and infer the matching target version.
- Rejected explicit `--target` values that disagree with the version inside `--mc-jar`, preventing mismatched shim and host-method decisions.
- Applied batch verification to unchanged copies as well as transformed outputs.
- Applied documented config defaults during the first Fabric pre-launch, honored `polyfills_enabled` on every loader, and kept manual, reserved, and future config fields when the in-game screen saves.
- Preserved handler parameter annotations, type annotations, local metadata, and stack frames when automatic translation inserts parameters.
- Fixed Dynamic FPS mixins for the added `SoundEngine.play` parameter and the renamed `Window` handle getter. Patch-zero source versions such as `1.21.0` now receive the full shim chain.
- Fixed No Chat Reports mixins for the removed simple-options screen superclass and client command parser invoker.
- Fixed legacy Fabric client tick callbacks registering through an inaccessible event implementation, which left old listeners inert on current Java.
- Fixed legacy overlay getter and setter calls after they moved behind `Minecraft.gui` on 26.2. Chat Bubbles now reaches the title screen, but its removed chat listener and player renderer keep the bubble feature unsupported.
- Removed a stale shim that changed the live `BlockState.getBlock()` call to a nonexistent method.
- Made mixin shadow, accessor, and explicit field selectors owner-aware so a same-named field on another Minecraft class cannot be renamed accidentally.
- Kept constructors out of ordinary fuzzy method repair and migrated fifteen legacy shim registrations to the dedicated constructor path.
- Made generated static Mixin helpers private so current Mixin accepts the bridged Pose constructor and worldgen codec registrars.
- Fixed relocated item-group, server-world, and model-layer callbacks using a different interface identity from Fabric's listener arrays and delayed provider invocation.
- Isolated generated helpers per mod and per nested library across Fabric, CLI, and AOT, while keeping older transformed callback holders compatible.
- Gave each translated legacy HUD callback a unique layer id so multiple mods can render their overlays together.
- Isolated Fabric intermediary and Forge SRG member maps between jars in mixed-loader batches, including standalone AOT and `batch --aot`.
- Extended Forge and NeoForge refmap repair through AOT output and nested jars without changing Fabric intermediary sections.
- Added owner-and-descriptor-aware old-SRG to Forge 1.20.1 target-SRG mapping, covering more than 69,000 exact method and field entries.
- Fixed Forge mixin refmaps keeping old class owners and member names after the mixin class itself was transformed.
- Fixed the removed `BlockBehaviour.Properties.noDrops()` call used by older Forge mods.
- Improved compatibility analysis to identify eager legacy Forge registry entries that require a `DeferredRegister` lifecycle migration.
- Restored NeoForge 26.1 and 26.1.1 release artifacts. The release matrix now requires 69 outputs: 23 Fabric, 23 Forge, 22 NeoForge, and one CLI jar.
- Regenerated `SHA256SUMS.txt` from the current release matrix and required one checksum for every published jar.
- Fixed self-hash verification from launcher paths containing encoded spaces, such as macOS `Application Support`.

YUNG's Better Portals 0.3.9 now passes its reported missing-class failure, old SRG calls, mixin parsing, and `noDrops()` construction call on a real Forge 1.20.1 client. Its next blocker is structural: it creates blocks, fluids, and items in static initializers after modern Forge has frozen the registry. Snapshot 6 reports that limitation clearly; a full fix needs a registry lifecycle port.

The automatic mixin work was checked against the exact No Chat Reports, Simple Voice Chat, Mouse Tweaks, and Dynamic FPS jars, plus a Fabric mixin compiled for 1.20.1. Signature changes that replace or remove parameters, change return values, depend on captured locals, or have more than one possible host target still require a reviewed repair. In snapshot 6, a handler that captured old parameters also remained unchanged when only its refmap, not its annotation text, related a Yarn source name to the current Mojang name. Snapshot 7 fixes that case.

### Snapshot 5, August 10, 2026

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
