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
| `write_translated_jars` | `false` when absent | On Forge and NeoForge, `true` writes translated jars into `mods/` instead of loading them from `retromod-cache/` |
| `loader_api_runtime` | `"auto"` when absent | Turn the Fabric API runtime for Minecraft 26.1 and newer on or off. See [Loader API Runtime](#loader-api-runtime) |

Leave these defaults in place unless you are diagnosing a specific transform.

`write_translated_jars` is read at startup, so a change takes effect on the next launch. `-Dretromod.writeTranslatedJars=true` or `false` overrides the file for one launch. Fabric and Quilt ignore the key. They always write translated jars into `mods/`. Changing `polyfills_enabled` or `transform_mixins` translates the Forge and NeoForge cache again on the next launch.

`check_for_native_versions` is the only setting here that enables routine outbound HTTP. It checks Modrinth metadata and does not download a mod jar. You can also opt in for one run with `-Dretromod.checkForNativeVersions=true`. The in-game settings screen does not expose this key or `restart_prompt`. Set them by editing the JSON file.

## Loader API Runtime

`loader_api_runtime` turns the Fabric API runtime for Minecraft 26.1 and newer on or off. It accepts `"auto"`, `true`, or `false`. `"auto"`, or no key at all, turns the runtime on in snapshot builds and off in release candidates and stable builds. `true` turns it on in every build, and `false` turns it off in every build.

`-Dretromod.loaderApiRuntime=auto`, `true`, or `false` overrides the file for one launch. The CLI takes `--loader-api-runtime=auto`, `true`, or `false` and does not read the file. Any other value logs a warning and counts as `"auto"`. The key is not written into a new config file.

The runtime affects only Fabric and Quilt mods on Fabric and Quilt hosts for 26.1 and newer. Forge and NeoForge translation does not change, and `polyfills_enabled` does not turn the runtime off. This build carries its foundation and a few fixes. Most old Fabric API calls it will cover still depend on the existing shims.

With the runtime on, each translated jar carries `Retromod-Loader-Api-Runtime` in its manifest, and the log lists every Fabric API reference the host still lacks. The stamps of `config/retromod/aot-cache/` and `retromod-cache/full-aot/` include the runtime version, so those caches are rebuilt when it changes. With the runtime off, Retromod translates jars exactly as builds without the runtime did.

Changing the setting translates the affected jars again on the next launch, and so does the first launch of a build that turns the runtime on or off by `"auto"`. Retromod compares the setting each jar in `mods/` was built with to the current one. It rebuilds a mismatched jar from its original in `retromod-input/processed/` and asks for the usual restart. Fabric has already loaded the old jar by then, so the rebuilt copy waits in `retromod-input/pending/` and replaces it when the game closes. A jar whose original is gone from `processed/` stays as it is, and the log says so. Put the original jar back in `retromod-input/` to update it.

Windows keeps a loaded jar locked until the game exits, so Retromod hides the old jar instead of replacing it. The next launch skips it, installs the copy from `retromod-input/pending/`, and asks for one more restart. If the log says a jar in `mods/` still runs the build from before the change, close the game, delete that jar, and start the game again.

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

Do not depend on a reserved key changing behavior yet. The in-game screen exposes four active toggles, plus `write_translated_jars` on Forge and NeoForge. It preserves other valid JSON keys when saving them.

## Editing the File

The file is plain JSON and cannot contain JSON comments. The `_comment` field is ordinary data and is valid. If the file is invalid, Retromod logs a warning and uses defaults for that launch. Delete the file to regenerate it.

Set `check_for_native_versions`, `restart_prompt`, and reserved keys by editing the file. The settings screen does not display them, but it preserves them when saving its toggles.

Verification reports are written to `config/retromod/verify-reports/`. The normal AOT cache lives in `config/retromod/aot-cache/`. Full AOT state lives in `retromod-cache/full-aot/`. Forge and NeoForge translations live in `retromod-cache/translated/`.
