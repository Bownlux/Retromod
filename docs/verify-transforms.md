---
title: Verify Transforms
nav_order: 6
---

# Verify Transforms

After transforming a jar, Retromod can check whether its Minecraft classes, methods, and fields exist on the host.

Runtime verification is enabled by the generated config. Toggle it in the GUI or set `"verify_transforms": false` in `config/retromod/config.json`. For a standalone CLI run, pass `--verify`. The CLI also honors an enabled setting in the current working directory. Pair it with `--mc-jar <target.jar>` so Minecraft classes and members are checked against that exact target rather than only the standalone process classpath. A batch checks each generated output separately.

## Reports

Reports are written to:

```text
config/retromod/verify-reports/
```

A missing reference usually means:

- `class_XXXX`, `method_XXXX`, or `field_XXXX`: missing Fabric intermediary mapping
- `m_NNNNN_` or `f_NNNNN_`: missing Forge SRG mapping
- Readable method or field: missing version redirect
- Removed class: missing or disabled polyfill

The console summary, the CLI listing, and each section of the report list Minecraft gaps first. A class from another mod or library that is not installed is labeled as belonging to that mod or library, not as missing from Minecraft. That is expected when the mod supports an optional integration, such as a recipe viewer you do not use. On a dedicated server, a missing client class is reported as not checked, because the server has no client classes to check it against. Fabric servers before 26.1 use intermediary names, so a missing client class there still reads as missing from Minecraft.

Attach the report when filing a compatibility issue.

## Nested Libraries

With an exact target jar, verification also scans class bytecode in recursively bundled libraries. It checks `net.minecraft` plus the game-owned `com.mojang.blaze3d`, `com.mojang.math`, `com.mojang.realmsclient`, and `com.mojang.renderpearl` class and member links from nested code, using exact method, field, and constructor descriptors. Other `com.mojang` packages are separate runtime libraries and are not treated as client-jar classes. This catches a bundled library that still calls an old Minecraft API without reporting DataFixerUpper, Authlib, Brigadier, or logging as missing game code.

Other unresolved references used only by a nested library are intentionally ignored. Libraries often include optional support for loaders, platforms, or mods that are not installed, so reporting every third-party link would turn valid optional integrations into false warnings. References made by the outer mod still receive the normal checks.

## What a Pass Means

A clean report means the check found no unresolved bytecode references in the host classes and mappings visible to it. A CLI check with `--mc-jar` has an exact Minecraft class and member index. Without that option, it can only inspect what the current process exposes. A clean report does not prove that methods still behave the same way or that every mixin applied.

Always launch the game or server and test the affected feature. Rendering, networking, world generation, and save data need real runtime checks.

Verification adds a small amount of work during transformation and no ongoing gameplay cost.
