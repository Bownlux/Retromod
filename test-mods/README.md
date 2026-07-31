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

Transform the built jar with Retromod, launch Minecraft, and wait for a stable title screen or server start. The summary appears during initialization, so it is not enough by itself. Also check that the run did not create a new crash report.

The full regression process is in [AGENTS.md](../AGENTS.md).
