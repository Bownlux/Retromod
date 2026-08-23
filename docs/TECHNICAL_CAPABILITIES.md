# Technical Capabilities

Retromod translates Java bytecode and selected mod resources. Its limits follow from that scope.

## Works Well

- Class and package moves
- Method and field renames
- Known descriptor changes
- Constructor-to-factory migrations
- Fabric intermediary and Forge SRG mappings
- Metadata and dependency ranges
- Mixin target and refmap renames
- Unambiguous Mixin parameter additions proven against the target jar
- Selected removed APIs
- AOT caching and output verification

## Needs a Specific Bridge

- Parameter additions outside the safe automatic shape, plus removed or reordered parameters
- Changed return types
- Loader event and registry migrations
- Mixin handler signature changes outside the safe automatic subset
- Reflection strings
- Resource and data schema migrations

These changes are possible when the old and new behavior can be related explicitly. They are not safe as global guesses.

## Automatic Mixin Translation

At game launch Retromod indexes the installed Minecraft classes. Offline `transform`, `aot`, and `batch` commands can get the same facts from `--mc-jar <target.jar>`.

The automatic pass accepts a unique same-name target with the same return type when the old parameters remain an ordered subsequence and no more than three parameters were added. It also supports selected callback-only injections, exact-prefix captures, invokers with a unique semantic match, safe call-mirroring injectors, and MixinExtras value modifiers that capture the complete old target argument list. Namespace and registered redirects run first.

It declines ambiguity, constructors, partial captures, reordered or removed parameters, changed returns, semantic local captures, unsafe parameter annotations, multiple inferred handler layouts, frame-rebuild failures, and any relevant `remap = false`. Curated bridges still handle known semantic migrations such as the ValueIO save-data change.

Some renderer migrations can be staged safely without claiming full visual compatibility. For a direct legacy `EntityRenderer` subclass with the exact old method shape, Retromod can satisfy the new render-state contract and remove the deleted direct-super call. The old render body is not converted into modern submit commands, so its custom geometry may still need a source port.

## Loader Namespace Boundaries

- Fabric on 26.1 and newer maps intermediary names to Mojang names.
- Fabric before 26.1 stays in intermediary names and uses a growing set of targeted bridges. Coverage is useful but not complete.
- NeoForge mods already use Mojang names.
- Old Forge names map to Mojang names on unobfuscated hosts. Forge 1.20.1 also has an owner-and-descriptor-qualified target-SRG table. Other pre-26 Forge targets do not yet have equivalent complete tables.
- Forge-to-NeoForge migration covers selected common APIs, not every Forge subsystem.

## Usually Cannot Be Automated

- Rewritten method logic with no matching injection point
- Native libraries
- Shader source and custom render pipelines
- Another mod's version-specific bytecode transformer
- Deleted systems whose replacement has a different data model
- Integrity checks that intentionally reject modified bytecode

## Practical Meaning

A clean reference report means no unresolved references were found by that check. It does not prove every host class was visible to the verifier, nor does it prove gameplay behavior. Test the feature that matters, especially rendering, world generation, networking, and save data. Use a copied instance and a backed-up world for save-affecting mods.

See [Architecture](architecture.md), [Verify Transforms](verify-transforms.md), and [Mods That Can't Be Translated](incompatible-mods.md).
