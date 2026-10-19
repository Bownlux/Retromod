# Retromod

[![Minecraft 1.20-26.3](https://img.shields.io/badge/Minecraft-1.20--26.3-green.svg)](https://minecraft.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.4.0--snapshot.2-blueviolet.svg)](https://github.com/Bownlux/Retromod/releases)
[![Modrinth](https://img.shields.io/badge/Download-Modrinth-00AF5C.svg)](https://modrinth.com/mod/retromod)
[![CurseForge](https://img.shields.io/badge/Download-CurseForge-F16436.svg)](https://www.curseforge.com/minecraft/mc-mods/retromod)

[Download on Modrinth](https://modrinth.com/mod/retromod) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/retromod) · [Docs](https://bownlux.github.io/Retromod/) · [Compatibility database](https://bownlux.github.io/Retromod/compatdb/)

Retromod runs older Minecraft mods on newer versions of the game, without waiting for the mod's author to update it. It works on Fabric, Quilt, and Forge for Minecraft 1.20 through 26.3, and on NeoForge 1.20.1 through 26.3. Mods built around their own renderer or deep loader internals, such as Create or OptiFine, still need a real port.

Retromod translates with fixed rules and mapping tables, not AI. It updates a mod's names, mixins, and metadata for your Minecraft version, fills in removed APIs where it can, and reports anything it could not resolve. Everything runs on your computer with no network requests. AI tools helped build Retromod, but no AI runs when a mod is translated.

## 📥 Downloads

Download Retromod from [Modrinth](https://modrinth.com/mod/retromod) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/retromod). Pick the jar that matches both your loader and your Minecraft version. Quilt uses the Fabric jar, which carries both `fabric.mod.json` and `quilt.mod.json`.


## 🖥️ Installation

Put Retromod in `mods/`. On Forge and NeoForge, put old mods in `mods/` too, launch, and restart when prompted. Retromod translates each old jar into `retromod-cache/translated/` and loads that copy, so the files in `mods/` never change. Later launches load the cached copy with no restart. Set `write_translated_jars` to `true` in `config/retromod/config.json` to write translated jars into `mods/` instead, as earlier versions did.

On Fabric and Quilt, also install Fabric API, then launch once and close the game. Put old mods in `retromod-input/`, launch, and restart when prompted. Fabric and Quilt check mod versions before Retromod runs, so an old jar in `mods/` stops the game. Retromod moves each translated jar to `mods/` and keeps the original in `retromod-input/processed/`.

Back up your worlds first, because Retromod keeps mod jars, not saves. The [installation guide](https://bownlux.github.io/Retromod/installation) covers resource packs, data packs, sharing translated mods, updating, and uninstalling.

## 🙇 Getting Help

Start with `logs/latest.log`. The first Retromod, loader, or mixin error usually explains more than the final crash. The [troubleshooting guide](https://bownlux.github.io/Retromod/troubleshooting) covers common errors, and the [FAQ](https://bownlux.github.io/Retromod/faq) covers servers, modpacks, and safety. The full [documentation](https://bownlux.github.io/Retromod/) also covers configuration, the CLI, and internals.

## 📬 Reporting Issues

Open a [bug report](https://github.com/Bownlux/Retromod/issues/new?template=bug_report.yml) when a mod fails to transform, crashes, or behaves incorrectly after Retromod runs. Attach `logs/latest.log`, any report in `config/retromod/verify-reports/`, and `config/retromod/config.json`. Include a link to the exact mod version and the Minecraft version it was built for. Also name your loader and give your Minecraft, loader, Java, and Retromod versions. On Quilt, pick Fabric in the loader field and mention Quilt in the description.

Report security problems privately through [GitHub's private vulnerability reporting](https://github.com/Bownlux/Retromod/security/advisories/new), as [SECURITY.md](SECURITY.md) describes. Do not open a public issue for them.

## 💬 Join the Community

Ask questions and propose designs in [GitHub Discussions](https://github.com/Bownlux/Retromod/discussions). Small, focused pull requests are welcome. Open a discussion before you start a large new subsystem. See [Contributing](https://bownlux.github.io/Retromod/contributing).

After you test a mod, file a [compatibility report](https://github.com/Bownlux/Retromod/issues/new?template=compat_report.yml), including partial results. Merged reports appear in the compatibility database.

## ✅ Compatibility

Retromod ships one jar per loader and Minecraft version.

| Loader | Host Minecraft | Mods built for |
|---|---|---|
| Fabric | 1.20 through 26.3 | 1.14.4 and newer |
| Quilt | 1.20 through 26.3, using the Fabric jar | 1.18.2 and newer |
| Forge | 1.20 through 26.3 | 1.12.2 and newer |
| NeoForge | 1.20.1 through 26.3 | 1.20.1 and newer |

Content mods, libraries, and quality-of-life mods work best. Test each mod in game: the [compatibility database](https://bownlux.github.io/Retromod/compatdb/) lists reported results, and [Mods That Can't Be Translated](https://bownlux.github.io/Retromod/incompatible-mods) lists the known gaps.

## ⌨️ Command-Line Tool

The standalone CLI runs the same transform core without launching Minecraft. Use it for servers, modpacks, CI, and compatibility checks.

```bash
java -jar retromod-1.4.0-snapshot.2-cli.jar batch path/to/mods \
  --aot --mc-jar path/to/minecraft-26.3-client.jar --verify
```

`batch` writes to `<input>/retromod-output/` and leaves the source jars untouched. `--aot` prepares cached transforms. `--mc-jar` gives mixin repair the exact target jar. `--verify` writes unresolved-reference reports to `config/retromod/verify-reports/` in the working directory. `export <game-dir>` copies the translations a Forge or NeoForge game made, so you can share them with a game that runs the same loader, Minecraft version, and Retromod. Run `--help` for every command, or read the [CLI guide](https://bownlux.github.io/Retromod/cli).

## 🛠️ Building from Source

Retromod builds with Maven. You need JDK 25 and Maven 3.8 or newer. It compiles with Java 25 but emits Java 17 bytecode, so the same code runs on Java 17, 21, and 25. Each jar still needs the Java version its Minecraft requires.

```bash
git clone https://github.com/Bownlux/Retromod.git
cd Retromod
mvn package -Dexec.skip=true
```

Always pass `-Dexec.skip=true`. Without it, `mvn package` also runs `build-all.sh --skip-build` and rebuilds `dist/`. Add `-DskipTests` to skip the test suite, or run `mvn test -Dexec.skip=true` to run only the tests. The build writes `target/retromod-<version>.jar` and `target/retromod-<version>-all.jar`.

To build the jars you install in a game, run the release script. It also needs Python 3, `zip`, and `unzip`.

```bash
bash build-all.sh
```

It writes 71 loader jars to `dist/{Fabric,Forge,NeoForge}/<mc>/retromod-<version>+<mc>.jar` and the CLI to `dist/CLI/retromod-<version>-cli.jar`. A complete build also writes `dist/SHA256SUMS.txt` for all 72 artifacts. Test in game with these jars, not the raw `-all.jar`. That jar bundles its own ASM, which conflicts with the copy every loader already provides. See [Contributing](https://bownlux.github.io/Retromod/contributing) for tests and release steps.

## 📜 License

Retromod is released under the [MIT License](LICENSE). Contributions are distributed under the same license. You still need to follow the licenses of Minecraft, your loader, and every mod you translate. Files adapted from Fabric API stay under the Apache License 2.0, and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists them.

## 🙏 Credits

Retromod is made by Bownlux, a developer of [RevivalSMP.net](https://revivalsmp.net). Thanks to every [GitHub contributor](https://github.com/Bownlux/Retromod/graphs/contributors).

Retromod builds on open-source work, and thanks the people who keep it open:

- [OW2 ASM](https://asm.ow2.io/), which reads and writes every class Retromod translates
- [FabricMC](https://fabricmc.net/) for Fabric Loader, Fabric API, and the intermediary mappings
- the [Quilt](https://quiltmc.org/), [NeoForge](https://neoforged.net/), and [Minecraft Forge](https://minecraftforge.net/) teams for their open loaders
- [SpongePowered Mixin](https://github.com/SpongePowered/Mixin) and [MixinExtras](https://github.com/LlamaLad7/MixinExtras)
- [Sinytra Connector](https://github.com/Sinytra/Connector), parts of which Retromod adapts under the MIT License
- [Gson](https://github.com/google/gson), [Guava](https://github.com/google/guava), and [SLF4J](https://www.slf4j.org/)

[![wakatime](https://wakatime.com/badge/github/Bownlux/Retromod.svg)](https://wakatime.com/badge/github/Bownlux/Retromod)