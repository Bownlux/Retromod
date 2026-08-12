# Retromod Roadmap

This is a direction of travel, not a release promise. Real mod reports and new Minecraft releases can change the order.

Shipped work belongs in the [changelog](CHANGELOG.md). There are no fixed release dates.

Last reviewed: 2026-08-11

## Current Work

Retromod 1.2.0 is the current stable release. The active development line is 1.3.0, focused on mixins that need more than a renamed target.

### 1.3.0: Mixin Translation

The aim is to repair useful mixins instead of disabling their handlers.

Already working in the 1.3 snapshots:

- save-data handlers affected by the `CompoundTag` to `ValueInput` and `ValueOutput` change
- handlers for methods that gained a leading `ServerLevel` parameter
- parameter-capturing handlers whose current target signature is proven through a refmap
- more MixinExtras selectors and owner moves
- several class and enum moves found through corpus testing

Work still planned:

- handle MixinExtras cases that need local-slot resolution
- restore the repairable YUNG's API worldgen mixins and verify them on a headless server
- replace more blocklist entries with real repairs when the old and new behavior can be matched safely

Some mixins still need a source port. True Darkness is one example because Minecraft replaced the CPU-side light texture it shadows with a different GPU system.

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
