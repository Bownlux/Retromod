---
title: Config reference
nav_order: 4
---

# Config Reference

Settings live in `config/retromod/config.json`. Retromod creates the file on first launch, and the [in-game settings screen]({{ '/gui' | relative_url }}) edits the same values.

## Main Settings

| Key | Default | Purpose |
|---|---:|---|
| `use_aot` | `true` | Cache transformed mods for faster later launches |
| `use_hybrid` | `true` | Use cached classes when available and transform the rest |
| `instruction_level_granularity` | `true` | Apply precise instruction-level rewrites |
| `transform_mixins` | `true` | Update mixin targets |
| `transform_refmaps` | `true` | Update mixin refmap names |
| `remap_reflection` | `true` | Rewrite supported reflective class and member names |
| `polyfills_enabled` | `true` | Provide replacements for selected removed APIs |
| `verify_transforms` | `true` | Report unresolved references after transformation |
| `target_mc_version` | `"auto"` | Detect the host version from the loader |
| `force_translate_complex` | `false` | Attempt mods that fail the complexity check |

Leave these defaults in place unless you are diagnosing a specific transform.

## Logging

| Key | Default | Purpose |
|---|---:|---|
| `log_level` | `"INFO"` | `TRACE`, `DEBUG`, `INFO`, `WARN`, or `ERROR` |
| `log_transformations` | `false` | Log individual redirects |
| `debug` | `false` | Enable extra transformer checks |
| `dump_bytecode` | `false` | Write transformed classes for decompilation |

Debug logs and bytecode dumps can become large. Turn them off after reproducing the issue.

## Editing the File

The file is plain JSON and cannot contain comments. If it is invalid, Retromod logs a warning and uses defaults for that launch. Delete the file to regenerate it.

Verification reports are written to `config/retromod/verify-reports/`. AOT cache files live in `config/retromod/aot-cache/`.
