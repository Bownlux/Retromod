# Retromod Roadmap

This is a direction of travel, not a release promise. Real mod reports and new Minecraft releases can change the order.

Shipped work belongs in the [changelog](CHANGELOG.md). There are no fixed release dates.

Last reviewed: 2026-08-26

## Current Work

Retromod 1.2.0 is the current stable release. The active development line is 1.3.0, focused on mixins that need more than a renamed target.

### 1.3.0: Mixin Translation

The aim is to repair useful mixins instead of disabling their handlers.

Already working in the 1.3 snapshots:

- save-data handlers affected by the `CompoundTag` to `ValueInput` and `ValueOutput` change
- handlers for methods that gained a leading `ServerLevel` parameter
- parameter-capturing handlers whose current target signature is proven through a refmap
- MixinExtras value modifiers that capture all old target arguments when a proven target change only adds parameters
- more MixinExtras selectors and owner moves
- MixinExtras captures of a local variable, whether they name a slot or select one by type, which used to make the whole handler unrepairable
- declining a handler repair when the handler's body names a field or method the host removed, so a repair cannot turn a dormant feature into a crash
- YUNG's API `NoiseChunk` worldgen mixin, verified on a headless 26.2 server
- several class and enum moves found through corpus testing

Work still planned:

- MixinExtras captures of the same type as the parameter Minecraft added. This is now a deliberate
  refusal rather than a gap. Mixin matches a capture's type exactly, so a capture of any other type
  is already safe, and one of the added type is genuinely ambiguous: when a method gains a
  `ServerLevel` and the mod captures a `ServerLevel` local, the new parameter may be the variable
  the author wanted. Choosing needs the target's local variable table, which Retromod does not index
  because `FuzzyMethodResolver` reads jars without debug information.
- replace more blocklist entries with real repairs when the old and new behavior can be matched safely

Every blocklist entry was re-checked against the 26.2 jar for snapshot.10 and none can be retired
yet. Deeper and Darker's painting handler is the one that looks easiest, because its target only
gained a leading `ServerLevel`. Its body reads a field 26.2 deleted outright, so repairing the
signature would replace a dormant feature with a crash.

Retromod now makes that judgement itself: a repair is declined when the handler body names a
Minecraft field or method the host no longer declares. It matches names across the whole hierarchy
rather than descriptors, so an inherited default method still counts as present and a method that
only gained a parameter is still left to the repair engines.

What remains is turning that judgement into retirement. The check answers whether a body is sound,
so an entry whose handler passes it could be dropped from the list and repaired instead. Deeper and
Darker is not that entry: its body reads `Painting.VARIANT_CODEC` and calls `Level.getGameRules`,
and both are gone.

Some mixins still need a source port. True Darkness is one example because Minecraft replaced the CPU-side light texture it shadows with a different GPU system. YUNG's API's enhanced Beardifier terrain adaptation also stays disabled: its bytecode applies, but a headless 26.2 server proved that its behavior breaks current chunk scheduling.

### Minecraft 26.3

26.3 is in snapshot. Retromod translates mods onto it: the class moves are in place for Fabric,
NeoForge, and Forge, and Minecraft's own snapshot version names are recognized. The jump is mostly
one library repackage, `com/mojang/blaze3d` becoming `com/mojang/renderpearl`, plus vanilla renames.

Two things are worth knowing. The repackage is partial, so several vertex classes stayed where they
were and only the classes that actually moved are redirected. And a class move restores linkage, not
behavior: the rendering API changed alongside the repackage, so a mod that drives rendering directly
still needs real adapters.

OpenGL was expected to go away in 26.3 and has not. Retromod still selects it for translated mods on
that host.

Not done yet: 26.3 is absent from the release build matrix, so no 26.3 jars are published. That is a
one-line change once 26.3 is closer to release, and it is deliberately separate because it decides
what gets uploaded for an unreleased Minecraft version.

## Next

### 1.4.0: Older Forge Mods

The 1.2 line taught Retromod how to discover and transform many Forge 1.12.2 mods. The next step is getting simple mods beyond construction and into working gameplay.

The main areas are:

- old `GameRegistry` and lifecycle registration
- pre-Brigadier commands
- SimpleImpl networking
- `IGuiHandler` menus and screens
- legacy world generation
- creative tabs, entity AI, and spawn registration

The target is simple to moderate content mods. Coremods, custom renderers, and projects tied to deleted internals will still need manual ports.

## Rendering After OpenGL

Minecraft 26.2 can run with Vulkan or OpenGL. Retromod currently prefers OpenGL for translated mods unless the player already chose a backend.

If a later Minecraft release removes the OpenGL backend, the first job is to inspect the final rendering API. Possible work includes:

- restoring Minecraft's old OpenGL backend if the host interface remains compatible
- adding focused adapters for old rendering calls that still have a clear modern equivalent
- keeping an honest incompatible list for raw OpenGL renderers and custom shader pipelines

Retromod will not try to translate arbitrary OpenGL commands into Vulkan. That is a graphics-driver problem, not a safe bytecode rewrite. Research notes live in [render redesign bridge plans](scripts/research/render-redesign-bridge-plans.md).

## Ongoing Work

- Add shims, polyfills, and mappings from real compatibility reports.
- Grow the SRG to Mojang mapping table for older Forge mods.
- Test each supported loader when Minecraft releases a new version.
- Move narrow, mod-specific fixes into [addons](docs/addons.md) when they do not belong in core.
- Improve diagnostics so a failed transform points to the first useful incompatibility.

## Later

Bukkit, Spigot, Paper, and Purpur plugin translation is not started. Plugins use a different API and loading model, so that work would be a separate project area.

## How to Help

- Add a result to the [compatibility database](https://bownlux.github.io/Retromod/compatdb/).
- File issues with the full `latest.log` and exact versions.
- Add an [SRG mapping](docs/srg-mappings.md).
- Write a focused [Retromod addon](docs/addons.md).
