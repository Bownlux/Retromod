---
title: Architecture
nav_order: 11
---

# Architecture

Retromod turns an old mod jar into one that targets the host Minecraft and loader.

## Pipeline

1. Detect the source Minecraft version and loader from mod metadata.
2. Find a chain of version shims.
3. Register class, method, field, constructor, and signature redirects.
4. Remap Fabric intermediary or Forge SRG names when needed.
5. Rewrite classes, mixin annotations, refmaps, access wideners, and metadata, including supported nested jars.
6. Embed selected replacement APIs.
7. Verify references and cache the result.

The transform is iterative because one rewrite can expose another old reference.

## Loader Entry Points

- **Fabric:** `RetromodPreLaunch` processes `retromod-input/` before Fabric scans normal mods.
- **NeoForge:** `RetromodNeoForge` transforms input and supported jars in `mods/`.
- **Forge:** `RetromodForge` follows the Forge path and registers Forge-specific bridges.
- **CLI:** `RetromodCli` runs the same core without a live loader.

Loader entry points must not reference another loader's classes. Shared work belongs in loader-neutral helpers.

## Core Components

| Component | Role |
|---|---|
| `RetromodTransformer` | ASM visitors and redirect registries |
| `ShimRegistry` | Finds version paths |
| `IntermediaryToMojangMapper` | Fabric names on unobfuscated hosts |
| `SrgToMojangMapper` | Forge SRG names on Mojang-named hosts |
| `MixinCompatibilityTransformer` | Mixin annotations and selected handler repairs |
| `FabricModTransformer` | Fabric metadata, jars, and access wideners |
| `ForgeModTransformer` | Forge and NeoForge metadata and jars |
| `SyntheticEmbedder` | Per-mod replacement classes |
| `AotCompiler` | Cached offline transforms |

## Shims and Polyfills

A version shim describes a transition between releases. Shims compose through a breadth-first search, so old support must remain registered.

A polyfill replaces an API that no longer exists. Polyfills should preserve useful behavior through a modern equivalent. A no-op is acceptable only when the limitation is explicit.

Both use `ServiceLoader` registrations under `src/main/resources/META-INF/services/`.

## Safety

Redirects should be owner- and descriptor-specific. Frame recomputation falls back conservatively when a class hierarchy cannot be proven. Fabric runtime nested transforms stop after four levels. Original jars are backed up, and cache stamps prevent packaged builds from reusing stale transforms.

See [Technical Details]({{ '/technical' | relative_url }}) for lower-level notes.
