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
| Applied Energistics 2 | Broad networking, storage, and platform integration |
| Tinkers' Construct | Version-specific material, data, and rendering systems |
| IndustrialCraft and older Thaumcraft | Deep integration with deleted game systems |

This list describes broad compatibility, not a judgment about those projects.

## Large Version Jumps

Minecraft 1.13's flattening replaced major block, item, command, and registry APIs. Forge 1.12.2 mods may transform far enough to be discovered, but many still require a real port.

Old Forge mods on modern NeoForge face another large API migration. Retromod bridges common registration and event paths, but a Forge host remains more reliable when a mod depends heavily on Forge internals.

## Mixins

A mixin can survive a class or method rename. It usually cannot survive a target method whose body, parameters, or local-variable layout was redesigned.

Retromod may disable one known-broken handler so the rest of a mod can load. That means the mod is usable with a missing feature, not fully compatible.

## Check Before Deciding

Look in the [compatibility database]({{ '/compatdb' | relative_url }}) and test the exact mod version. Some lighter addons work even when their larger ecosystem does not.

If a listed mod works for you, submit a compatibility report with the versions, loader, and features tested.
