---
title: Changelog
nav_order: 15
description: "Highlights from Retromod releases."
---

# Changelog

This page keeps the release history readable. The [full technical changelog](https://github.com/Bownlux/Retromod/blob/main/CHANGELOG.md) lists every fix and regression test.

## 1.3.0 Snapshot Line

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
