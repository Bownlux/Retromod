---
title: Verify Transforms
nav_order: 6
---

# Verify Transforms

After transforming a jar, Retromod can check whether its Minecraft classes, methods, and fields exist on the host.

Verification is enabled by default. Toggle it in the GUI, set `"verify_transforms": false` in `config/retromod/config.json`, or pass `--verify` to the CLI.

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

Attach the report when filing a compatibility issue.

## What a Pass Means

A clean report means bytecode references resolve against the host jar. It does not prove that the methods still behave the same way or that every mixin applied.

Always launch the game or server and test the affected feature. Rendering, networking, world generation, and save data need real runtime checks.

Verification adds a small amount of work during transformation and no ongoing gameplay cost.
