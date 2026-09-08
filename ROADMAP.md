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

### Fabric Shims Keyed on Yarn Names

About half the Minecraft-side registration keys in the Fabric shim tree are Yarn names: of 160, 84
are Mojang and 76 are Yarn. A Yarn key cannot match a distributed mod, which carries intermediary
names that Retromod remaps to Mojang before the instruction visitor runs. Two real mods were checked
and neither holds a single Yarn-shaped reference.

Most of those are not a defect. 28 of the 30 Yarn-keyed class redirects map the Yarn name to exactly
the right Mojang name, so they are deliberate converters for a mod that does ship Yarn names, and
they are merely redundant with the intermediary path for one that does not. A chain that passes
through a stale Mojang name still lands correctly, because the transform loop keeps resolving until
the bytecode stops changing.

What is worth revisiting is the smaller set where a Yarn key is the only thing providing a repair, so
nothing happens at all: the `DamageSource` static-field redirects and the `DimensionType` and
`World.getDimension` method redirects in the 1.15 through 1.19 shims. Each also targets a bridge
class that was never written, so both halves have to be done together or neither is worth doing.

A related detail: `registerRemappedMethodAlias` clones a key under a Mojang owner when another shim
registered that class redirect, but rewrites only the owner, never the method name or descriptor. An
alias built from a Yarn-keyed entry therefore still carries a Yarn method name and matches nothing.

### Deleted Base Classes

Minecraft keeps folding hardcoded subclasses into data components, and a mod that extended one has
nothing left to inherit from. A class move cannot express that: it is a rename, and pointing a
deleted base at the nearest surviving name produced worse failures than leaving it alone. Retromod
now rebases the inheritance edge onto a generated subclass of a base that survived, building its
constructors from the `super(...)` calls the mods actually make. `RecordItem` and
`EnchantedBookItem` go through it.

A rebase only restores linkage. Arguments the modern base does not take are dropped, so the item
registers and whatever the old base did with them is gone. It suits a base whose remaining job is
registration or identity. `AgeableListModel` is the counter-example: no surviving model base accepts
its constructors, so a mod that extends it still needs a real port, and Retromod names it in the log
rather than guessing.

The wider fix is that Retromod reads class shape from the host instead of from tables. Whether a
name can be extended and whether a call target is an interface are both host questions, and a
hardcoded answer is right only for the version it was written against. Both now read the indexed
Minecraft jar first. An offline transform with no `--mc-jar` still falls back to the tables, and
closing that gap is the next step.

### Minecraft 26.3

26.3 is in pre-release. Retromod translates mods onto it: 230 class moves for Fabric, NeoForge, and
Forge, every one checked against the 26.3-pre-2 client, and Minecraft's own pre-release version
names resolve to the milestone so a pre-release jar can be used as the target. The jump is mostly
one library repackage, `com/mojang/blaze3d` becoming `com/mojang/renderpearl`, plus vanilla renames.

Three groups are removed rather than moved, so no rename can cover them. Most of the worldgen
configuration system, including `FeatureConfiguration` and its subclasses, `ConfiguredFeature`, and
`SurfaceRules`. The last of the hardcoded item classes, `AxeItem`, `HoeItem`, `ShovelItem`,
`BedItem`, and `SignItem`. And, new in pre-release 1, the loot number providers: `NumberProvider`
and `NumberProviders` are gone and the family split into int-valued and float-valued packages.
Three names exist in exactly one of the two and are carried; `ConstantValue`,
`EnvironmentAttributeValue`, `StorageValue`, `Sum` and `UniformGenerator` exist in both, so nothing
in the old name says which a mod meant and they are declined.

A mod built on any of those needs a real port. The pack format entry tracks the pre-release and will
move again before release. No 26.3 jars are published yet.

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
