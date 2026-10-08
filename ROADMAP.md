# Retromod Roadmap

This is a direction of travel, not a release promise. Real mod reports and new Minecraft releases can change the order.

Shipped work belongs in the [changelog](CHANGELOG.md). There are no fixed release dates.

Last reviewed: 2026-10-01

## Current Work

Retromod 1.3.2 is the current stable release. The 1.3 line's goal was mixin translation, and it is
met: every repair that line set out to build has shipped code and a passing test, so the list of
what it does now lives in the [changelog](CHANGELOG.md). What follows is what 1.3 did not close.

### Mixin Repairs Still Refused

A MixinExtras capture of the same type as the parameter Minecraft added is a deliberate refusal
rather than a gap. Mixin matches a capture's type exactly, so a capture of any other type is already
safe, and one of the added type is genuinely ambiguous: when a method gains a `ServerLevel` and the
mod captures a `ServerLevel` local, the new parameter may be the variable the author wanted.
Choosing needs the target's local variable table, which Retromod does not index because
`FuzzyMethodResolver` reads jars without debug information.

Nine blocklist entries across five mods remain in `src/main/resources/retromod/mixin-blocklist.json`,
covering Deeper and Darker, True Darkness, YUNG's API, and ENGRAM. None was retired in rc.1, rc.2,
1.3.0, 1.3.1, or 1.3.2. Each was re-checked against the 26.2 jar, and that check has not been repeated
against the released 26.3 client, so repeating it is the first step before any entry can be dropped.

Retromod already makes part of that judgement itself. A repair is declined when the handler body
names a Minecraft field or method the host no longer declares, matched by name across the whole
hierarchy, so an inherited default method still counts as present and a method that only gained a
parameter is still left to the repair engines. What remains is turning that judgement into
retirement: an entry whose handler passes the check could be dropped from the list and repaired
instead. Deeper and Darker is not that entry. Its painting handler only gained a leading
`ServerLevel`, but its body reads `Painting.VARIANT_CODEC` and calls `Level.getGameRules`, and both
are gone, so repairing the signature would replace a dormant feature with a crash.

Some mixins still need a source port. True Darkness is one example because Minecraft replaced the
CPU-side light texture it shadows with a different GPU system. YUNG's API's enhanced Beardifier
terrain adaptation also stays disabled: its bytecode applies, but a headless 26.2 server proved that
its behavior breaks current chunk scheduling.

### Bridges That Were Never Written

A shim can register a redirect whose bridge class does not exist, and then the repair simply never
happens. `src/test/resources/phantom-baseline.txt` pins 45 such targets and `PhantomBaselineTest`
fails the build when that set grows or shrinks, so the number only moves deliberately.
`RetromodTransformer.dropPhantomComRetromodTargets` drops them at startup with a warning, which
makes them inert rather than fatal.

Paying that baseline down is the concrete remaining 1.3.x task. The `DamageSource` static-field
redirects in the 1.19.3 shim and the `DimensionType` and `World.getDimension` redirects in the 1.15
shim are the clearest examples: each targets a bridge class that was never written, so the redirect
and the bridge have to be done together or neither is worth doing.

### Fabric Shims Keyed on Yarn Names

Roughly 40 percent of the Minecraft-side registration keys in the Fabric shim tree are Yarn names. A
Yarn key cannot match a distributed mod, which carries intermediary names that Retromod remaps to
Mojang before the instruction visitor runs. Counting the first `net/minecraft/` argument of every
`register*` call under `src/main/java/com/retromod/shim/fabric/` gives 222 registrations over 140
distinct class keys: 77 Mojang, 57 Yarn, and 6 still intermediary.

Most of those are not a defect. A Yarn-keyed redirect that names the right Mojang destination is a
deliberate converter for a mod that does ship Yarn names, and merely redundant for one that does
not. A chain that passes through a stale Mojang name still lands correctly, because the transform
loop keeps resolving until the bytecode stops changing. Of the 48 Yarn-keyed class redirects, 16 map
a Yarn name to another Yarn name, mostly the `net/minecraft/tag/` and `net/minecraft/util/registry/`
package moves, and those are the ones worth checking first.

A related detail: `registerRemappedMethodAlias` clones a key under a Mojang owner when another shim
registered that class redirect. It rewrites the owner and resolves the descriptor, and since 1.3.1
it refuses an alias that points back at itself, but it never rewrites the method name. An alias
built from a Yarn-keyed entry therefore still carries a Yarn method name and matches nothing.

### Deleted Base Classes

Minecraft keeps folding hardcoded subclasses into data components, and a mod that extended one has
nothing left to inherit from. A class move cannot express that: it is a rename, and pointing a
deleted base at the nearest surviving name produced worse failures than leaving it alone. Retromod
now rebases the inheritance edge onto a generated subclass of a base that survived, building its
constructors from the `super(...)` calls the mods actually make. Nineteen item bases go through it,
`RecordItem` and `EnchantedBookItem` among them, plus the pre-1.19.3 creative tab, two screen and
dispense bases, and `AxeItem`, `HoeItem` and `ShovelItem` at 26.3.

A rebase only restores linkage. Arguments the modern base does not take are dropped, so the item
registers and whatever the old base did with them is gone. It suits a base whose remaining job is
registration or identity. `AgeableListModel` is the counter-example: no surviving model base accepts
its constructors, so a mod that extends it still needs a real port, and Retromod names it in the log
rather than guessing. `BlockEntityWithoutLevelRenderer`, `ItemOverrides`, and custom loot-function
serializers are in the same class of failure.

The wider fix is that Retromod reads class shape from the host instead of from tables. Whether a
name can be extended and whether a call target is an interface are both host questions, and a
hardcoded answer is right only for the version it was written against. Both read the indexed
Minecraft jar first, and an offline transform with no `--mc-jar` now finds an installed copy on its
own. The tables are the fallback only when no installed copy is found.

### Minecraft 26.3

26.3 has released. Retromod ships it as a target for Fabric, NeoForge, and Forge, with 230 class
moves verified against the released client plus the authlib 10 renames. The jump is mostly one
library repackage, `com/mojang/blaze3d` becoming `com/mojang/renderpearl`, plus vanilla renames.

26.3 also moved input from GLFW to SDL and renumbered every key and mouse button. Retromod
translates the codes a mod passes to `KeyMapping`, `isKeyDown`, and `InputConstants.Type.getOrCreate`.
A mod that compares raw key codes itself, for example in `keyPressed`, still sees SDL numbers where
it expects GLFW ones, and those comparisons need a port.

Four groups are removed rather than moved, so no rename can cover them. Most of the worldgen
configuration system, including `FeatureConfiguration` and its subclasses, `ConfiguredFeature`, and
`SurfaceRules`. The last of the hardcoded item classes, `AxeItem`, `HoeItem`, `ShovelItem`,
`BedItem`, and `SignItem`. The loot number providers: `NumberProvider` and `NumberProviders` are
gone and the family split into int-valued and float-valued packages, and while three names exist in
exactly one of the two and are carried, `ConstantValue`, `EnvironmentAttributeValue`,
`StorageValue`, `Sum` and `UniformGenerator` exist in both, so nothing in the old name says which a
mod meant and they are declined. And `Feature` itself became an interface, so a mod extending it
needs a real port.

### Minecraft 26.4

26.4 is in snapshots, and Retromod does not target it yet. Snapshot 3 still runs on Java 25, and
Fabric already publishes loader and API builds for the 26.4 snapshots. NeoForge has no 26.4 build.

Snapshot 3 brought the ice caves (the Frostbite, ice balls, icicles, and ice crystals) and a
Direct3D 12 backend, along with two removals that reach almost every content mod. `SoundType` is
gone: block sounds are `BlockSoundSet` records in a registry, and `Properties.sound` takes a
`ResourceKey<BlockSoundSet>`. 130 of the 131 old constants have a same-named key in
`BlockSoundSets` and `EMPTY` becomes `noSound()`, so vanilla sounds are a redirect. A mod's own
`new SoundType(...)` has to become a registered sound set, which needs generated data. The texture
classes (`AbstractTexture`, `DynamicTexture`, `SimpleTexture`, `ReloadableTexture`, and their
siblings) gave way to `TextureProvider` and `TextureHandle`, so a mod that uploads its own textures
needs a texture bridge. `LargeDripstoneFeature` became `LargeSpeleothemFeature`.

Part of the 26.3 `renderpearl` move is reversed: `RenderPipeline`, `VertexFormat`, and
`ShaderSource` are back under `com/mojang/blaze3d`. The 26.3 shim's redirects for those classes
would send a mod to names 26.4 no longer has, and a reverse redirect in a later shim would loop,
so those entries need a host gate before a 26.4 target can work.

The rest is the usual mix of renames and redesigns. `VerticalAnchor` became a record, so an
interface call to it throws `IncompatibleClassChangeError`, and Retromod only flips calls the other
way today. `RuleTest` became an interface. `DyeColor` lost its color accessors,
`LevelReader.getBiome(BlockPos)` is gone, and `spawnAfterBreak` gained a parameter. These are
findings from an early snapshot and will move before the release.

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

Forge 1.20.1 mods on NeoForge get the same attention. From 1.3.3, configs, menus, capabilities,
custom registries, client extensions, and `SimpleChannel` messages are bridged. Code-registered
enchantments, which 1.21 made data-driven and final, are the largest gap left; generating their
data pack entries from the old classes is the next step.

1.3.3 bridged the 1.12 event bus, `RegistryEvent.Register`, the FML lifecycle, the block and item
builders, and block states, with configs, networking, fluids, and world generators as stand-ins.
On a survey of 15 simple 1.12.2 content mods, 3 now register content on NeoForge 1.21.1 and 2 on
Forge 1.20.1, where none did before. What stops the rest: 1.12 world generation that never runs,
biomes and dimensions, commands, `EnumHelper` materials, GUIs, and tile entities.

1.19.2 mods on 1.20.1 now restore their code-registered worldgen through a generated data pack,
list their items in their creative tabs again, and get natural spawns back through a generated
biome modifier. The pack is offered on hosts up to 1.20.1; newer hosts need pack sources written
against each newer Pack API. Old GUI drawing such as `drawString` and `fill`, and the client
registration events 1.19 replaced, are the next client-side gaps.

The target is simple to moderate content mods. Coremods, custom renderers, and projects tied to deleted internals will still need manual ports.

### Leads From Compatibility Reports

Each of these came from a report with a log behind it, and each names one place to start:

- The 26.2 `MultiBufferSource` stand-in now applies to a mod built for 26.1, but it drops the
  geometry it builds. Submitting that geometry through 26.2's `SubmitNodeCollector` is the missing
  piece.
- The 26.2 `ItemEntity` merge signature swaps a `CallbackInfo` for a `CallbackInfoReturnable` and
  changes the parameter list, which the conservative repair rules decline. It is a candidate for a
  curated adapter rather than a relaxed rule.
- 26.3 added a `Prediction` parameter to `Inventory.placeItemBackInInventory` and
  `ServerPlayer.drop`. A bridge needs the value vanilla callers pass, which has not been checked.
- Forge 66 split `InputEvent.InteractionKeyMappingTriggered` and `RenderBlockScreenEffectEvent` into
  per-kind subclasses with their own `BUS`, so a listener for the old type registers on nothing. It
  also removed the axe, shovel and hoe `ToolActions`, the brewing recipe API, and
  `ForgeRegistries.FEATURES`, which need a port.
- Forge's `Lazy` allowed a null value and NeoForge's does not, so a Forge mod whose lazy value is
  null fails in its constructor. An embedded Forge-shaped `Lazy` would replace the current rename.
- Some 1.20.1 Forge mods still stop during registration on the pre-1.20.5
  `MobEffect.addAttributeModifier` shape and on `BlockBehaviour.Properties.copy`.
- KubeJS 2001 on Fabric 1.21.1 stops on DataFixerUpper's removed `DataResult.get()`.
- 1.21.1 Fabric mods on 26.x still meet `Ingredient.CODEC_NONEMPTY`, removed in 1.21.2, and
  `EitherHolder`, removed after 1.21.11.
- A Forge `@Mod.EventBusSubscriber` class that mixes static and instance handlers loses its static
  handlers on NeoForge, because NeoForge rejects such a class and Forge only ever subscribed the
  static ones.
- The registry-id bridge registers from 26.1, but a block's id became mandatory at 1.21.2, so a
  NeoForge 1.21.1 mod on a 1.21.2 through 1.21.11 host still fails with `Block id not set`. The
  bridge names 26.x types, so widening it needs checking against a 1.21.x host first.
- 26.3 key translation stops at `KeyMapping`, `isKeyDown` and `getOrCreate`. `ToggleKeyMapping`,
  subclass constructors and Forge's conflict-context constructors keep GLFW defaults.
- Retromod points a disabled mixin at `retromod/stripped/<Name>`, and the resulting "target was not
  found" warnings read like Retromod defects in a user's log. Emitting a real placeholder class
  would keep the same behavior without the noise.

Two limits are also worth closing. Since 1.21.2 `neighborChanged` receives an `Orientation`, which
can be null, instead of the neighbor's position, so an old override cannot be handed the position it
expects and is not bridged. And a
fresh in-game transform once left a handful of items without their registry id; transforming again
produced a correct jar. 1.3.3 fixed a race that could leave calls unredirected on an unlucky
parallel transform, which fits that report, but the case was never reproduced.

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
