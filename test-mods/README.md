# test-mods

These small mods exercise Retromod inside a real Minecraft launch. Each test writes a clear pass or fail line to the game log.

| Project | Loader | Notes |
|---------|--------|-------|
| `retromod-test-mod/` | Fabric | The primary suite. Test cases live in `src/main/java/com/retromod/testmod/tests/`. |
| `retromod-test-mod-forge/` | Forge | Loader-specific cases. |
| `retromod-test-mod-neoforge/` | NeoForge | Loader-specific cases. |

The test mods use each loader's normal Gradle tooling. Retromod itself still uses Maven because it does not compile against Minecraft.

Build the project for the loader you are testing:

```bash
cd test-mods/retromod-test-mod
./gradlew build
```

Each build also writes a small library mod next to the test mod: `retromod-test-lib`, `retromod-test-lib-forge`, or `retromod-test-lib-neoforge`. Install it alongside the test mod. The cross-jar case calls its API, which takes types Retromod rebuilds inside every translated jar, so without the library that case fails. The Forge and NeoForge libraries name their Minecraft version only in the jar manifest, and a separate case checks that Retromod translates them whenever it translates the test mod.

The Fabric and Forge test mods compile a few calls against stand-ins in `src/mcstubs/` for Minecraft classes newer than the version they build for, such as 1.21.11's `EitherHolder`. The stand-ins are not packaged, so the jar carries the names a newer mod would.

The Forge test mod also compiles against stand-ins of the Farmer's Delight 1.20.1 API in `src/fdstubs/`, which are not packaged. Its #302 case builds a knife and reads Farmer's Delight's registry fields during item registration. Install Farmer's Delight for the host's Minecraft version, such as 1.21.1-1.3.4 on NeoForge 1.21.1, to run it. Without the library the case logs `skipped`.

Transform the built jar with Retromod, launch Minecraft, and wait for a stable title screen or server start. The summary appears during initialization, so it is not enough by itself. Also check that the run did not create a new crash report.

The full regression process is in [AGENTS.md](../AGENTS.md).
