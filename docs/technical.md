---
title: Technical Details
nav_order: 11.5
---

# Technical Details

Retromod uses ASM 9.8 and compiles with Java 25 while targeting Java 17 bytecode. Host Minecraft still determines the required JVM.

## Bytecode Rewrites

The visitor pipeline applies class remapping before owner/name/descriptor redirects:

```text
ClassReader
  -> ClassRemapper
  -> RetromodClassVisitor
  -> ClassWriter
```

Supported operations include class moves, member renames, descriptor changes, constructor-to-factory conversion, field access bridges, selected argument conversions, and removed-call neutralization.

Fabric intermediary and Forge SRG mappings use separate tables. Mojang-to-Mojang changes stay owner-scoped because readable names are not globally unique.

## Frames

ASM recomputes stack-map frames after structural rewrites. Mod classes are often unavailable through `Class.forName` during pre-launch, so Retromod can read their hierarchy from the jar being transformed. It only narrows a frame type when the common ancestor can be proven to exist at runtime.

If a frame-changing rewrite cannot be emitted safely, Retromod keeps the earlier valid result or the original class.

## Mixins

Mixin annotations and refmaps are rewritten separately from normal instructions. Retromod handles many renamed targets and a curated set of signature changes. It does not guess when a method body or local-variable layout was redesigned.

Known fatal handlers can be removed through the mixin blocklist, leaving the associated feature inactive.

## Embedded Classes

Deleted loader APIs are embedded under a unique `com/retromod/embedded/<mod-key>/` path. This avoids split packages between the loader and multiple transformed mods.

## Cache and Verification

AOT caches include a stamp derived from the Retromod version and its own class hash. Packaged builds clear a cache when the stamp changes.

The verifier checks transformed references against the host jar. It catches linkage gaps, not semantic changes.

## Security Model

Mods are untrusted input. Retromod limits writes to its game-directory paths, does not make runtime network requests by default, and treats failed transformations as errors instead of executing downloaded code.

The embedded build hash is an integrity hint, not a digital signature. See [Authenticity]({{ '/authenticity' | relative_url }}).
