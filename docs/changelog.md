---
title: Changelog
nav_order: 15
description: "Highlights from Retromod releases."
---

# Changelog

This page keeps the release history readable. The [full technical changelog](https://github.com/Bownlux/Retromod/blob/main/CHANGELOG.md) lists every fix and regression test.

## 1.3.0 Snapshot Line

### Snapshot 10, August 25, 2026

- Adds an offline `resourcepack` CLI command for updating one resource pack without launching Minecraft.
- Validates texture mappings before release and keeps same-named block and item textures separate.
- Updates renamed entity textures from pre-1.13 packs, including boats, beds, end crystals, snow golems, and llamas.
- Applies later texture moves in version order through 26.2, including equipment, armor trims, GUI slots, and reorganized entity textures.
- Repairs a Mixin handler that reads a local variable from the method it hooks when Minecraft added a parameter to that method.
- Leaves a Mixin handler alone when its body uses something Minecraft removed, instead of repairing it into a later crash.
- Adds Minecraft 26.3 support, which repackaged the rendering library and renamed several vanilla classes.
- Accepts Minecraft's snapshot version names, so a 26.3 snapshot is recognized as 26.3.
- Follows a 26.3 entity method that gained a parameter, without disturbing the same method on 26.2.
- Updates mods that use GeckoLib for its move to a new package in GeckoLib 5, which otherwise left them crashing on their first animated item.
- Fixes a NeoForge crash where a translated mod failed on startup with `IncompatibleClassChangeError` because loader state stopped being static.
- Renames 413 block and item textures when converting a pre-1.13 pack, derived from the game's own files, where the previous list covered 9.
- Covers 219 later texture moves through 26.2, including the sign textures 26.2 moved into the block folder.
- Updates a converted pack's own models so they point at the textures that moved, instead of leaving missing textures.
- Converts textures for every namespace in a pack, so a pack that also skins mods converts fully.
- Keeps the current file when a pack ships a texture on both the old and the new path.

The command moves textures only when a version boundary has one verified successor. It does not port arbitrary models, shaders, overlays, OptiFine formats, or custom rendering code.

A handler that reads a local of the same type as the added parameter is still left alone, because either variable would match. Mods disabled by the Mixin blocklist were re-checked against 26.2 and all of them still need a source port.

26.3 support covers class moves, not the rendering API changes that came with them. OpenGL has not been removed in 26.3, so Retromod still selects it for translated mods.

26.3 removed the worldgen configuration classes and the last hardcoded item classes outright, so mods built on either need a real port.

The resource pack fixes came out of a conversation with ttaute, whose texture tables are in review.

### Snapshot 9, August 23, 2026

- Moves a one-target, accessor-only Mixin when an exact owner-scoped redirect and unchanged descriptor prove the new field owner.
- Repairs legacy block random-tick setters after the field moved to `BlockBehaviour` and became final, then refreshes every cached block state after a proven setter call.
- Updates 48 same-contract `GameRules` constants whose Fabric names otherwise expand to retired `RULE_*` spellings on 26.1 and newer.
- Transforms explicit `aot` and `batch` inputs whose metadata omits a source Minecraft version, using only matching-loader shims available on the target host.
- Prevents one-sided Fabric constraints such as `<26.2`, `<=26.2`, and `>26.2` from being mistaken for native target declarations.
- Uses the detected host version for transforms started from the in-game compatibility screen.
- Keeps parallel class analysis on an immutable copy of the input instead of files that other workers are replacing.
- Preserves bundled MixinExtras implementations so nested-jar transforms cannot disable their bootstrap.
- Applies matching API bridges in known-source CLI and AOT output, including explicit same-version transforms.
- Updates staged resource and data packs through 26.2 on Fabric, Quilt, Forge, and NeoForge. Data packs run compatible 26.x content migration when needed, while legacy resource packs run item-definition and content migration only during an actual format upgrade.
- Reads the Minecraft dependency from `quilt.mod.json` and keeps it authoritative when Fabric metadata is also present. The shared Fabric artifact uses entrypoint contracts Quilt Loader can invoke, so there is no separate Quilt jar.
- Keeps Windows and Unix release builds on the same version, Minecraft matrix, integrity checks, artifact counts, and checksum output.
- Updates JUnit Jupiter to 6.1.3 and the release publisher's `charset-normalizer` to 3.5.1.

Retromod refuses conflicting, mixed-purpose, or ambiguous accessor moves. Automatic scans still leave unknown-version native mods alone. Malformed, newer-only, overlay-based, or conflicting packs stay staged. The changed fire-tick rule and three inverted boolean rules also stay untouched because they need value-aware adapters.

### Snapshot 8, August 17, 2026

A maintenance build. Updates the bytecode library behind every transform, along with logging and build tooling. Nothing changes about which mods translate or which Minecraft versions are supported, and the same jar still runs on Java 17, 21, and 25.

Retromod still builds against the oldest Fabric loader it accepts, so it cannot call an API that an older loader lacks.

Because the change reaches every transform, this build was checked by re-translating mods from scratch on 26.2 Fabric and 26.2 NeoForge clients.

### Snapshot 7, August 12, 2026

- Fixed parameter-capturing Fabric mixins whose annotation keeps a Yarn source selector while the refmap alone identifies the changed target method.
- Carries an exact, unique target parameter-addition proof from refmap remapping into the matching class repair, while preserving the source selector that Mixin expects.
- Repairs `@ModifyReturnValue` and `@ModifyExpressionValue` handlers that capture the complete old target argument list when a proven target change only adds parameters.
- Keeps value-only handlers unchanged and refuses partial, extra, ambiguous, or semantic captures instead of guessing a layout.
- Refuses ambiguous targets, `remap = false`, unsafe parameter annotations, staticness mismatches, and owner mismatches instead of guessing.
- Uses the same repair context for runtime transforms, the CLI, normal and full AOT, and nested jars.
- Recursively transforms Fabric libraries bundled inside other libraries, including their classes, mixins, refmaps, access wideners, and generated helpers.
- Keeps nested helper names unique across the complete archive chain. Runtime recursion is capped at four levels below the outer mod.
- Uses the exact `--mc-jar` index for CLI verification, removing false missing-class reports for current Minecraft classes that are not on the standalone CLI classpath.
- Checks game-owned links inside recursively bundled libraries against the exact target, while intentionally ignoring their optional third-party integrations.
- Keeps DataFixerUpper, SLF4J, Gson, and other Minecraft runtime libraries outside the client jar from appearing as false missing-class issues in executable CLI reports.
- Adapts legacy `super.keyPressed(key, scanCode, modifiers)` calls to construct the 26.x `KeyEvent`, while preserving direct-superclass invocation semantics.
- Recognizes Fabric `mixin.<modid>.json` configs so every listed mixin receives selector repair on official-name hosts.
- Keeps rebuilt Fabric stack-map frames precise when a mod-owned class reaches its Minecraft ancestor through remapped hierarchy names, fixing the Revamped Phantoms `Shockwave` verifier failure.
- Bridges Revamped Phantoms' remaining 26.x entity registration, tag, damage, targeting, nearby-query, and camera links with exact 26.1 and 26.2 API shapes.
- Lets narrowly matched legacy entity renderers satisfy the modern render-state contract without a linkage crash. Their old custom geometry may remain invisible until it receives a real submit-pipeline port.
- Keeps `mixins.<modid>.refmap.json` resources on the CLI refmap path in outer and nested jars.
- Completes AutoClicky's 26.x client calls and GUI input and render bridges, and verifies the exact jar through stable 26.2 client startup.
- Repairs Double Hotbar's complete seven-handler HUD mixin for the 26.1 extraction renames and the 26.2 `Gui` to `Hud` move.
- Repairs inherited intermediary method calls when an exact jar or host hierarchy proves the mod-owned caller is a subclass, fixing ENGRAM's remaining entity world calls.
- Disables ENGRAM's paired optional screenshot mixins together on the verified 1.21.11 host instead of leaving a delayed accessor crash.
- Prevents an old Fabric API staged with a bundle from being translated into a duplicate of the host API.
- Uses Mojang runtime member names for Forge 1.20.6 and newer, fixing CoFH Core's reported Forge 1.21.1 mixin shadow.
- Adapts the removed Forge 1.20.1 channel builder and payload context to Forge 1.20.6 through 1.21.x. Forge 26.x needs a separate event-listener adapter.
- Keeps Forge-only distribution markers on Forge instead of moving them into the NeoForge package.
- Normalizes legacy class-only AccessTransformer descriptor syntax, fixing Mana and Artifice's NeoForge discovery error.
- Repairs Management Wanted's reported abstract tick listeners and several early Forge-to-NeoForge companion links, including promoted loader metadata and discarded message-registration results.
- Clarifies that version-incompatible Forge and NeoForge jars must start in `retromod-input/` or be prepared with the CLI.
- Uses each loader's selected side so a merged Forge server jar cannot make a dedicated server look like a client.

CoFH Core now passes its reported mixin failure and its next removed networking-builder failure on Forge 1.21.1. It is not fully compatible: Minecraft 1.21 replaced subclassed enchantment objects and their static registry with a final, data-driven system, which needs a manual data and behavior port.

The Medieval Origins bundle no longer risks installing a translated duplicate of Fabric API. A full bundle result still requires its matching Origins pre-release plus the missing Spell Engine and Icarus dependencies.

Management Wanted's reported tick-event crash is fixed in the exact three-jar bundle. Complete compatibility remains unconfirmed because its companions still depend on deeper Forge networking and lifecycle behavior, and the current packet bridge does not replay delivery.

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

YUNG's Better Portals 0.3.9 now passes its reported missing-class failure, old SRG calls, mixin parsing, and `noDrops()` construction call on a real Forge 1.20.1 client. Its next blocker is structural: it creates blocks, fluids, and items in static initializers after modern Forge has frozen the registry. Snapshot 6 reports that limitation clearly. A full fix needs a registry lifecycle port.

The automatic mixin work was checked against the exact No Chat Reports, Simple Voice Chat, Mouse Tweaks, and Dynamic FPS jars, plus a Fabric mixin compiled for 1.20.1. Signature changes that replace or remove parameters, change return values, depend on captured locals, or have more than one possible host target still require a reviewed repair. In snapshot 6, a handler that captured old parameters also remained unchanged when only its refmap, not its annotation text, related a Yarn source name to the current Mojang name. Snapshot 7 fixes that case.

### Snapshot 5, August 10, 2026

- Fixed mod-owned stack-frame merges in the standalone CLI, full AOT worker pool, and recursively nested libraries. Each transform now reads the hierarchy from the jar it is actually rewriting.
- Fixed Patchouli 1.20.1 on Fabric 1.21.11 across its recipe accessors, screen hierarchy, sound accessor, item setup, and old Gson registration. Replaced APIs for ghost buffers, resource-pack books, the animated guide-book model, and its completion predicate are disabled safely. Built-in books and normal rendering remain available.
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
