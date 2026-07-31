# Retromod - Claude Development Guide

Retromod transforms older Minecraft mod bytecode so old mods work on newer MC versions. It supports Fabric, NeoForge, and Forge with ~120 version shims and ~35 polyfill providers (counts drift; run `grep -cv '^#' META-INF/services/...` for the current numbers).

**Repository:** https://github.com/Bownlux/Retromod.git

## Writing style

**Do not use em-dashes (the long dash, Unicode U+2014) anywhere:** not in chat replies, not in code comments, docs, CHANGELOG/ROADMAP entries, or commit messages. Also avoid en-dashes (U+2013). They are annoying to copy out of responses. Use a comma, parentheses, a colon, or two separate sentences instead. (Ordinary hyphens `-`, e.g. in version ranges like `1.20-26.2`, are fine.)

Write like a maintainer talking to another person:

- Lead public text with what changed for the user. Add implementation detail only when it helps someone understand a limitation or diagnose a problem.
- Prefer short sentences and ordinary words. Avoid all-caps emphasis, canned headings, breathless claims, and long parenthetical asides.
- Keep changelog entries focused. State the symptom, the fix, any honest limitation, and the test coverage. Leave the full debugging history in the issue or commit.
- Comments should explain why a choice is necessary, especially when the obvious implementation would be wrong. Do not restate the code or narrate the investigation.
- Use clear names and small helpers so the code explains its own mechanics. Avoid dense nested conditionals, clever one-liners, and temporary abbreviations that make readers decode the implementation.
- Make errors actionable. Say what failed, which input was involved, and what the user can do next. Do not blame the user or expose irrelevant internals.
- Tests should read as examples of behavior. Keep setup helpers reusable, test names direct, and assertion messages useful when a failure occurs.

## Critical Context

- **Target MC version:** 26.1 (Mojang removed ALL code obfuscation); 26.2 supported since 1.1.0-snapshot.4 (shims/aliases/Fabric build target in place, verified on 26.2-rc-1)
- **Java:** Built WITH Java 25 (we need the modern compiler to use ASM 9.8 features that read MC 26.1's class file format), but bytecode targets `--release 17` so the SAME JAR runs on Java 17, 21, and 25. Broad runtime compat is the entire point. `build-all.sh` sets the per-MC `"java"` constraint in fabric.mod.json based on what each MC version itself needs: `>=17` for MC 1.20-1.20.4, `>=21` for MC 1.20.5-1.21.x, **`>=25` for MC 26.x** (MC 26.1's own class files are Java 25 bytecode, so a Java 21 JVM can't load them). Don't accidentally bump `<release>` higher, or that locks out MC 1.20.x users on Java 17. ASM 9.8 itself runs on Java 8+; it just READS class files up to v69 (Java 25), which has nothing to do with what bytecode WE emit.
- **Intermediary names are dead in 26.1+.** All `class_XXXX`, `method_XXXX`, `field_XXXX` must map to Mojang official names.
- **NeoForge already uses Mojang names** since 1.17. NeoForge mods mainly need metadata patching, not name remapping.
- **Fabric mods use intermediary names**, so they need full intermediary→Mojang remapping for 26.1+.
- **ALL old shims (including pre-1.20.1) must stay.** People still translate 1.16.5, 1.14.4 mods. Shims are NOT separate build targets; they're all part of one build.
- **`Retromod.TARGET_MC_VERSION`** is auto-detected at runtime from the mod loader. NEVER hardcode version strings like `"1.21.11"`. Always use `Retromod.TARGET_MC_VERSION`.

## Architecture

```
src/main/java/com/retromod/
├── core/           Core runtime: Retromod, RetromodTransformer, version detectors, mod transformers
├── cli/            CLI tool: RetromodCli (analyze, batch, aot, shims, etc.)
├── aot/            AOT compiler: AotCompiler, HybridCompiler, FullAotCompiler
├── shim/           Version shims by loader (fabric/, neoforge/, forge/, api/)
│   └── ShimRegistry.java   BFS-based shim chain finder with version aliases
├── mapping/        IntermediaryToMojangMapper, MappingComposer (26.1 core feature)
├── mixin/          Mixin compatibility: MixinCompatibilityTransformer, MixinTargetRedirector
├── polyfill/       Removed API reimplementations (72+ polyfills across 10 providers)
├── embedder/       API embedding into mod JARs, ModVersionInfo record
├── resources/      Resource/data pack transforms
├── gui/            In-game GUI (title screen button, file picker, restart popup)
├── agent/          Java Agent mode (premain/agentmain)
├── legacy/         Legacy version support utilities
├── compat/         Compatibility layer
├── archive/        Archive handling
├── util/           Utilities
└── virtual/        Virtual filesystem/classes
```

## Build Commands

```bash
# Quick build (skip tests)
mvn package -q -DskipTests -Dexec.skip=true

# Full build with tests
mvn package -Dexec.skip=true

# Run tests only
mvn test -Dexec.skip=true

# Lite build (1.20+ only, smaller JAR, no legacy/third-party polyfills)
mvn package -P lite -DskipTests -Dexec.skip=true

# Run CLI command (deps not bundled in JAR, must use mvn exec)
mvn exec:java -Dexec.mainClass="com.retromod.cli.RetromodCli" -Dexec.args="<command>" -q
```

**Important:** Always pass `-Dexec.skip=true` during build to prevent Maven from running the CLI entrypoint.

Output JAR: `target/retromod-<version>.jar` (current: `retromod-1.3.0-snapshot.3.jar`)

## Release integrity (self-hash)

Official builds embed a SHA-256 of Retromod's own classes in `SignatureVerifier.EXPECTED_SELF_HASH`. At startup the verifier re-hashes the running jar's `com/retromod/**` classes (excluding the relocated `com/retromod/shaded/**` deps and the verifier class itself) and reports `VERIFIED` on a match, otherwise it fires a fork notice. It's an integrity / modification check, **not** cryptographic anti-tamper: there's no secret key, so a determined attacker can recompute it; for real verification users compare the jar's SHA-256 against the value published on the releases page.

**Embed the hash as the LAST release step** (any source change shifts it):
```bash
mvn clean package -Dexec.skip=true                          # build the final jars
python3 scripts/compute-self-hash.py target/retromod-1.3.0-snapshot.3-all.jar
# embed the 64-hex result into SignatureVerifier.EXPECTED_SELF_HASH PROGRAMMATICALLY
# (sed/python - never hand-typed), rebuild, then re-run the compute script and
# compare against the embedded value (closed-loop verify)
```
Because the hash covers only Retromod's own code (not the relocated deps), **one value matches every per-loader dist jar** from `build-all.sh` (it strips bundled deps, not own classes, verified). In dev, leave `EXPECTED_SELF_HASH=""`: the verifier then reports `UNKNOWN` and logs the computed hash so you can grab it. No keystore, no signing.

## Deploy to Minecraft

```bash
cp target/retromod-1.3.0-snapshot.3.jar ~/Library/Application\ Support/minecraft/mods/retromod-1.3.0-snapshot.3+26.1.jar
```

Game directory (macOS): `~/Library/Application Support/minecraft/`

## Key Files

| File | Purpose |
|------|---------|
| `core/Retromod.java` | Main Fabric ModInitializer, `TARGET_MC_VERSION` auto-detection |
| `core/RetromodPreLaunch.java` | Fabric pre-launch (runs BEFORE mod scan, transforms mods) |
| `core/RetromodNeoForge.java` | NeoForge entry point (transforms at constructor time) |
| `core/RetromodForge.java` | Forge entry point |
| `core/RetromodTransformer.java` | ASM bytecode transformer (class/method/field redirects) |
| `core/FabricModTransformer.java` | Patches fabric.mod.json version constraints |
| `core/ForgeModTransformer.java` | Patches mods.toml/neoforge.mods.toml version constraints |
| `core/ModVersionDetector.java` | Reads mod MC version from loader-specific metadata |
| `mapping/IntermediaryToMojangMapper.java` | ~230 intermediary→Mojang name mappings |
| `mapping/MappingComposer.java` | Generates mapping files from TinyV2 + ProGuard sources |
| `shim/ShimRegistry.java` | BFS chain finder with version aliases |
| `cli/RetromodCli.java` | CLI tool (`TARGET_MC_VERSION = "26.1"`) |
| `embedder/ModVersionInfo.java` | Record with `needsTransformation()` version comparison |
| `aot/AotCompiler.java` | AOT compilation with metadata patching for 26.1+ |

## How Mod Transformation Works

1. **Fabric:** `RetromodPreLaunch` runs before Fabric scans `mods/`. Transforms mods from `retromod-input/`, patches `fabric.mod.json`, moves to `mods/`. User restarts. Old mods CANNOT go directly in `mods/`; Fabric rejects them.

2. **NeoForge/Forge:** `RetromodNeoForge`/`RetromodForge` constructor transforms from `retromod-input/` AND in-place in `mods/`. Patches `mods.toml`. Backs up originals to `retromod-backups/`.

3. **CLI batch:** `RetromodCli.batchCommand()` processes all JARs in a folder. For 26.1+ targets, ALL mods get metadata patching via `patchModMetadata()` post-processing step, even if bytecode doesn't need transformation.

## Version Constraint Patching

**This is critical for 26.1.** Old mods have version ranges like `[1.21,1.21.1)` which reject MC 26.1.

- **Fabric:** Replace `"minecraft"` with exact target version, relax fabricloader/fabric-api to `"*"`
- **NeoForge/Forge:** Widen minecraft `versionRange` to `[<lower>,)`, make non-core deps `type="optional"`, handle both bracket ranges and bare versions
- The `ForgeModTransformer.updateMinecraftVersionRange()` method processes TOML line-by-line tracking `[[dependencies.modid]]` blocks

## ServiceLoader Registration

Shims and polyfills are discovered via ServiceLoader:
- `META-INF/services/com.retromod.core.VersionShim` (~120 entries)
- `META-INF/services/com.retromod.polyfill.PolyfillProvider` (~35 entries)

When adding a new shim or polyfill, ALWAYS register it in the corresponding services file.

## Testing

- **Framework:** JUnit 5 (Jupiter)
- **Test file:** `src/test/java/com/retromod/RetromodTest.java`
- **Run:** `mvn test -Dexec.skip=true`

### Per-issue regression process (REQUIRED when fixing a reported issue)

When you fix a user-reported issue, add a regression case for it to the **Retromod test mod** so the bug can't silently come back, then verify in-game:

1. **Add a test case to the test mod for the affected loader.** The test-mod projects all live under `test-mods/`: `test-mods/retromod-test-mod/` (Fabric), `test-mods/retromod-test-mod-forge/` (Forge), and `test-mods/retromod-test-mod-neoforge/` (NeoForge). If the bug is loader-specific, add it only to that loader's project. **If the bug can occur on multiple loaders, add the case to ALL of them.** Test cases follow the harness shape in `test-mods/retromod-test-mod/src/main/java/com/retromod/testmod/tests/`: implement `Test` (a tiny `description()` + `run()` returning `TestResult`); the runner reports pass/fail per test in the launch log. Use the `TestNN<Name>` / load-to-verify pattern of `Test05SuperKeyPressed` for transform/verify regressions.
2. **Then test Retromod end-to-end with BOTH** the test mod (the new case must pass) **and the actual mod that wasn't working** (it must now load/run). Deploy the proper per-loader dist jar from `build-all.sh` (NOT the raw `-all.jar`, which bundles ASM and hits a `LinkageError` on Fabric; build-all strips it). **A passing test-mod SUMMARY is NOT a passing launch:** the summary prints at mod-init, and the game can still crash a second later in a later entrypoint/boot phase (bitten on 26.2-rc-1: 214/214 printed, then the client entrypoint died on absent Fabric API; killing the game right after the summary masked the crash across two runs). Verify the game *outlives* init: wait for window/title-screen log lines, then `ls -t crash-reports/` and confirm no new file from this run.
3. **Still add a JUnit unit test** for the fix at the transform level (it's the authoritative, host-independent guarantee). Some issues are host-version- or mappings-specific (e.g. a pre-26.1-only model-bridge bug) and can't be faithfully reproduced by the modern-MC test mod alone. For those, the JUnit test plus launching the failing mod is the real coverage, and the test-mod case is a smoke check.

## CI/CD

- **File:** `.github/workflows/ci.yml`
- **Java:** 25
- **Features:** Break-glass bypass (push within 5 min of revert skips tests), auto-revert failed pushes, auto-close failed PRs, CI-passed labels
- **Important:** The linter/CI may revert changes it doesn't like. If that happens, check what was reverted and fix accordingly.

## Common Pitfalls

1. **Don't hardcode `"1.21.11"` anywhere.** Use `Retromod.TARGET_MC_VERSION`. The linter has reverted this multiple times.

2. **Don't delete old shims.** Every shim from 1.12.2 onwards must stay. People translate ancient mods.

3. **TOML parsing is fragile.** The simple TOML parser can't handle `[[dependencies.modid]]` array-of-tables properly (entries overwrite each other). Use `extractMcVersionFromToml()` regex approach instead.

4. **AOT cache invalidation is automatic since 1.2.0-snapshot.7.** Every AOT cache directory (`config/retromod/aot-cache/`, `retromod-cache/full-aot/`) carries a `.cache-stamp` (Retromod version + self-hash of Retromod's own classes, `AotCacheStamp`); on startup a mismatched or missing stamp wipes the directory. So packaged builds never serve a previous build's transforms. Residual dev caveat: on an unpackaged classpath (`mvn exec`/IDE) the self-hash is unresolvable and the stamp degrades to version-only, so same-version dev iterations still need a manual `rm -rf config/retromod/aot-cache/`.

5. **`needsTransformation()` returns false for null targetMcVersion.** If `ModVersionDetector` can't read the version, the mod gets skipped. For 26.1+, the batch command has a separate post-processing step to patch metadata even for "compatible" mods.

6. **Fabric is strictest.** Fabric checks mod versions BEFORE Retromod runs. That's why `retromod-input/` exists: mods get transformed there first, then moved to `mods/`.

7. **The JAR doesn't bundle dependencies.** You can't run `java -jar retromod.jar` directly for CLI. Use `mvn exec:java` instead.

8. **Loader entry points must not reference another loader's classes.** `RetromodNeoForge`/`RetromodForge` load on NeoForge/Forge; if they touch a Fabric-only class (e.g. `RetromodPreLaunch implements net.fabricmc...PreLaunchEntrypoint`) the JVM drags that interface in and crashes at load with `NoClassDefFoundError`, *even with no mods* (#40). Same story for `Retromod implements ModInitializer`. Put loader-agnostic shared helpers on `RetromodVersion` (no loader supertype). `LoaderIsolationTest` scans the compiled entry points' constant pools and fails if they reference `net/fabricmc/` etc.

9. **Gate shims by host version.** Register a shim only when `!RetromodVersion.mcVersionExceeds(shim.getTargetVersion(), host)` (i.e. target ≤ host), in all three entry points. The 1.21.11→26.1 shim renames API classes (Fabric `ScreenEvents$BeforeRender`→`BeforeExtract`, NeoForge `IItemHandler`→`ItemHandler`, …); applied on a pre-26.1 host it rewrites mods to 26.1-only names → `NoClassDefFoundError`/`VerifyError` (#21/#31/#32/#35/#38). Unlike the intermediary remap, API names are identical in mod and runtime, so this bites on Fabric too. The intermediary→Mojang remap is separately gated on `RetromodVersion.isUnobfuscatedTarget(host)` (26.1+ only, #21/#29).

10. **NeoForge 1.20.1 mods need the toml RENAMED, not just patched.** NeoForge 1.20.2+ reads `META-INF/neoforge.mods.toml`; a 1.20.1 (Neo)Forge mod ships only `META-INF/mods.toml` and NeoForge SKIPS it at scan time ("is for Minecraft Forge or an older version of NeoForge") *before bytecode runs* (#42, and the real cause of #38, which was wrongly blamed on the shim gate). `ForgeModTransformer.promoteToNeoForgeToml` (gated on `McReflect.isNeoForge()` + target ≥ 1.20.2) renames it, relaxes top-level `loaderVersion` to `[1,)`, and repoints the `forge` loader dependency → `neoforge` (NeoForge has no `forge` mod). Forge hosts keep `mods.toml`.

11. **Versioning: bump published builds, but fix unpublished builds in place.** If someone reports a bug in a published snapshot or release candidate, use a new version so they can tell the fixed build apart. Update `pom.xml`, the `VERSION` in `build-all.sh`, version constants and banners, the README badge, the full `CHANGELOG.md`, the shorter release summary at `docs/changelog.md`, and version references in the docs. Shields.io escapes literal hyphens by doubling them in the badge URL. Patch releases such as `1.0.1` ship directly. Minor and major releases use a snapshot, then a release candidate, before the stable build. Embed the self-hash only after every other release edit because any later source change invalidates it. Leave `EXPECTED_SELF_HASH=""` during development.

12. **Heavy/coremod mods can't be translated.** Create (ships Flywheel, a custom GL renderer plus coremods), Flywheel, Veil (rendering framework), and similar deep-integration/rendering mods are on [Mods That Can't Be Translated](docs/incompatible-mods.md). They fail with coremod/`getLoadingModList`/`VerifyError`/`CancellationException`-teardown symptoms regardless of metadata fixes (#25/#43). Don't chase these as transform bugs. Confirm the mod list against the incompatible list first.

13. **A 1.20.1 *Forge* mod on a *NeoForge* host is a 1.2.0 feature; don't chase it in beta.** NeoForge 1.20.1 was its first release and still shared Forge's API; NeoForge replaced all of it in 1.20.2+. So a 1.20.1 Forge mod uses APIs modern NeoForge threw out: `ForgeRegistries`/`IForgeRegistry`, `DeferredRegister.create(IForgeRegistry,…)`, `FMLJavaModLoadingContext.get().getModEventBus()`, `net.minecraftforge.*` packages. Translating it onto NeoForge 26.1.x is the *entire* Forge→NeoForge migration (registries → `BuiltInRegistries`/`Registries` `ResourceKey`s with new `create` signatures; mod-event-bus idiom; events; data gen), i.e. Sinytra-Connector-scale, not a redirect table. beta.9's toml promotion (#11/#42) only gets such a mod *scanned*; it then crashes at construct time (e.g. `NoClassDefFoundError: …/FMLJavaModLoadingContext`, then `NoSuchFieldError: NeoForgeRegistries … BLOCKS`). **Decision (Twigs, this session): defer to Retromod 1.2.0; documented as a known limitation; Forge mods go on a Forge host for now.** Known-good 1.2.0 building block, verified against NeoForge 26.1.2's `loader-11.0.13.jar`: `FMLJavaModLoadingContext.get().getModEventBus()` ↦ a bridge whose `getModEventBus()` delegates to `ModLoadingContext.get().getActiveContainer().getEventBus()` (delegation verified against `loader-11.0.13.jar`). **Embedding mechanism (snapshot.2, `SyntheticEmbedder`):** do NOT embed at the original class name. The deleted classes' packages are still loader/neoforge-owned (verified on 26.2: `net/neoforged/neoforge/common/` has 63 classes, `net/neoforged/fml/javafmlmod/` holds `FMLModContainer` etc.), so a synthetic embedded at its original name into a mod's JPMS module **split-packages with the loader module** (a hard crash), and two mods both embedding it split-package with each other. `SyntheticEmbedder.embed(modDir, modKey, transformer)` instead puts each referenced synthetic under `com/retromod/embedded/<mod-key>/` (a Retromod package, unique per mod via the jar name) and rewrites that mod's references there: split-package-safe by construction, gated to mods that actually reference it (reference scan via the ASM remapper). Register a synthetic with `RetromodTransformer.registerSyntheticClass`; the embedder is wired into `ForgeModTransformer` (runtime) + transform-tested (`SyntheticEmbedderTest`). **B2/B4 synthetics built (snapshot.2):** `ForgeNeoForgeSynthetics` generates both. **B2 `DeferredSpawnEggItem`** (extends `SpawnEggItem`; old `(Supplier,int,int,Item.Properties)` ctor sets the type via 26.2's `Item.Properties.spawnEgg(EntityType)` in a try/catch, then `super(props)`) and **B4 `FMLJavaModLoadingContext`** (`get().getModEventBus()` ↦ the `ModLoadingContext.get().getActiveContainer().getEventBus()` delegation). `register(t)` (runtime, gated on the original being **absent** on the host, so don't shadow a class that still exists) is called from `RetromodNeoForge`; `registerAll(t)` (offline, unconditional: no host MC on the CLI classpath, so the caller version/loader-gates) is called from `RetromodCli.registerAuxiliaryRedirects` + the batch post-branch step. Offline embed via `SyntheticEmbedder.embedIntoJar(jarPath, key, t)` (jar-based, `Zip*Stream` so the manifest survives), called after `transformJar` in `transform`/`batch` **and** in the batch post-`patchModMetadata` step (so a "compatible"-by-version mod that still references a deleted class is still covered: e.g. Luminous: Nether targets 1.21.1, takes the metadata-only PATCHED branch). Loader-gate is **NeoForge OR Forge** (a cross-loader mod shipping both `mods.toml` + `neoforge.mods.toml` is detected as "forge" yet runs on NeoForge; reference-gating no-ops a pure-Forge mod). Transform-verified end-to-end on Luminous: Nether → 26.2 (`ForgeNeoForgeSyntheticsTest`, `SyntheticEmbedderTest.embedIntoJar...`). **Remaining 1.2.0 work:** in-game verification of B2/B4 (a real `DeferredSpawnEggItem`/`FMLJavaModLoadingContext` mod loading + the egg/event-bus actually working on 26.2), and the standalone `aot` command's embed wiring (the `batch --aot` path is already covered by the post-branch embed).

14. **Retromod's startup probes must never *initialize* a Minecraft class.** `EnvironmentDetector` (client/server/headless detection, called from the loader entry-point constructors) probed for MC classes with the single-arg `Class.forName(name)`, which **initializes** the class. During mod construction that forced `net.minecraft.server.MinecraftServer.<clinit>` to run far too early; with a mod that mixins those classes (Legacy4J mixins `MinecraftServer` + `LevelSettings`) it cascaded into `wily.legacy.client.PackAlbum.<clinit>` reading `Minecraft.getInstance().gameDirectory` before the client singleton existed → NPE. Net effect: **Retromod's mere presence crashed an otherwise-fine, NATIVE (un-transformed) mod** (#46, Legacy4J on NeoForge 1.21.11; the mod declares `versionRange "[1.21.11]"` so Retromod correctly *skips* transforming it, yet still broke it). Fix: probe with `Class.forName(name, false, loader)` (`initialize=false`) via `EnvironmentDetector.classExists`, so it observes, never initializes. Watch the **merged-jar trap**: the MC runtime jar contains server classes (even `net.minecraft.server.dedicated.DedicatedServer`) on a *client* too, so dedicated-server detection must key on the **absence of a client class**, not the presence of a server one. Diagnosed with a 3-mod minimal repro (Retromod + Legacy4J + Factory API): crashed *with* Retromod, launched *without* it.

15. **Mixin `@Inject` handler signatures aren't re-typed when an MC method's params change, and the crash misleads.** The mixin pass rewrites the `@Mixin`/`@Inject` *target* (so it resolves to the right MC method), but NOT the handler method's captured parameter types or body. So when MC changes a method's *signature* (not a rename), Mixin rejects the injection: `InvalidInjectionException: Invalid descriptor … Expected (NewType) but found (OldType)`. Canonical case: `Entity.addAdditionalSaveData`/`readAdditionalSaveData` `CompoundTag` → `ValueOutput`/`ValueInput` (1.21.5 ValueIO/codec refactor), where a 1.21.1 mod's save-data `@Inject` dies on 1.21.11 (#48, Darker Depths). Can't be a rename fix: `ValueOutput` has a different API than `CompoundTag`, so the handler body needs rewriting (future work). **The trap:** the `InvalidInjectionException` is only a WARN/construction error early in the log; the *visible* crash is a downstream `NullPointerException` (e.g. `ModelManager.reload` → `PreparableReloadListener$SharedState.get`) because NeoForge enters a **"broken mod state"** and skips client registration events. Always scroll up to the first `Mixin apply … failed` + `Failed to wait for future Mod Construction, N errors found`. Do NOT misattribute to a third-party API: **GeckoLib loaded fine in #48** (verified in our own log; the first guess was GeckoLib, the log said otherwise). Soft-fail via the mixin blocklist (strips the handler → that feature inert); proper re-signaturing is deferred. **The blocklist strip was Fabric-only until beta.10:** `transformMixinClass` is called from `FabricModTransformer`, but `ForgeModTransformer` never invoked it; beta.10 added `MixinCompatibilityTransformer.stripBlocklistedHandlers` (blocklist-only, no target remap) wired into `ForgeModTransformer.transformClasses`, so NeoForge/Forge mixins get stripped too. **Caveat (verified, #48):** stripping only soft-fails a *self-contained* handler. Darker Depths' `PlayerMixin` strips clean of the injection error but still won't construct: its death-anchor feature spans the `@Inject` handlers **plus** a `@Unique` field, the `DeathAnchorLocation` interface + accessors, and orphaned `lambda$<handler>$N` helpers; and NeoForge doesn't log the residual construction errors (they're lost when the "broken mod state" cascades to the `ModelManager` NPE), so they can't be chased from the log. Such interdependent-mixin mods aren't soft-failable by stripping alone.

16. **A NeoForge/Forge mod with no `module-info` + spaces/odd chars in its filename can't read core MC.** Mods that ship no `module-info`/`Automatic-Module-Name` (MCreator output, many small mods) get their JPMS module name *derived from the jar filename*; spaces or odd chars break the derived module's reads, so the mod dies in its own `<clinit>` with `ClassNotFoundException: net.minecraft.resources.ResourceLocation` (or another core class). It looks like a missing-class bug, but it's module resolution. `ForgeModTransformer.transformMod` now sanitizes the transformed output filename (`[^A-Za-z0-9._-]` → `_`) so the derived module name is always valid. Proven by a controlled test (#47, Luminous Nether): the jar with spaces in its name failed; the exact same mod renamed without spaces loaded. NeoForge/Forge only, since Fabric's Knot loader has no JPMS modules. Don't conflate with #48: that's the mixin-signature issue on Darker Depths, a *different* mod filed alongside #47.

17. **The old Fabric shims (≈1.14-1.20) are MCP/Yarn-named, so they no-op on real (intermediary) Fabric mods.** Distributed Fabric mods ship **intermediary** names (`class_1297`, `method_5773`); the pre-26.1 Fabric shims register redirects keyed on **Mojang class paths + MCP method names** (`net/minecraft/world/entity/Entity.onUpdate`) and **Yarn** class names (`net/minecraft/tag/BlockTags`), none of which appear in a real distributed mod (verified: `onUpdate`/`attackEntityFrom`/… = 0 hits in the mapping data; the real Mojang names are `tick`, `render`, …). On a **26.1 host** this is harmless: the intermediary→Mojang harvest does the real translation and these shims are dead weight. On a **pre-26.1 Fabric host** the intermediary→Mojang remap is (correctly, #21/#29) gated OFF, so nothing bridges genuinely changed APIs: old Fabric mods work where intermediary names are stable (most simple content mods do), and break on **redesigned/removed** APIs. The classic case is a ≤1.16 custom entity model/renderer (the model system was rebuilt in 1.17). **Forge/NeoForge are unaffected**: NeoForge mods are Mojang-named (shims match directly), and Forge's SRG→Mojang remap runs unconditionally onto a Mojang-named runtime. Real fix = re-author the Fabric shims in the correct namespace (or a pre-26.1 intermediary↔Mojang bridge), **scheduled for 1.1.0** (#55). See [Mods That Can't Be Translated](docs/incompatible-mods.md).

18. **26.1 parses datapack JSON with STRICT gson: lenient JSON (comments / trailing commas) now fails, and ONE fatal mod cascades.** Old worldgen JSON has shipped `//` and `/* */` comments and trailing commas for years (Minecraft used to parse leniently; mod authors literally wrote *"Yes, worldgen json files can have comments"* in their `template_pool`s). On 26.1 each such file throws `MalformedJsonException: Use JsonReader.setStrictness(Strictness.LENIENT)` and the registry entry stays **unbound**. The trap: an unbound `worldgen/template_pool`/`processor_list` is **FATAL** (`FatalStartupException`, "Couldn't find Minecraft server thread") and aborts the **shared** worldgen `RegistryDataLoader` pass, so *co-loaded* structure mods then surface as "Unknown registry key in worldgen/feature" / "Unbound values" for **their** custom types even though their registration ran fine. So a multi-mod "custom worldgen types don't register" report is usually really *one* mod's strict-JSON crash taking the others down with it (verified: Philips Ruins fatal-crashed; YUNG's Extras looked broken beside it but loaded perfectly *alone*, and both load together once PR's comments are stripped). Fix lives in `ModDataMigrator.normalizeLenientJson` (string-aware strip of comments + trailing commas, so a `//` inside a URL value survives), applied to ALL datapack JSON. Diagnose with a **headless dedicated server** (`./run.sh nogui`): the client swallows these; the server prints the per-element `MalformedJsonException` and the fatal cascade. Also in this class: `minecraft:potion` (the thrown-potion **entity**) was split into `minecraft:splash_potion` + `minecraft:lingering_potion` (vanilla `ThrownPotionSplitFix`), so an `entity_type` tag listing `minecraft:potion` fails to load (the potion **item** is unchanged, so scope the rename to `tags/entity_type/` only).

## Mixin discovery (finding mixins to translate)

Historically we found broken mixins *reactively* (a mod crashes, we scroll the log per pitfall #15). The discovery tooling flips this to *predictive*: enumerate every mixin target across a mod corpus, rank them by how often they appear, and cross them against a real MC version diff so we know which mixins will break on a jump BEFORE anyone crashes. Four tools work together (full usage in [scripts/mixin-discovery.md](scripts/mixin-discovery.md)):

- **`mixin-scan` CLI command** (Java, ASM). Scans mod jars and emits an injector-level JSON table of every `@Mixin`: target classes, each injector handler (Inject/Redirect/ModifyArg/Accessor/MixinExtras WrapOperation/etc.), its `method`/`@At` selectors, and whether the mixin is actually applied via a discovered config. This is the source of truth the scripts consume. Deps are not bundled, so run it through `mvn exec:java` (see the usage guide).
- **`scripts/mixin-rank.py`** (frequency worklist). Clusters scan records by (targetClass, targetMethod) and sorts by how many mods hit each target. Translate the most-referenced targets first for the widest compatibility win per unit of effort.
- **`scripts/mixin-crossjoin.py`** (predicted breaks). Joins a scan against a `scripts/harvest-mc-diff.py` output (moves/renames/removed classes) for a specific version jump and produces a PREDICTED-BREAK worklist (target class removed, moved, or renamed) ranked by corpus frequency, with a suggested mechanism (class-redirect / resignature / blocklist). No crash needed.
- **`scripts/mixin-refmap-harvest.py`** (pure-JSON cross-check). Inventories every `*refmap.json` inside the jars without touching bytecode or needing a JDK, so it works as an independent cross-check where the Java scanner cannot run.

Caveat: these surface CANDIDATES, not confirmations. A predicted break is a hypothesis about a target that changed; correctness still requires the in-game or headless verify (per the pitfalls above, the visible crash often masks the real mixin failure).

## Dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| ASM | 9.8 | Bytecode manipulation (9.8 required for Java 25) |
| Gson | 2.10.1 | JSON parsing |
| SLF4J | 2.0.9 | Logging |
| JUnit 5 | 5.10.1 | Testing |
| Mixin | 0.8.5 | Mixin API (provided) |
| Fabric Loader | 0.16.10 | Fabric API (provided) |

## Skills

Development skills are in `.claude/skills/`:

| Skill | Use when... |
|-------|-------------|
| `add-version-shim` | Adding support for a new MC version transition |
| `add-polyfill` | Reimplementing a removed API using modern equivalents |
| `mapping-work` | Working with intermediary/Mojang/SRG name mappings |
| `mod-loader-compat` | Fixing Fabric/NeoForge/Forge loading issues |
| `test-mod-transform` | Testing mod transforms end-to-end |
| `debug-crash` | Diagnosing crashes from transformed mods |
| `build-and-deploy` | Building and deploying to Minecraft |
| `modrinth-api` | Downloading test mods from Modrinth |


## Other

If you are Claude reading this, any updates you do to this that is not about Claude, add to Agents.md (codex)
