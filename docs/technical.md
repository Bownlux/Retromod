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

Mixin annotations and refmaps are rewritten separately from normal instructions. Namespace conversion and registered redirects run before repairs that need Mojang descriptors.

Retromod indexes the installed Minecraft jar during a game launch. Offline `transform`, `aot`, and `batch` commands use `--mc-jar <target.jar>` to build the same method index. If `--target` is absent, the CLI reads `version.json` from that jar, with a standard client filename as a fallback.

The automatic translator accepts only uniquely proven shapes. The main case is a same-name method with an unchanged return type where the old parameters remain in order and no more than three parameters were added. Selected callback-only injections, exact-prefix captures, invokers, overwrites, and call-mirroring injectors have additional shape checks. A MixinExtras `@ModifyReturnValue` or `@ModifyExpressionValue` handler can also follow the change when its first parameter is the intercepted value and the remaining parameters exactly match every old target argument. New target arguments are inserted after that value. Ambiguous overloads, constructors, partial captures, reordered or removed parameters, changed return types, semantic local captures, unsafe parameter annotations, multiple inferred layouts, and `remap = false` scopes are left unchanged. A failed frame rebuild falls back to the last valid result.

Curated adapters handle some semantic changes that cannot be inferred from descriptors alone. The ValueIO adapter, for example, preserves an old `CompoundTag` handler behind an explicit input or output bridge. Exact 26.x entity bridges can rebuild older tag, targeting, damage, and registration calls when the current behavior is known. Retromod does not generalize those conversions to unrelated methods.

The legacy entity-renderer adapter is deliberately narrower. It can make a direct old `EntityRenderer` subclass loadable by supplying a base render state and removing its deleted direct-super call. It does not translate the old render body into the modern submit pipeline, so custom geometry can remain invisible. A renderer with a different hierarchy or signature is left unchanged.

Known fatal handlers can be removed through the mixin blocklist, leaving the associated feature inactive.

## Loader Namespaces

Fabric 26.1 and newer uses the full intermediary-to-Mojang pass. Pre-26.1 Fabric hosts must remain intermediary-named, so they use targeted bridges for known API changes instead of a global remap. That path has real old-mod launch coverage but is not a universal bridge for redesigned APIs.

NeoForge is Mojang-named. Forge source names are decoded through SRG mappings. When the host is Forge 1.20.1, a second owner-and-descriptor-qualified table emits the exact target SRG names. Other pre-26 Forge hosts currently keep unsupported source members unchanged instead of guessing.

Forge-to-NeoForge support combines metadata promotion, mappings, and per-mod synthetic classes for selected common APIs. It does not recreate every Forge registry, networking, data-generation, or event subsystem.

## Embedded Classes

Deleted loader APIs are embedded under a unique `com/retromod/embedded/<mod-key>/` path. This avoids split packages between the loader and multiple transformed mods. A helper used inside nested jars derives its key from the complete parent archive chain, so two libraries with the same filename do not share a generated class.

## Cache and Verification

AOT caches include a stamp derived from the Retromod version and its executable-surface hash. Packaged builds clear a cache when the stamp changes.

The verifier checks transformed references visible from its host classpath and mapping index. It catches many linkage gaps, not semantic changes, and a clean report is not a gameplay test.

## Security Model

Mods are untrusted input. Runtime writes stay in the selected game directory. The CLI writes its requested outputs and initializes the standard Retromod input, backup, and API-archive folders relative to its current working directory. Commands create caches and reports there when needed. Archive entries are validated and bounded before extraction or rewrite. Fabric runtime recursion stops after four nested levels.

Normal transformation is offline. The native-version lookup is opt-in through `check_for_native_versions` or a system property. CLI API-archive downloads require an explicit command and consent, or `--yes` for scripted use.

The embedded build hash is an integrity hint, not a digital signature. See [Authenticity]({{ '/authenticity' | relative_url }}).
