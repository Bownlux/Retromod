# Retromod Compatibility

Compatibility depends on the exact mod version, loader, host Minecraft version, and Retromod build. A mod that works in one combination may fail in another.

The [compatibility database](https://bownlux.github.io/Retromod/compatdb/) has the current test results. It replaces the old static list that used to live in this file.

## Supported Ranges

| Loader | Host Minecraft | Source mods |
|---|---|---|
| Fabric | 1.20-26.2 | 1.14.4 and newer |
| NeoForge | 1.20.1-26.2 | 1.20.1 and newer |
| Forge | 1.20-26.2 | 1.12.2 and newer |

These ranges mean Retromod has a transform path. They do not promise that every mod in the range will work.

## What Usually Works

Simple content mods, libraries, recipe viewers, and quality-of-life mods are the best candidates. Mods with a small set of Minecraft or loader hooks are easier to translate and verify.

Large rendering mods, coremods, and projects built around deleted game systems often need a real source port. See [Mods That Can't Be Translated](docs/incompatible-mods.md) for common examples.

## Reading Test Results

- **Diamond:** behaves like a native build in the tested setup
- **Gold:** playable, with only minor issues
- **Iron:** core features work, but something noticeable is broken
- **Copper:** loads, but major features are missing
- **Borked:** crashes or is not usable

A clean bytecode transform is only the first check. Good reports also confirm that the game reaches a stable title screen or server startup and that the mod's important features work.

## Share a Result

Use the form at the bottom of the [compatibility database](https://bownlux.github.io/Retromod/compatdb/). Include the mod version, source and host Minecraft versions, loader, Retromod version, and what you tested.
