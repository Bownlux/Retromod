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
| `verify_transforms` | `true` in a new file, `false` when absent | Check generated jars and write unresolved-reference reports |
| `debug` | `false` | Log a reference check for each Fabric or Quilt mod Retromod translates |
| `check_for_native_versions` | `false` | Opt in to a Modrinth API lookup for a native build of a selected mod |
| `restart_prompt` | `true` when absent | Show the restart prompt after files are prepared |
| `write_translated_jars` | `false` when absent | On Forge and NeoForge, `true` writes translated jars into `mods/` instead of loading them from `retromod-cache/` |
| `allow_huge_jars` | `false` | Translate any mod jar that unpacks to more than 25 GB once it passes the archive check, without asking |
| `approved_huge_jars` | `[]` when absent | SHA-256 hashes of jars over 25 GB that passed the archive check and were approved. **Allow** on the title-screen prompt adds one |
| `approved_unverified_jars` | `[]` when absent | SHA-256 hashes of jars approved after their archive check did not pass. `allow_huge_jars` never covers these |
| `loader_api_runtime` | `"auto"` when absent | Turn the Fabric API runtime for Minecraft 26.1 and newer on or off. See [Loader API Runtime](#loader-api-runtime) |
| `log_level` | `"INFO"` | `"DEBUG"` also writes Retromod's detail lines to `logs/latest.log`. See [Log Detail](#log-detail) |

Leave these defaults in place unless you are diagnosing a specific transform.

`write_translated_jars` is read at startup, so a change takes effect on the next launch. `-Dretromod.writeTranslatedJars=true` or `false` overrides the file for one launch. Fabric and Quilt ignore the key. They always write translated jars into `mods/`. Changing `polyfills_enabled` or `transform_mixins` translates the Forge and NeoForge cache again on the next launch.

A mod jar that unpacks to 1 GB or less is translated without a check. A larger one passes an archive check first: Retromod reads its headers, then unpacks each entry once to count it. The check fails when:

- entries overlap, repeat a name, or carry headers that disagree with each other or with Java's own zip reader
- an entry is encrypted, uses a compression method Java cannot read, or unpacks to a size other than the one it declares
- a class file is over 32 MB, or the jar's class files together pass 512 MB
- an entry over 4 MB unpacks to more than 100 times its stored size, or the jar to more than 20 times overall
- the jar and the archives inside it hold more than 100,000 entries together
- a nested jar in `META-INF/jars/` or `META-INF/jarjar/` is over 128 MB, or the archives inside the jar unpack to more than 500 MB together
- a nested archive fails any of these

A jar that passes is translated within the size it declares, up to 25 GB.

A jar whose check fails is held back, not translated, and the title screen asks about it with a warning that it may be a zip bomb. Allow it only if you trust where it came from. **Allow** records its SHA-256 in `approved_unverified_jars` and translates it on the next launch. `allow_huge_jars` does not cover it.

Past 25 GB, Retromod holds every jar back and asks the same way. When the headers pass, **Allow** records the hash in `approved_huge_jars`, and `allow_huge_jars` set to `true` allows such jars without asking. A dedicated server has no prompt, so its log names the hash to add. `-Dretromod.allowHugeJars=true` sets `allow_huge_jars` for one launch. The CLI takes `--allow-huge-jars` and `--allow-unverified-jars`.

`check_for_native_versions` is the only setting here that enables routine outbound HTTP. It checks Modrinth metadata and does not download a mod jar. You can also opt in for one run with `-Dretromod.checkForNativeVersions=true`. The in-game settings screen does not expose this key or `restart_prompt`. Set them by editing the JSON file.

`debug` writes its findings to the game log as `Reference check` lines. They name classes, methods, fields, and constructors that the translated jar calls but the running game does not have. The check matches methods by name and parameter count only, so a changed parameter type can pass. It checks a Mixin target only when a Mixin config names a Minecraft class directly. Retromod does not run it for Forge and NeoForge mods, in the CLI, or during `use_aot` preparation. `verify_transforms` checks references on every loader and writes a report file instead.

## Log Detail

At the default `"INFO"`, the game log gets a short record from Retromod: the start line, one line per mod it translated or served, the end-of-pass summary, in-place loading status, and the build check. Warnings and errors always appear.

Every translation step, loaded polyfill, registered bridge, and scan result is a detail line. On Forge and NeoForge, detail lines always go to `logs/debug.log`. Fabric and Quilt have no `debug.log`, so set `log_level` to `"DEBUG"` to see them there. With `"DEBUG"`, detail lines also go to `logs/latest.log` on every loader. `-Dretromod.logLevel=debug` does the same for one launch. Retromod reads the setting once per launch.

Forge and NeoForge translate a mod once and serve it from the cache after that, so its detail lines appear only in the launch that translated it. Run `cache clear <game-dir>` with the CLI to translate again.

## Loader API Runtime

`loader_api_runtime` turns the Fabric API runtime for Minecraft 26.1 and newer on or off. It accepts `"auto"`, `true`, or `false`. `"auto"`, or no key at all, turns the runtime on in snapshot builds and off in release candidates and stable builds. `true` turns it on in every build, and `false` turns it off in every build.

`-Dretromod.loaderApiRuntime=auto`, `true`, or `false` overrides the file for one launch. The CLI takes `--loader-api-runtime=auto`, `true`, or `false` and does not read the file. Any other value logs a warning and counts as `"auto"`. The key is not written into a new config file.

The runtime affects only Fabric and Quilt mods on Fabric and Quilt hosts for 26.1 and newer. Forge and NeoForge translation does not change, and `polyfills_enabled` does not turn the runtime off. This build carries its foundation and a few fixes. Most old Fabric API calls it will cover still depend on the existing shims.

With the runtime on, each translated jar carries `Retromod-Loader-Api-Runtime` in its manifest, and the log lists every Fabric API reference the host still lacks. The stamps of `config/retromod/aot-cache/` and `retromod-cache/full-aot/` include the runtime version, so those caches are rebuilt when it changes. With the runtime off, Retromod translates jars exactly as builds without the runtime did.

Changing the setting translates the affected jars again on the next launch, and so does the first launch of a build that turns the runtime on or off by `"auto"`. Retromod compares the setting each jar in `mods/` was built with to the current one. It rebuilds a mismatched jar from its original in `retromod-input/processed/` and asks for the usual restart. Fabric has already loaded the old jar by then, so the rebuilt copy waits in `retromod-input/pending/` and replaces it when the game closes. A jar whose original is gone from `processed/` stays as it is, and the log says so. Put the original jar back in `retromod-input/` to update it.

Windows keeps a loaded jar locked until the game exits, so Retromod hides the old jar instead of replacing it. The next launch skips it, installs the copy from `retromod-input/pending/`, and asks for one more restart. If the log says a jar in `mods/` still runs the build from before the change, close the game, delete that jar, and start the game again.

## Reserved Settings

The generated file also contains settings that are reserved for later runtime wiring. A new config file includes them, but the transform pipeline does not consult them yet:

| Key | Default | Purpose |
|---|---:|---|
| `use_hybrid` | `true` | Reserved hybrid-engine toggle |
| `instruction_level_granularity` | `true` | Reserved transform-granularity toggle |
| `transform_mixins` | `true` | Read by the Fabric initializer, but not yet used to gate the mixin passes |
| `transform_refmaps` | `true` | Reserved refmap-pass toggle |
| `remap_reflection` | `true` | Reserved JSON toggle. The current transformer uses the `retromod.remapReflection` system property |
| `log_transformations` | `false` | Reserved per-redirect logging toggle |
| `target_mc_version` | `"auto"` | Reserved override. Loader detection and the CLI `--target` option select the target |
| `dump_bytecode` | `false` | Reserved bytecode-dump toggle |

Do not depend on a reserved key changing behavior yet. The in-game screen shows two shared toggles, plus `use_aot` on Fabric and Quilt and `write_translated_jars` on Forge and NeoForge. A change writes only that key and keeps every other valid JSON key as it is.

## Editing the File

The file is plain JSON and cannot contain JSON comments. The `_comment` field is ordinary data and is valid. Older files may still contain `force_translate_complex`. Retromod ignores it, because it tries every mod. If the file is invalid, Retromod logs a warning and uses defaults for that launch. Delete the file to regenerate it.

Set `check_for_native_versions`, `restart_prompt`, `debug`, `log_level`, and reserved keys by editing the file. The settings screen does not display them, but it preserves them when saving its toggles.

Verification reports are written to `config/retromod/verify-reports/`. The normal AOT cache lives in `config/retromod/aot-cache/`. Full AOT state lives in `retromod-cache/full-aot/`. Forge and NeoForge translations live in `retromod-cache/translated/`.
