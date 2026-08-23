# Retromod - Claude Development Guide

Retromod transforms older Minecraft mod bytecode so old mods work on newer MC versions. It supports Fabric, NeoForge, and Forge with 120 registered version-shim providers and 36 registered polyfill providers. Counts drift; count non-comment, nonblank lines in `src/main/resources/META-INF/services/com.retromod.core.VersionShim` and `src/main/resources/META-INF/services/com.retromod.polyfill.PolyfillProvider`.

**Repository:** https://github.com/Bownlux/Retromod.git

## Writing style

**Do not use em-dashes (the long dash, Unicode U+2014) anywhere:** not in chat replies, not in code comments, docs, CHANGELOG/ROADMAP entries, or commit messages. Also avoid en-dashes (U+2013). They are annoying to copy out of responses. Use a comma, parentheses, a colon, or two separate sentences instead. (Ordinary hyphens `-`, e.g. in version ranges like `1.20-26.2`, are fine.)

Write like the published docs at https://bownlux.github.io/Retromod/. They are the house style, and
they are built from `docs/`. Read `docs/faq.md`, `docs/troubleshooting.md`, or `docs/index.md` before
writing user-facing text and match what is there. This applies to chat replies too.

This governs prose aimed at users: `docs/`, `README.md`, both changelogs, release notes, issue and PR
text, and chat. It does not govern this guide, which stays a dense internal reference. American
spelling throughout.

### The house style

- **Answer first.** Open with the answer, then qualify it. "No. Normal transformation, caching, and
  verification are local." "Sometimes. Retromod bridges selected common 1.20.1 Forge metadata..."
  Never open with background, a restatement of the question, or a summary label.
- **Short declarative sentences.** Most run 8 to 20 words. One idea each. Break a long sentence in two
  rather than joining it with a semicolon or a parenthetical.
- **State the limit in the same breath as the capability.** This is the most recognizable habit in
  these docs. "It can repair many renamed or moved APIs, but it cannot recreate an entire rendering
  engine or loader subsystem." "Retromod relaxes stale version ranges when it knows the dependency can
  be bridged. It does not invent a missing library." Never let a claim stand without its boundary.
- **Name what it refuses, concretely.** "It refuses ambiguous overloads, constructors, partial
  captures, reordered or removed parameters, return-type changes, semantic local captures, unsafe
  annotations, and `remap = false` scopes." A concrete refusal list beats "some cases are unsupported".
- **Third person for Retromod, second person for the reader.** "Retromod translates...", "It updates
  bytecode...", "Use OpenGL", "Back up important worlds". Never "we", never "I".
- **Prefer the exact path, key, or flag over a description of it.** `retromod-input/processed/`,
  `config/retromod/aot-cache/`, `--mc-jar <target.jar>`, `check_for_native_versions`. Backtick every
  one of them, including error class names like `UnsupportedClassVersionError`.
- **Bold is for UI paths only**, as in **Video Settings > Graphics API > OpenGL**. Do not bold concepts,
  warnings, or lead-in labels.
- **Lists only for real enumerations**: version floors, symptom-to-cause pairs, what to attach to a bug
  report. Explanation stays in prose.
- **Title Case for section headings** ("Start Here", "Cache Looks Stale"). FAQ headings are the reader's
  question in sentence case ("Does it work on servers?").
- Keep sections short. One to three paragraphs, then the next heading.

Do not use: marketing adjectives, "simply", "just", "easily", "seamlessly", exclamation marks, all-caps
emphasis, hedging filler, apologies, or any narration of how a fix was found. State the limit flatly
instead of softening it.

### Changelog and release notes

Public entries are short and declarative. One or two sentences per bullet.

- Start each bullet with a present-tense verb describing what the build does now: "Repairs...", "Updates...", "Keeps...", "Prevents...", "Clarifies...". Do not open with a bold summary label followed by a paragraph.
- One clause of mechanism is enough. The reader wants to know whether their mod works, not how the transform is wired.
- Leave issue numbers, mod names used only as evidence, and test class names out of the bullet. They belong in the commit, the issue, or `CHANGELOG.md` at most.
- Put limitations in plain sentences after the bullets, stating what still does not work and what a real fix would need. Do not label them or apologize for them.
- Do not append test coverage to public entries. The suite is the guarantee; saying so adds nothing for a user.

`CHANGELOG.md` may carry a little more detail than `docs/changelog.md`, but both follow this shape. `docs/changelog.md` is the user-facing summary and should stay the shortest.

### Code, comments, errors, and tests

- Comments should explain why a choice is necessary, especially when the obvious implementation would be wrong. Do not restate the code or narrate the investigation.
- Use clear names and small helpers so the code explains its own mechanics. Avoid dense nested conditionals, clever one-liners, and temporary abbreviations that make readers decode the implementation.
- Make errors actionable. Say what failed, which input was involved, and what the user can do next. Do not blame the user or expose irrelevant internals.
- Tests should read as examples of behavior. Keep setup helpers reusable, test names direct, and assertion messages useful when a failure occurs.

### Contained Editing

- Trace the request to the first useful failure and owning layer before editing. Do not change adjacent code on suspicion.
- Make the smallest complete change. Cover sibling runtime, CLI, AOT, nested-jar, and loader paths only when the same invariant applies.
- Keep one purpose in the diff. Avoid unrelated cleanup, renames, reformatting, dependency changes, documentation rewrites, and speculative features.
- Reuse existing extension points. Add a shared helper only when it prevents repeated policy or cross-path drift.
- Preserve public APIs, version gates, service registrations, old shims, unrelated behavior, and user changes. Avoid permissive fallbacks, broad exception handling, and guessed compatibility.
- Add the narrow regression test, then test the affected integration path. Reported issues still require the full per-issue process below.
- Review the final diff line by line. Remove scaffolding and incidental churn.

Contained does not mean partial. If a rule belongs in one shared layer, fix it there instead of duplicating small patches.

## Security Embargo

- Keep suspected and confirmed vulnerability details private until disclosure is approved.
- Do not announce a security fix or publish vulnerability details until the affected stable version with the fix is released.
- For the current 1.3.0 pre-release line, keep audit findings and security-fix details private until stable 1.3.0 is released.
- If a vulnerability is fixed directly on a stable release line, defer the public technical details until the first snapshot of the next version.
- Before the embargo lifts, keep private maintainer notes with the impact, affected versions, fix, tests, and disclosure timeline. Do not put exploit instructions or identifying audit details in public changelogs, issues, pull requests, or release notes.
- Coordinate disclosure timing with the maintainer and, when applicable, the reporter. Public text after the embargo should explain user impact and the fixed versions without unnecessary exploit detail.

## Critical Context

- **Target MC version:** 26.1 (Mojang removed ALL code obfuscation); 26.2 supported since 1.1.0-snapshot.4 (shims/aliases/Fabric build target in place, verified on 26.2-rc-1)
- **Java:** Built WITH Java 25 (we need the modern compiler to use ASM 9.10.1 features that read MC 26.1's class file format), but bytecode targets `--release 17` so the SAME JAR runs on Java 17, 21, and 25. Broad runtime compat is the entire point. `build-all.sh` sets the per-MC `"java"` constraint in fabric.mod.json based on what each MC version itself needs: `>=17` for MC 1.20-1.20.4, `>=21` for MC 1.20.5-1.21.x, **`>=25` for MC 26.x** (MC 26.1's own class files are Java 25 bytecode, so a Java 21 JVM can't load them). Don't accidentally bump `<release>` higher, or that locks out MC 1.20.x users on Java 17. ASM itself runs on Java 8+; it just READS class files up to v69 (Java 25), which has nothing to do with what bytecode WE emit.
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
├── polyfill/       Removed API reimplementations (36 registered providers)
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

# Run the CLI from a source checkout
mvn exec:java -Dexec.mainClass="com.retromod.cli.RetromodCli" -Dexec.args="<command>" -q

# Run the executable release CLI (dependencies bundled)
java -jar dist/CLI/retromod-1.3.0-snapshot.9-cli.jar <command>
```

**Important:** Always pass `-Dexec.skip=true` during build to prevent Maven from running the CLI entrypoint.

Development output: `target/retromod-<version>.jar` and `target/retromod-<version>-all.jar`. Release output: 68 loader-specific jars under `dist/{Fabric,Forge,NeoForge}/<mc>/`, plus `dist/CLI/retromod-1.3.0-snapshot.9-cli.jar`.

## Release integrity (self-hash)

Official builds embed a SHA-256 of the executable release surface in `SignatureVerifier.EXPECTED_SELF_HASH`. It covers every class except loader-provided `org/objectweb/asm/`, loader-variant `javax/annotation/` stubs, and the verifier class itself. It also covers every `META-INF/services/` descriptor and Retromod's bundled transformation data. At startup the verifier reports `VERIFIED` on a match, otherwise it fires a fork notice. It is an integrity / modification check, **not** cryptographic anti-tamper: there is no secret key, so a determined attacker can recompute it. For real verification, users compare the whole jar's SHA-256 with the release manifest.

**Embed the hash as the LAST release step** (any covered code, provider, or transformation-data change shifts it):
```bash
mvn clean package -Dexec.skip=true                          # build the final jars
python3 scripts/compute-self-hash.py target/retromod-1.3.0-snapshot.9-all.jar
# embed the 64-hex result into SignatureVerifier.EXPECTED_SELF_HASH PROGRAMMATICALLY
# (sed/python - never hand-typed), rebuild, then re-run the compute script and
# compare against the embedded value (closed-loop verify)
```
The excluded loader-variant classes are the only covered-surface differences between distributions, so **one value matches every per-loader dist jar and the standalone CLI** from `build-all.sh`. In dev, leave `EXPECTED_SELF_HASH=""`: the verifier then reports `UNKNOWN` and logs the computed hash so you can grab it. No keystore, no signing.

After embedding and rebuilding, run `bash build-all.sh --skip-build --require-self-hash`. A complete release has 23 Fabric, 23 Forge, 22 NeoForge, and 1 CLI artifact. That is 68 loader jars plus the CLI, or 69 artifacts total. Verify all rows in `dist/SHA256SUMS.txt` before publishing.

**`build-all.sh` removes generated `retromod-*.jar` files only after its build and integrity preflight passes.** It preserves other files under `dist/`, including `dist/MODRINTH_CHANGELOG.md`. Clear `dist/` before a version-bumped release build, then recreate that hand-written release note with the final self-hash. Confirm the tree with `PYTHONPATH=. python3 -c "from scripts.release_artifacts import validate_release_artifacts as v; v('<version>')"` and check the self-hash is one value across a Fabric, Forge, NeoForge, and the CLI jar. Modrinth and CurseForge receive only the 68 loader jars; the CLI and checksum manifest ship on GitHub Releases.

## Deploy to Minecraft

```bash
cp dist/Fabric/26.1/retromod-1.3.0-snapshot.9+26.1.jar ~/Library/Application\ Support/minecraft/mods/
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
| `mapping/IntermediaryToMojangMapper.java` | Loads the bundled intermediary-to-Mojang table (11,981 classes, 54,479 fields, and 57,520 methods at snapshot.9) |
| `mapping/MappingComposer.java` | Generates mapping files from TinyV2 + ProGuard sources |
| `shim/ShimRegistry.java` | BFS chain finder with version aliases |
| `cli/RetromodCli.java` | CLI tool (`TARGET_MC_VERSION = "26.1"`) |
| `embedder/ModVersionInfo.java` | Record with `needsTransformation()` version comparison |
| `aot/AotCompiler.java` | AOT compilation with metadata patching for 26.1+ |

## How Mod Transformation Works

1. **Fabric:** `RetromodPreLaunch` runs before Fabric scans `mods/`. Transforms mods from `retromod-input/`, patches `fabric.mod.json`, moves to `mods/`. User restarts. Old mods CANNOT go directly in `mods/`; Fabric rejects them.

2. **NeoForge/Forge:** `RetromodNeoForge`/`RetromodForge` constructor transforms from `retromod-input/` AND in-place in `mods/`. Patches `mods.toml`. Input originals go to `retromod-input/processed/`; in-place originals go to `mods/retromod-backups/`. The health checker may also keep copies in the game-root `retromod-backups/` folder.

3. **CLI batch:** `RetromodCli.batchCommand()` processes all JARs in a folder. For 26.1+ targets, ALL mods get metadata patching via `patchModMetadata()` post-processing step, even if bytecode doesn't need transformation.

## Version Constraint Patching

**This is critical for 26.1.** Old mods have version ranges like `[1.21,1.21.1)` which reject MC 26.1.

- **Fabric:** Replace `"minecraft"` with exact target version, relax fabricloader/fabric-api to `"*"`
- **NeoForge/Forge:** Widen minecraft `versionRange` to `[<lower>,)`, make non-core deps `type="optional"`, handle both bracket ranges and bare versions
- The `ForgeModTransformer.updateMinecraftVersionRange()` method processes TOML line-by-line tracking `[[dependencies.modid]]` blocks

## ServiceLoader Registration

Shims and polyfills are discovered via ServiceLoader:
- `src/main/resources/META-INF/services/com.retromod.core.VersionShim` (120 registered providers at snapshot.9)
- `src/main/resources/META-INF/services/com.retromod.polyfill.PolyfillProvider` (36 registered providers at snapshot.9)

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

4. **AOT cache invalidation is automatic since 1.2.0-snapshot.7.** Every AOT cache directory (`config/retromod/aot-cache/`, `retromod-cache/full-aot/`) carries a `.cache-stamp` (Retromod version + executable-surface self-hash, `AotCacheStamp`); on startup a mismatched or missing stamp wipes the directory. So packaged builds never serve a previous build's transforms. Residual dev caveat: on an unpackaged classpath (`mvn exec`/IDE) the self-hash is unresolvable and the stamp degrades to version-only, so same-version dev iterations still need a manual `rm -rf config/retromod/aot-cache/`.

5. **`needsTransformation()` returns false for null targetMcVersion.** If `ModVersionDetector` can't read the version, the mod gets skipped. For 26.1+, the batch command has a separate post-processing step to patch metadata even for "compatible" mods.

6. **Fabric is strictest.** Fabric checks mod versions BEFORE Retromod runs. That's why `retromod-input/` exists: mods get transformed there first, then moved to `mods/`.

7. **Use the right CLI artifact.** The raw development mod jar does not provide a reliable standalone dependency set. Published releases include `dist/CLI/retromod-<version>-cli.jar`, which is shaded and executable with `java -jar`. Use `mvn exec:java` as the source-checkout fallback.

8. **Loader entry points must not reference another loader's classes.** `RetromodNeoForge`/`RetromodForge` load on NeoForge/Forge; if they touch a Fabric-only class (e.g. `RetromodPreLaunch implements net.fabricmc...PreLaunchEntrypoint`) the JVM drags that interface in and crashes at load with `NoClassDefFoundError`, *even with no mods* (#40). Same story for `Retromod implements ModInitializer`. Put loader-agnostic shared helpers on `RetromodVersion` (no loader supertype). `LoaderIsolationTest` scans the compiled entry points' constant pools and fails if they reference `net/fabricmc/` etc.

9. **Gate shims by host version.** Register a shim only when `!RetromodVersion.mcVersionExceeds(shim.getTargetVersion(), host)` (i.e. target ≤ host), in all three entry points. The 1.21.11→26.1 shim renames API classes (Fabric `ScreenEvents$BeforeRender`→`BeforeExtract`, NeoForge `IItemHandler`→`ItemHandler`, …); applied on a pre-26.1 host it rewrites mods to 26.1-only names → `NoClassDefFoundError`/`VerifyError` (#21/#31/#32/#35/#38). Unlike the intermediary remap, API names are identical in mod and runtime, so this bites on Fabric too. The intermediary→Mojang remap is separately gated on `RetromodVersion.isUnobfuscatedTarget(host)` (26.1+ only, #21/#29).

10. **NeoForge 1.20.1 mods need the toml RENAMED, not just patched.** NeoForge 1.20.2+ reads `META-INF/neoforge.mods.toml`; a 1.20.1 (Neo)Forge mod ships only `META-INF/mods.toml` and NeoForge SKIPS it at scan time ("is for Minecraft Forge or an older version of NeoForge") *before bytecode runs* (#42, and the real cause of #38, which was wrongly blamed on the shim gate). `ForgeModTransformer.promoteToNeoForgeToml` (gated on `McReflect.isNeoForge()` + target ≥ 1.20.2) renames it, relaxes top-level `loaderVersion` to `[1,)`, and repoints the `forge` loader dependency → `neoforge` (NeoForge has no `forge` mod). Forge hosts keep `mods.toml`.

11. **Versioning: bump published builds, but fix unpublished builds in place.** If someone reports a bug in a published snapshot or release candidate, use a new version so they can tell the fixed build apart. Update `pom.xml`, the `VERSION` in `build-all.sh`, version constants and banners, the README badge, the full `CHANGELOG.md`, the shorter release summary at `docs/changelog.md`, and version references in the docs. Shields.io escapes literal hyphens by doubling them in the badge URL. Patch releases such as `1.0.1` ship directly. Minor and major releases use a snapshot, then a release candidate, before the stable build. Embed the self-hash only after every covered code, provider, and transformation-data edit. Leave `EXPECTED_SELF_HASH=""` during development. Do not blanket find-replace the old version string: `scripts/tests/test_release_artifacts.py` needs a version that deliberately does *not* match the pom, and it used to spell that as the next release, so bumping to that release silently made the value correct and killed three of its seven tests. It now uses a `MISMATCHED_VERSION` sentinel and derives its artifact paths from `VERSION`, so only that one constant changes. Run it after any bump with `PYTHONPATH=. python3 scripts/tests/test_release_artifacts.py`; CI does not.

12. **Heavy/coremod mods can't be translated.** Create (ships Flywheel, a custom GL renderer plus coremods), Flywheel, Veil (rendering framework), and similar deep-integration/rendering mods are on [Mods That Can't Be Translated](docs/incompatible-mods.md). They fail with coremod/`getLoadingModList`/`VerifyError`/`CancellationException`-teardown symptoms regardless of metadata fixes (#25/#43). Don't chase these as transform bugs. Confirm the mod list against the incompatible list first.

13. **Forge-to-NeoForge support is partial, not deferred and not complete.** Since 1.2.0, Retromod promotes 1.20.1 Forge metadata and bridges selected registration, event-bus, networking, and removed-class paths. `ForgeNeoForgeSynthetics` embeds only referenced replacements under a unique `com/retromod/embedded/<mod-key>/` package, which avoids loader split packages and cross-mod collisions. Runtime and offline transform paths both wire these synthetics, including standalone `aot`. Do not claim general Forge compatibility on NeoForge: registry lifecycle, packet delivery, data generation, rendering, and other deep Forge internals can still require a real port. Use a matching Forge host when those systems are involved.

14. **Retromod's startup probes must never *initialize* a Minecraft class.** `EnvironmentDetector` (client/server/headless detection, called from the loader entry-point constructors) probed for MC classes with the single-arg `Class.forName(name)`, which **initializes** the class. During mod construction that forced `net.minecraft.server.MinecraftServer.<clinit>` to run far too early; with a mod that mixins those classes (Legacy4J mixins `MinecraftServer` + `LevelSettings`) it cascaded into `wily.legacy.client.PackAlbum.<clinit>` reading `Minecraft.getInstance().gameDirectory` before the client singleton existed → NPE. Net effect: **Retromod's mere presence crashed an otherwise-fine, NATIVE (un-transformed) mod** (#46, Legacy4J on NeoForge 1.21.11; the mod declares `versionRange "[1.21.11]"` so Retromod correctly *skips* transforming it, yet still broke it). Fix: probe with `Class.forName(name, false, loader)` (`initialize=false`) via `EnvironmentDetector.classExists`, so it observes, never initializes. Watch the **merged-jar trap**: the MC runtime jar contains server classes (even `net.minecraft.server.dedicated.DedicatedServer`) on a *client* too, so dedicated-server detection must key on the **absence of a client class**, not the presence of a server one. Diagnosed with a 3-mod minimal repro (Retromod + Legacy4J + Factory API): crashed *with* Retromod, launched *without* it.

15. **Mixin signature repair is exact and conservative.** Snapshot.6 can index the installed Minecraft jar, or an offline `--mc-jar`, and automatically retype a uniquely proven parameter-addition change. The old parameters must stay in order, the return type must stay unchanged, no more than three parameters may be added, and no competing overload may fit. Selected zero-capture injections, exact-prefix captures, invokers, overwrites, and call-mirroring handlers have additional shape checks. Ambiguous overloads, constructors, reordered or removed parameters, changed returns, semantic local captures, unsafe parameter annotations, multiple layouts, frame failures, and every relevant `remap = false` scope are declined. Curated adapters still own semantic migrations such as `CompoundTag` to `ValueInput` or `ValueOutput`; never treat an arbitrary save signature as a safe descriptor edit. The first useful log failure can still be an early `InvalidInjectionException` followed by a misleading NeoForge broken-state crash, so diagnose from the first mixin error.

16. **A NeoForge/Forge mod with no `module-info` + spaces/odd chars in its filename can't read core MC.** Mods that ship no `module-info`/`Automatic-Module-Name` (MCreator output, many small mods) get their JPMS module name *derived from the jar filename*; spaces or odd chars break the derived module's reads, so the mod dies in its own `<clinit>` with `ClassNotFoundException: net.minecraft.resources.ResourceLocation` (or another core class). It looks like a missing-class bug, but it's module resolution. `ForgeModTransformer.transformMod` now sanitizes the transformed output filename (`[^A-Za-z0-9._-]` → `_`) so the derived module name is always valid. Proven by a controlled test (#47, Luminous Nether): the jar with spaces in its name failed; the exact same mod renamed without spaces loaded. NeoForge/Forge only, since Fabric's Knot loader has no JPMS modules. Don't conflate with #48: that's the mixin-signature issue on Darker Depths, a *different* mod filed alongside #47.

17. **Pre-26.1 Fabric requires intermediary-native bridges.** Distributed Fabric mods and pre-26.1 Fabric runtimes both use intermediary names, so the full intermediary-to-Mojang pass must stay off there. Since 1.2.0, targeted bridges cover common old text, entity field, material, identifier constructor, entity model, biome category, interaction result, and entity-type build changes. Real 1.16.5 mods have reached a stable 1.20.1 server through this path. Coverage is still curated, not universal: a redesigned API without an intermediary-native bridge can fail even when an old Mojang-named shim exists. On 26.1 and newer hosts, use the full intermediary-to-Mojang map before Mojang-named repairs. See [Mods That Can't Be Translated](docs/incompatible-mods.md).

18. **26.1 parses datapack JSON with STRICT gson: lenient JSON (comments / trailing commas) now fails, and ONE fatal mod cascades.** Old worldgen JSON has shipped `//` and `/* */` comments and trailing commas for years (Minecraft used to parse leniently; mod authors literally wrote *"Yes, worldgen json files can have comments"* in their `template_pool`s). On 26.1 each such file throws `MalformedJsonException: Use JsonReader.setStrictness(Strictness.LENIENT)` and the registry entry stays **unbound**. The trap: an unbound `worldgen/template_pool`/`processor_list` is **FATAL** (`FatalStartupException`, "Couldn't find Minecraft server thread") and aborts the **shared** worldgen `RegistryDataLoader` pass, so *co-loaded* structure mods then surface as "Unknown registry key in worldgen/feature" / "Unbound values" for **their** custom types even though their registration ran fine. So a multi-mod "custom worldgen types don't register" report is usually really *one* mod's strict-JSON crash taking the others down with it (verified: Philips Ruins fatal-crashed; YUNG's Extras looked broken beside it but loaded perfectly *alone*, and both load together once PR's comments are stripped). Fix lives in `ModDataMigrator.normalizeLenientJson` (string-aware strip of comments + trailing commas, so a `//` inside a URL value survives), applied to ALL datapack JSON. Diagnose with a **headless dedicated server** (`./run.sh nogui`): the client swallows these; the server prints the per-element `MalformedJsonException` and the fatal cascade. Also in this class: `minecraft:potion` (the thrown-potion **entity**) was split into `minecraft:splash_potion` + `minecraft:lingering_potion` (vanilla `ThrownPotionSplitFix`), so an `entity_type` tag listing `minecraft:potion` fails to load (the potion **item** is unchanged, so scope the rename to `tags/entity_type/` only).

19. **A mixin repair is namespace-sensitive, so it MUST run after the remap, and every class-transform path has to invoke it.** This bug class was found three times in one snapshot.4 pass. Two rules. **(a) Order.** A repair that matches a member by its Mojang descriptor (`MixinLegacyMemberBridge`, `MixinShadowFieldDemotion`) sees **intermediary** names if it runs inside `transformMixinClass`, because Fabric mods are still intermediary there: `transformMixinAnnotation` remaps the `@Mixin` *target* but nothing remaps the member *descriptors* until `RetromodTransformer.transformClass`. So those repairs silently matched nothing on the Fabric runtime path while working offline (the CLI happens to call `stripBlocklistedHandlers` *after* `transformClass`). They now live in `MixinCompatibilityTransformer.applyLegacyMemberBridges`, called post-remap from `FabricModTransformer`, `ForgeModTransformer`, and `RetromodCli`. Selector-driven repairs (`MixinHandlerResignature`) are different and correctly stay pre-remap: they key off the *selector*, which `transformMethodAnnotations` remaps in place, and the Mojang param types they insert pass through the later remap untouched. **(b) Coverage.** A mixin selector is annotation **text**, so `transformClass` alone never fixes it: `@ModifyReturnValue(method = "method_55665...")` survives a full remap intact and the mixin then fails to apply. Both AOT compilers stopped at `transformClass`, so **every** mixin in a precompiled mod was dead (verified on revampedphantoms: 8 stale selectors across 4 mixin classes). `AotCompiler` and `FullAotCompiler` now call `AotMixinRepair.apply` (strip blocklist → member bridges → ValueIO), and the AOT jar's 53 selectors are byte-identical to the `transform` jar's (`AotMixinRepairTest`, which fails loudly if the wiring is removed). **(c) Resources, not just classes.** AOT also skipped three resource passes the JIT and CLI paths run: the refmap remap, the access widener / classTweaker remap, and `ModDataMigrator`. On a 26.x host (official namespace) an intermediary access widener is rejected by Fabric's classTweaker reader *before any mod code runs*, so there is no crash report at all, and an intermediary refmap leaves every mixin unresolvable even when its annotations are correct. Now mirrored in `AotCompiler`'s resource loop, verified byte-identical to the `transform` output on a real refmap mod. When comparing two transform paths, diff the **resource** handling as well as the class loop. Coverage also depends on *recognising* a mixin: the Fabric runtime path learns which classes are mixins from the mod's configs via `findMixinClasses`. Every accepted config naming form must reach this discovery path. Missing `modid.mixin.json` or `mixin.modid.json` left listed classes with stale annotation selectors (fixed and covered by `FabricMixinConfigDiscoveryTest`; the test mod uses the latter form). **(d) Jar-in-jar counts as a transform path.** All three nested-jar loops (`FabricModTransformer.processNestedJiJJar`, `AotCompiler.transformNestedJarAot`, `RetromodCli.transformNestedJar`) remapped nested classes but skipped the mixin pipeline, so a bundled library's mixins kept stale selectors; the AOT one also lacked the refmap / access widener / data passes. All fixed. No corpus jar bundles a mixin-bearing nested jar (the two available, codecextras and opensesame, ship zero mixin configs), so the regression test **synthesises** one: it adds a one-class library with an intermediary `@Inject` selector to a real mod and asserts the prepared jar remapped it. Build the fixture when the corpus lacks one, rather than shipping the change unverified. When adding any new class-transform loop, ask three questions: does it run after the remap, does it run the mixin pipeline, and does it handle the same *resources* as its sibling paths?

20. **A mixin-owned bridge may only call a private target member if the mixin is a CLASS.** `MixinLegacyMemberBridge` repairs a stale member by turning the mod's `@Shadow`/`@Invoker` into a concrete `@Unique` method. That works because a **class** mixin is *merged into the target*, so a call to the target's private constructor or private static `register` is an in-class access. An **accessor interface** (`@Mixin(Foo.class) public interface FooAccessor { @Invoker ... }`, the standard Fabric idiom for reaching private statics, and what Species 2.3 uses for `TreeDecoratorType.register`) is NOT merged: its methods stay in the interface. Two things then break at once. (a) Mixin widens the private target member **only because of the `@Invoker`** the bridge removes, so a direct call becomes `IllegalAccessError` at runtime, turning a mixin-apply failure into a *worse* crash. (b) The mod's **refmap** still names the member with its OLD descriptor (verified in Species: `register` maps to `register(Ljava/lang/String;Lcom/mojang/serialization/Codec;)L…TreeDecoratorType;` even after Retromod's refmap remap), so simply retyping the invoker to the modern descriptor stops it resolving. Any real fix has to move the annotation and the refmap entry together. Guarded by `mixinLegacyMemberBridge.mergesIntoTarget` (interfaces are skipped, `leavesAccessorInterfaceAlone` covers it). **Caught only by transforming the real reported mod**: the synthetic test mod used a class mixin, so it passed while the actual mod would have crashed. When a bridge emits a call to a private member, check the mixin's `ACC_INTERFACE` flag and download the mod from the issue.

## Mixin discovery (finding mixins to translate)

Historically we found broken mixins *reactively* (a mod crashes, we scroll the log per pitfall #15). The discovery tooling flips this to *predictive*: enumerate every mixin target across a mod corpus, rank them by how often they appear, and cross them against a real MC version diff so we know which mixins will break on a jump BEFORE anyone crashes. Four tools work together (full usage in [scripts/mixin-discovery.md](scripts/mixin-discovery.md)):

- **`mixin-scan` CLI command** (Java, ASM). Scans mod jars and emits an injector-level JSON table of every `@Mixin`: target classes, each injector handler (Inject/Redirect/ModifyArg/Accessor/MixinExtras WrapOperation/etc.), its `method`/`@At` selectors, and whether the mixin is actually applied via a discovered config. This is the source of truth the scripts consume. Use the executable release CLI or `mvn exec:java` from a source checkout.
- **`scripts/mixin-rank.py`** (frequency worklist). Clusters scan records by (targetClass, targetMethod) and sorts by how many mods hit each target. Translate the most-referenced targets first for the widest compatibility win per unit of effort.
- **`scripts/mixin-crossjoin.py`** (predicted breaks). Joins a scan against a `scripts/harvest-mc-diff.py` output (moves/renames/removed classes) for a specific version jump and produces a PREDICTED-BREAK worklist (target class removed, moved, or renamed) ranked by corpus frequency, with a suggested mechanism (class-redirect / resignature / blocklist). No crash needed.
- **`scripts/mixin-refmap-harvest.py`** (pure-JSON cross-check). Inventories every `*refmap.json` inside the jars without touching bytecode or needing a JDK, so it works as an independent cross-check where the Java scanner cannot run.

Caveat: these surface CANDIDATES, not confirmations. A predicted break is a hypothesis about a target that changed; correctness still requires the in-game or headless verify (per the pitfalls above, the visible crash often masks the real mixin failure).

## Dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| ASM | 9.10.1 | Bytecode manipulation (9.8+ required for Java 25) |
| Gson | 2.10.1 | JSON parsing |
| SLF4J | 2.0.18 | Logging |
| JUnit Jupiter | 6.1.3 | Testing |
| Mixin | 0.8.5 | Mixin API (provided) |
| Fabric Loader | 0.16.10 | Fabric API (provided), pinned deliberately |

**Fabric Loader is pinned at 0.16.10 on purpose. Do not bump it.** It is a `provided` dependency, so the
running loader supplies it and a newer compile version gains nothing at runtime. `fabric.mod.json`
declares `fabricloader >=0.16.0`, so compiling against a newer release lets an API that 0.16.x lacks
compile cleanly and then throw `NoSuchMethodError` on a host we claim to support. `.github/dependabot.yml`
ignores it so the bump stops being proposed weekly. Bumping ASM, Gson, or SLF4J shifts the self-hash;
re-embed it as the last release step.

## Skills

Development skills are in `.agents/skills/`:

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

If you are Claude reading this, add non-Claude updates to `AGENTS.md` too.
