# Retromod

Run older Minecraft mods on newer game versions.

[![Build JDK 25](https://img.shields.io/badge/Build_JDK-25-blue.svg)](https://adoptium.net/)
[![Minecraft 1.20 - 26.2](https://img.shields.io/badge/Minecraft-1.20%20--%2026.2-green.svg)](https://minecraft.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.3.0--snapshot.7-blueviolet.svg)]()
[![Modrinth](https://img.shields.io/badge/Download-Modrinth-00AF5C.svg)](https://modrinth.com/mod/retromod)
[![CurseForge](https://img.shields.io/badge/Download-CurseForge-F16436.svg)](https://www.curseforge.com/minecraft/mc-mods/retromod)

**[Download on Modrinth](https://modrinth.com/mod/retromod)** · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/retromod) · [Docs](https://bownlux.github.io/Retromod/) · [Compatibility database](https://bownlux.github.io/Retromod/compatdb/)

## What This Does

Retromod gives older Minecraft mods a better chance of running on current versions of the game. It updates bytecode, mappings, mixins, and loader metadata while keeping a backup of the original mod.

Its Mixin repair pass combines the mod's refmap with the exact methods in the target Minecraft jar. This lets it repair selected parameter-capturing handlers even when the annotation keeps a Yarn source name. It also keeps complete target-argument captures on supported MixinExtras value modifiers. Ambiguous or unsafe changes remain unchanged for a reviewed port.

Fabric runtime transforms recurse through four bundled jar levels. Classes, mixins, refmaps, access wideners, and per-archive compatibility helpers follow the nested path. On official-name targets, nested metadata and data files are updated too.

Other compatibility layers move a mod between loaders. Retromod moves a mod **between game versions**, so a mod built for 1.20.1 can run on a current release without waiting for the author to port it.

It works with Fabric, NeoForge, and Forge. Published host jars cover Fabric and Forge on Minecraft 1.20 through 26.2, plus NeoForge on 1.20.1 through 26.2. The code is built with JDK 25 but targets Java 17 bytecode; use the Java version required by the host Minecraft release.

Simple content mods, libraries, and quality-of-life mods are the best fit. Mods that replace large parts of Minecraft's renderer or loader may still need a proper port. Back up important worlds before testing save-affecting mods. Retromod preserves original mod jars, not world saves.

Each full distribution has 68 loader-specific jars and one executable standalone CLI jar. The CLI can transform a modpack without launching Minecraft:

```bash
java -jar retromod-1.3.0-snapshot.7-cli.jar --help
```

Start with the [installation guide](docs/installation.md), then check the [compatibility database](https://bownlux.github.io/Retromod/compatdb/) or [troubleshooting guide](docs/troubleshooting.md) if a mod needs more work. The full docs cover the [CLI](docs/cli.md), [technical details](docs/technical.md), [build integrity](docs/authenticity.md), [security policy](SECURITY.md), and [contributing](docs/contributing.md).
