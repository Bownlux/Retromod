# Technical Capabilities

Retromod translates Java bytecode and selected mod resources. Its limits follow from that scope.

## Works Well

- Class and package moves
- Method and field renames
- Known descriptor changes
- Constructor-to-factory migrations
- Fabric intermediary and Forge SRG mappings
- Metadata and dependency ranges
- Mixin target renames
- Selected removed APIs
- AOT caching and output verification

## Needs a Specific Bridge

- Added, removed, or reordered parameters
- Changed return types
- Loader event and registry migrations
- Mixin handler signature changes
- Reflection strings
- Resource and data schema migrations

These changes are possible when the old and new behavior can be related explicitly. They are not safe as global guesses.

## Usually Cannot Be Automated

- Rewritten method logic with no matching injection point
- Native libraries
- Shader source and custom render pipelines
- Another mod's version-specific bytecode transformer
- Deleted systems whose replacement has a different data model
- Integrity checks that intentionally reject modified bytecode

## Practical Meaning

A clean reference report means the transformed jar links against the host. It does not prove gameplay behavior. Test the feature that matters, especially rendering, world generation, networking, and save data.

See [Architecture](architecture.md), [Verify Transforms](verify-transforms.md), and [Mods That Can't Be Translated](incompatible-mods.md).
