---
title: Mods That Can't Be Translated
nav_order: 14
---

# Mods That Can't Be Translated

Retromod can redirect renamed bytecode and provide small compatibility bridges. It cannot recreate a subsystem that Minecraft or a loader completely redesigned.

## Usually Out of Scope

- Native JNI or JNA code tied to a specific game ABI
- Custom rendering engines and shader pipelines
- Coremods with their own bytecode transformers
- Modified or proprietary mixin frameworks
- Direct GPU buffer and command manipulation
- Features built around deleted worldgen, registry, or rendering models

These need a manual port from the mod author.

## Common Examples

| Mod or family | Main reason |
|---|---|
| Create and Flywheel | Custom rendering, contraption internals, and deep loader integration |
| OptiFine | Proprietary coremod and renderer replacement |
| Veil and Veil-based mods | Custom rendering and post-processing pipeline |
| Sodium, Iris, Embeddium | Version-specific renderer mixins; partial loading may still happen |
| Chat Bubbles 1.0.1 | Replaces the deleted player renderer and injects into a removed chat listener; it can reach the menu, but bubbles need a manual renderer port |
| Applied Energistics 2 | Broad networking, storage, and platform integration |
| Tinkers' Construct | Version-specific material, data, and rendering systems |
| IndustrialCraft and older Thaumcraft | Deep integration with deleted game systems |

This list describes broad compatibility, not a judgment about those projects.

## Large Version Jumps

Minecraft 1.13's flattening replaced major block, item, command, and registry APIs. Forge 1.12.2 mods may transform far enough to be discovered, but many still require a real port.

Old Forge mods on modern NeoForge face another large API migration. Retromod now bridges several common 1.20.1 Forge registration, event-bus, networking, and removed-class paths. This is partial compatibility, not a complete Forge runtime. Registry lifecycle, packet delivery, data generation, or other deep Forge internals can still need a manual port. Use a Forge host when a mod depends heavily on those systems.

Fabric mods use intermediary names. On 26.1 and newer hosts, Retromod maps that namespace to Mojang names before applying later repairs. On pre-26.1 Fabric hosts, that global remap must stay off because the runtime is still intermediary-named. Retromod has curated intermediary bridges for common old text, entity, material, identifier, model, and entity-type changes, including real 1.16.5 to 1.20.1 launch coverage. It is not a complete translation of every redesigned pre-26 API.

## Mixins

A mixin can survive a class or method rename. With an exact target Minecraft jar, Retromod can also repair a uniquely proven method change when parameters were only added in a safe shape. It can repair selected zero-capture injections and exact-prefix captures without guessing.

Retromod refuses ambiguous overloads, constructors, return-type changes, reordered or removed parameters, semantic local captures, unsafe parameter annotations, and `remap = false` scopes. A target whose body or local-variable layout was redesigned normally needs a manual port.

Retromod may disable one known-broken handler so the rest of a mod can load. That means the mod is usable with a missing feature, not fully compatible.

FastQuit 3.0.0 is not currently claimed compatible with Minecraft 26.2. Its level-storage mixin spans save methods whose parameters and meaning changed. Rewriting only its shadow declarations would risk save behavior, so Retromod leaves this case for a tested semantic bridge.

## Check Before Deciding

Look in the [compatibility database]({{ '/compatdb' | relative_url }}) and test the exact mod version. Some lighter addons work even when their larger ecosystem does not.

If a listed mod works for you, submit a compatibility report with the versions, loader, and features tested.
