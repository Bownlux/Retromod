---
title: Config Reference
nav_order: 4
---

# Config Reference

Settings live in `config/retromod/config.json`. Retromod creates the file on first launch, and the [in-game settings screen]({{ '/gui' | relative_url }}) edits the same values.

## Active Settings

| Key | Default | Purpose |
|---|---:|---|
| `use_aot` | `true` | Prepare legacy mods ahead of launch on the Fabric entry point |
| `polyfills_enabled` | `true` | Enable or disable polyfill-provider registration on Fabric, Forge, and NeoForge |
| `verify_transforms` | `true` | Check generated jars and write unresolved-reference reports |
| `force_translate_complex` | `false` | Let the in-game file picker attempt a high-risk mod |
| `check_for_native_versions` | `false` | Opt in to a Modrinth API lookup for a native build of a selected mod |
| `restart_prompt` | `true` when absent | Show the restart prompt after files are prepared |

Leave these defaults in place unless you are diagnosing a specific transform.

`check_for_native_versions` is the only setting here that enables routine outbound HTTP. It checks Modrinth metadata and does not download a mod jar. You can also opt in for one run with `-Dretromod.checkForNativeVersions=true`. The in-game settings screen does not expose this key or `restart_prompt`. Set them by editing the JSON file.

## Reserved Settings

The generated file also contains settings that are reserved for later runtime wiring. Snapshot.6 writes them into a new file, but the transform pipeline does not currently consult them:

| Key | Default | Purpose |
|---|---:|---|
| `use_hybrid` | `true` | Reserved hybrid-engine toggle |
| `instruction_level_granularity` | `true` | Reserved transform-granularity toggle |
| `transform_mixins` | `true` | Read by the Fabric initializer, but not yet used to gate the mixin passes |
| `transform_refmaps` | `true` | Reserved refmap-pass toggle |
| `remap_reflection` | `true` | Reserved JSON toggle. The current transformer uses the `retromod.remapReflection` system property |
| `log_level` | `"INFO"` | Reserved log-level setting |
| `log_transformations` | `false` | Reserved per-redirect logging toggle |
| `target_mc_version` | `"auto"` | Reserved override. Loader detection and the CLI `--target` option select the target |
| `debug` | `false` | Reserved debug toggle |
| `dump_bytecode` | `false` | Reserved bytecode-dump toggle |

Do not depend on a reserved key changing behavior yet. The in-game screen exposes only four active toggles, but it preserves other valid JSON keys when saving them.

## Editing the File

The file is plain JSON and cannot contain JSON comments. The `_comment` field is ordinary data and is valid. If the file is invalid, Retromod logs a warning and uses defaults for that launch. Delete the file to regenerate it.

Set `check_for_native_versions`, `restart_prompt`, and reserved keys by editing the file. The settings screen does not display them, but it preserves them when saving its four toggles.

Verification reports are written to `config/retromod/verify-reports/`. The normal AOT cache lives in `config/retromod/aot-cache/`. Full AOT state lives in `retromod-cache/full-aot/`.
