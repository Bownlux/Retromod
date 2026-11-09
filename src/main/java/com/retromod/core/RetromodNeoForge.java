/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.core;

import com.retromod.gui.TitleScreenButtonInjector;
import com.retromod.inplace.InPlaceLaunch;
import com.retromod.inplace.WorkFolder;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.ArchivePublication;
import com.retromod.util.RetromodLog;
import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** NeoForge entry point: translates old NeoForge and Forge mods while NeoForge constructs mods. */
// NeoForge's scanner refuses to load a jar that declares mods in mods.toml without a
// matching @Mod("modId") class; without this RetromodNeoForge.<init> never fires.
// Resolved at compile time against the stub in net/neoforged/fml/common/Mod.java;
// NeoForge's real annotation shadows it at runtime.
@net.neoforged.fml.common.Mod("retromod")
public class RetromodNeoForge {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    
    public RetromodNeoForge() {
        RetromodVersion.logPresenceBanner(LOGGER);
        // NeoForge does not load the Fabric entry point that normally records this version.
        String mcVersion = RetromodVersion.detectFmlMcVersion();
        if (mcVersion != null) {
            RetromodVersion.TARGET_MC_VERSION = mcVersion;
        } else {
            // The shim gate needs the host version to decide which API changes are safe to apply.
            LOGGER.error("Retromod could not detect the NeoForge Minecraft version. It will "
                    + "use {} and skip shims for newer versions, so some mods may not work. "
                    + "Please report your NeoForge and FML versions.",
                    RetromodVersion.TARGET_MC_VERSION);
        }

        LOGGER.info("Starting Retromod {} on NeoForge for Minecraft {}",
                RetromodVersion.RETROMOD_VERSION, RetromodVersion.TARGET_MC_VERSION);
        // A jar built for several loaders is read through the metadata NeoForge reads.
        ModVersionDetector.setDefaultPreferredLoader("neoforge");

        // First-time users need a real file they can edit.
        RetromodConfig.ensureDefaultConfig();
        // Stage transforms in the game folder, not a temp folder a launcher sandbox may limit.
        WorkFolder.useGameFolder(Paths.get(".").toAbsolutePath().normalize());

        publishNeoForgeEnvironment();
        boolean isServer = EnvironmentDetector.isDedicatedServer();
        LOGGER.debug(RetromodLog.DETAIL, "Environment: {}", isServer ? "Dedicated Server" : "Client");
        // Old mods' item properties reach their item definitions through a property type added before the first reload.
        if (!isServer) com.retromod.shim.common.LegacyItemPropertiesBridge.addPropertyType();

        try {
            com.retromod.resources.ResourceManager.processStagedPacks(
                    RetromodVersion.TARGET_MC_VERSION,
                    Paths.get(".").toAbsolutePath().normalize());
        } catch (Exception e) {
            LOGGER.warn("Could not initialize resource manager", e);
        }

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        registerTransformRedirects(transformer);

        // Forge SimpleChannel messages from transformed mods are registered as NeoForge payloads
        // when NeoForge locks its network registry, after every mod has created its channels.
        com.retromod.shim.forge.embedded.NetworkShim.listenForPayloadRegistration();
        // Forge items hand out renderers through initializeClient, which NeoForge no longer calls.
        com.retromod.shim.forge.ClientExtensionsBridge.listen();
        // NeoForge 26.3 dropped the fuel and compost data maps for item components, so translated
        // mods' old map files are applied as components.
        com.retromod.shim.neoforge.LegacyDataMapComponentBridge.listen();
        // Old mods that read the enchantment registry during a data pack load find that load's registries.
        com.retromod.shim.common.ReloadRegistryRecorder.listen();

        // AutoFix is opt-in on every loader. logs/latest.log is writable by any mod via
        // SLF4J, so a crafted log line matching AutoFixEngine's regex could register
        // attacker-chosen redirects on the shared transformer. The flag gates both the
        // saved-fix loader here and the live log analyzer below. See Retromod.java.
        boolean autoFixEnabled = Boolean.parseBoolean(
                System.getProperty("retromod.autoFix", "false"));

        // Saved fixes from a prior launch's crash-log analysis. Load after shims (so shim
        // redirects win) but before transformation (so fixes apply during transform).
        if (autoFixEnabled) {
            try {
                AutoFixEngine autoFixEngine = new AutoFixEngine();
                int savedFixes = autoFixEngine.loadAndApplySavedFixes(transformer);
                if (savedFixes > 0) {
                    LOGGER.debug(RetromodLog.DETAIL, "AutoFix: loaded {} saved fix(es) from previous launch", savedFixes);
                }
            } catch (Exception e) {
                LOGGER.debug("Could not load auto-fix saved fixes: {}", e.getMessage());
            }
        }

        // Fuzzy resolver: last-resort fallback for unresolved references, MC JAR
        // auto-detected from the classpath.
        try {
            transformer.initFuzzyResolver(null);
        } catch (Exception e) {
            LOGGER.debug("Could not initialize fuzzy resolver: {}", e.getMessage());
        }

        // In-place mode serves translations from Retromod's cache and never writes into mods/.
        // The rewrite flow below is what write_translated_jars=true keeps.
        InPlaceLaunch launch = InPlaceLaunch.forEntryPoint(
                Paths.get(".").toAbsolutePath().normalize());
        launch.logDecision();

        initializeHybridEngine(launch);

        // On a 26.2+ client, prefer the still-present OpenGL backend so translated old
        // mods' OpenGL rendering keeps working. The constructor may run after the GpuDevice
        // is created, in which case it takes effect on next launch. See GraphicsBackendCompat.
        try {
            GraphicsBackendCompat.ensureOpenGlForOldMods(
                Paths.get(".").toAbsolutePath().normalize(), RetromodVersion.TARGET_MC_VERSION);
        } catch (Exception e) {
            LOGGER.debug("Graphics backend preference skipped: {}", e.getMessage());
        }

        // The rewrite flow translates retromod-input/ and then rewrites old jars in mods/.
        int transformed;
        if (launch.rewrite()) {
            transformed = transformModsFromInput();

            int inPlace = transformModsInPlace();
            transformed += inPlace;

            // In-place transform runs at constructor time, after the module layer is built,
            // too late to fix module-layer failures. Nudge the user toward the offline path
            // for next time (#95).
            if (inPlace > 0) {
                recommendAotFlow(inPlace);
            }
        } else {
            // The same transform the rewrite flow uses, so cached jars match it. A failure is
            // thrown, so the cache keeps its reason and retries a disk error.
            transformed = launch.translatePending(RetromodVersion.TARGET_MC_VERSION, () ->
                    (InPlaceLaunch.LinkingTranslator)
                            new ForgeModTransformer(RetromodVersion.TARGET_MC_VERSION)::translateOrThrow);
        }

        if (launch.rewrite()) {
            com.retromod.gui.RestartPrompt.markPending(transformed);
        } else {
            com.retromod.gui.RestartPrompt.markTranslatedIntoCache(transformed);
        }

        // A client gets the title-screen button, which also shows the restart prompt armed above.
        // A dedicated server reports on the console only.
        if (!isServer) registerTitleScreenButton();
        if (isServer) LOGGER.debug(RetromodLog.DETAIL, "Running without the in-game interface on this server");
        String restart = com.retromod.gui.RestartPrompt.consoleText(
                isServer, transformed, !launch.rewrite());
        if (restart != null) LOGGER.info(restart);

        scanForRuntimeTransformableMods(launch);

        // Scan the previous launch's latest.log for known crash patterns and register
        // fixes for the next retransformation. Gated on the same -Dretromod.autoFix flag,
        // since latest.log is attacker-writable through any mod's SLF4J logger.
        if (autoFixEnabled) {
            try {
                Path gameDir = Paths.get(".").toAbsolutePath().normalize();
                Path logFile = gameDir.resolve("logs/latest.log");
                if (Files.exists(logFile)) {
                    AutoFixEngine autoFixEngine = new AutoFixEngine();
                    java.util.List<AutoFixEngine.AppliedFix> fixes =
                        autoFixEngine.analyzeAndFix(logFile, transformer);
                    if (!fixes.isEmpty()) {
                        LOGGER.warn("Auto-fix registered {} redirect(s) from the previous log. "
                                + "Review each one because log content is untrusted:",
                                fixes.size());
                        for (AutoFixEngine.AppliedFix fix : fixes) {
                            LOGGER.warn("  AutoFix [{}] {} => {}",
                                    fix.errorType(), fix.description(), fix.action());
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Could not run auto-fix analysis: {}", e.getMessage());
            }
        } else {
            LOGGER.debug("Auto-fix is disabled. Enable it with -Dretromod.autoFix=true "
                    + "(see security notes in Retromod.java).");
        }

        LOGGER.debug(RetromodLog.DETAIL, "Retromod initialized.");
        
        if (isServer) {
            LOGGER.debug(RetromodLog.DETAIL, "Retromod is running in NeoForge server mode.");
            LOGGER.debug(RetromodLog.DETAIL, "Bytecode transforms, ahead-of-time compilation, and Forge migration are enabled.");
            LOGGER.debug(RetromodLog.DETAIL, "GUI features are unavailable on a headless server.");
        }
    }

    /** Publish NeoForge's authoritative logical side before generic environment checks run. */
    private static void publishNeoForgeEnvironment() {
        try {
            Class<?> environment = Class.forName(
                    "net.neoforged.fml.loading.FMLEnvironment", false,
                    RetromodNeoForge.class.getClassLoader());
            Object side;
            try {
                side = environment.getMethod("getDist").invoke(null);
            } catch (NoSuchMethodException missingGetter) {
                side = environment.getField("dist").get(null);
            }
            String sideName = side instanceof Enum<?> value ? value.name() : String.valueOf(side);
            Boolean client = EnvironmentDetector.clientSideFromLoaderName(sideName);
            if (client != null) {
                EnvironmentDetector.setLoaderEnvironment(client);
            } else {
                LOGGER.warn("NeoForge reported an unknown logical side: {}", sideName);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.warn("Could not read NeoForge's logical side; using resource detection", e);
        }
    }
    
    /**
     * Read the {@code polyfills_enabled} config flag (default {@code true}). Uses only
     * {@link RetromodConfig} + Gson, so it adds no foreign-loader reference to this
     * class's constant pool (LoaderIsolationTest).
     */
    private static boolean readPolyfillsEnabled() {
        return RetromodConfig.getBoolean("polyfills_enabled", true);
    }

    /**
     * Load the {@link com.retromod.polyfill.PolyfillProvider}s on the NeoForge runtime.
     * Package-private and I/O-light (only the shared transformer) so the loader-safety
     * behavior is unit-testable without constructing the @Mod entry point.
     *
     * <p>On a Mojang-named NeoForge runtime the intermediary/MCP redirects no-op while
     * the Forge -> NeoForge migration redirects ({@code Dist}, {@code @OnlyIn}, ...)
     * apply; the removed-class providers self-gate on {@link RetromodVersion#TARGET_MC_VERSION}.
     *
     * <p>The {@code neoforge} category is left off: it re-implements the NeoForge 1.21.9
     * transfer-API rework that the host-gated {@code NeoForge_1_21_8_to_1_21_9} shim (#9)
     * already owns, and it is un-gated, so on a pre-1.21.9 host it would rewrite a
     * still-present {@code IItemHandler} to a not-yet-existing {@code ResourceHandler}.
     */
    static void registerPolyfills(RetromodTransformer transformer, boolean polyfillsEnabled) {
        com.retromod.polyfill.PolyfillRegistry registry =
                new com.retromod.polyfill.PolyfillRegistry();
        registry.setEnabled(polyfillsEnabled);
        registry.setCategoryEnabled("neoforge", false); // owned by the host-gated shim
        registry.loadAndRegister(transformer);
    }

    private void initializeHybridEngine(InPlaceLaunch launch) {
        try {
            Path gameDir = Paths.get(".").toAbsolutePath().normalize();
            Path modsFolder = gameDir.resolve("mods");
            
            // The engine skips originals that in-place mode holds back for their cached translation.
            HybridTransformationEngine hybrid = HybridTransformationEngine.getInstance();
            hybrid.initialize(modsFolder, RetromodVersion.TARGET_MC_VERSION,
                    launch::isHiddenOriginal);
            
            LOGGER.debug(RetromodLog.DETAIL, "Hybrid AOT/JIT engine initialized");
        } catch (Exception e) {
            LOGGER.warn("Could not initialize hybrid engine: {}", e.getMessage());
        }
    }
    
    /** Registers the in-game title screen button, the way into the Retromod screen. */
    private void registerTitleScreenButton() {
        // The injector logs whether a supported event bus took the button.
        try {
            TitleScreenButtonInjector.register();
        } catch (Exception e) {
            LOGGER.debug("Title screen button not available: {}", e.getMessage());
        }
    }

    /** Registers what this entry point transforms mods with: shims, SRG names, class moves, bridges, polyfills. */
    static void registerTransformRedirects(RetromodTransformer transformer) {
        // NeoForge shims, including Forge migration shims.
        loadNeoForgeShims(transformer);

        // Forge SRG -> Mojang member names. NeoForge 1.20.2 and newer run Mojang names, but a
        // Forge SRG-baked mod (Jade, JEI Forge) migrated onto NeoForge carries SRG names with it
        // and crashes without this mapping.
        try {
            int srgEntries = com.retromod.mapping.SrgToMojangMapper.getInstance()
                    .applyTo(transformer);
            if (srgEntries > 0) {
                LOGGER.debug(RetromodLog.DETAIL, "Registered {} SRG → Mojang mapping(s)", srgEntries);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not register SRG mappings", e);
        }

        // Vanilla net/minecraft/* class moves and renames. NeoForge mods skip the
        // intermediary->Mojang remap but still need vanilla renames (ResourceLocation
        // ->Identifier, repackaged entities) or they crash with NoClassDefFoundError.
        // applyClassMovesOnly is host-version-aware: each rename applies only where the
        // host has the new class and not the old one, so it works on both a 1.21.11 and
        // a 26.1 host without the #9 hazard.
        try {
            int moves = com.retromod.mapping.IntermediaryToMojangMapper
                    .applyClassMovesOnly(transformer);
            if (moves > 0) {
                LOGGER.debug(RetromodLog.DETAIL, "Registered {} vanilla class move(s) for NeoForge", moves);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not register vanilla class moves", e);
        }

        // Bridges for classes NeoForge deleted (FMLJavaModLoadingContext, DeferredSpawnEggItem
        // #85), gated on the original being absent; SyntheticEmbedder embeds each per-mod.
        try {
            com.retromod.shim.forge.ForgeNeoForgeSynthetics.register(transformer);
        } catch (Exception e) {
            LOGGER.warn("Could not register Forge/NeoForge synthetics", e);
        }

        // Polyfills (re-implemented removed APIs). The Fabric entry loaded these; without
        // this a NeoForge user on the in-place transform missed the Forge -> NeoForge
        // Dist/@OnlyIn redirects and the removed-class bridges.
        try {
            registerPolyfills(transformer, readPolyfillsEnabled());
        } catch (Exception e) {
            LOGGER.warn("Could not register polyfills", e);
        }

        // Minecraft renames between the version a mod was built for and this host. Registered
        // last, because a shim or polyfill for the old name keeps it.
        try {
            com.retromod.mapping.MojangRenameTable.register(transformer, RetromodVersion.TARGET_MC_VERSION);
        } catch (Exception e) {
            LOGGER.warn("Could not register Minecraft renames", e);
        }
    }

    private static void loadNeoForgeShims(RetromodTransformer transformer) {
        try {
            java.util.ServiceLoader<VersionShim> loader = 
                java.util.ServiceLoader.load(VersionShim.class);
            
            int count = 0;
            int failed = 0;
            java.util.Iterator<VersionShim> it = loader.iterator();
            while (it.hasNext()) {
                VersionShim shim;
                try {
                    shim = it.next();
                } catch (java.util.ServiceConfigurationError e) {
                    continue; // class absent in lite builds
                }
                String loaderType = shim.getModLoaderType();
                if ("neoforge".equals(loaderType) ||
                    "forge".equals(loaderType) ||
                    "common".equals(loaderType)) {
                    // Register only shims whose target MC is <= the host. A newer shim
                    // (IItemHandler->ItemHandler, ...) applied on an older host renames to
                    // classes that don't exist there and crashes at load (#38).
                    if (!ShimRegistry.isAvailableOnHost(
                            shim, RetromodVersion.TARGET_MC_VERSION)) {
                        continue;
                    }
                    // One shim that throws is skipped alone, so the shims after it still apply.
                    if (ShimRegistration.register(shim, transformer)) {
                        count++;
                    } else {
                        failed++;
                    }
                }
            }
            
            LOGGER.debug(RetromodLog.DETAIL, "Loaded {} NeoForge/Forge version shims", count);
            if (failed > 0) {
                LOGGER.warn("{} NeoForge/Forge version shim(s) could not be registered; see the "
                        + "warnings above", failed);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load NeoForge shims", e);
        }
    }
    
    /** Transform mods (bytecode + mods.toml version) from retromod-input/ and move to mods/. */
    private int transformModsFromInput() {
        int count = 0;
        try {
            Path gameDir = Paths.get(".").toAbsolutePath().normalize();
            Path inputDir = gameDir.resolve("retromod-input");
            Path modsDir = gameDir.resolve("mods");
            Path processedDir = inputDir.resolve("processed");

            ZipSecurity.validateNotSymlink(inputDir);
            ZipSecurity.validateNotSymlink(modsDir);

            Files.createDirectories(inputDir);
            prepareProcessedDirectory(processedDir);

            if (!Files.isDirectory(inputDir)) return 0;

            List<Path> jars;
            try (var stream = Files.list(inputDir)) {
                jars = stream.filter(p -> p.toString().endsWith(".jar"))
                             .filter(Files::isRegularFile).toList();
            }

            if (jars.isEmpty()) return 0;

            ForgeModTransformer transformer = new ForgeModTransformer(RetromodVersion.TARGET_MC_VERSION);
            SyntheticLibraries libraries = SyntheticLibraries.ofFolder(modsDir);

            for (Path modJar : SyntheticLibraries.librariesFirst(jars, jar -> jar)) {
                try {
                    String fileName = modJar.getFileName().toString();
                    LOGGER.info("Transforming from retromod-input/: {}", fileName);

                    Path transformed = transformer.transformMod(modJar, modsDir, libraries);
                    if (transformed != null) {
                        libraries.add(transformed);
                        ArchivePublication.moveReplacing(
                                modJar, processedDir.resolve(fileName));
                        count++;
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to transform {}: {}", modJar.getFileName(), e.getMessage());
                }
            }

            if (count > 0) {
                LOGGER.info("Transformed {} mod(s) from retromod-input/", count);
            }
        } catch (Exception e) {
            LOGGER.error("Error scanning retromod-input/: {}", e.getMessage());
        }
        return count;
    }

    static void prepareProcessedDirectory(Path processedDir) throws java.io.IOException {
        Files.createDirectories(processedDir);
        ZipSecurity.validateNotSymlink(processedDir);
    }

    /** Transform incompatible mods in mods/ in place, backing up originals to retromod-backups/. */
    private int transformModsInPlace() {
        int count = 0;
        try {
            Path gameDir = Paths.get(".").toAbsolutePath().normalize();
            Path modsDir = gameDir.resolve("mods");
            Path backupDir = modsDir.resolve("retromod-backups");

            ZipSecurity.validateNotSymlink(modsDir);
            ZipSecurity.validateNotSymlink(backupDir);

            if (!Files.isDirectory(modsDir)) return 0;

            ForgeModTransformer transformer = new ForgeModTransformer(RetromodVersion.TARGET_MC_VERSION);
            ModVersionDetector detector = new ModVersionDetector();

            List<Path> jars;
            try (var stream = Files.list(modsDir)) {
                jars = stream.filter(p -> p.toString().endsWith(".jar"))
                             .filter(Files::isRegularFile)
                             .filter(p -> !p.getFileName().toString().contains("-retromod"))
                             .filter(p -> !p.getFileName().toString().startsWith("retromod"))
                             .toList();
            }

            List<Path> incompatible = new java.util.ArrayList<>();
            for (Path modJar : jars) {
                try {
                    var info = detector.detectVersion(modJar);
                    if (info != null && info.needsTransformation(RetromodVersion.TARGET_MC_VERSION)) {
                        LOGGER.debug(RetromodLog.DETAIL, "Found {} in mods/, made for Minecraft {}",
                                modJar.getFileName(), info.targetMcVersion());
                        incompatible.add(modJar);
                    }
                } catch (Exception e) {
                    LOGGER.debug("Could not check {}: {}", modJar.getFileName(), e.toString());
                }
            }
            SyntheticLibraries libraries = incompatible.isEmpty()
                    ? SyntheticLibraries.NONE : SyntheticLibraries.ofFolder(modsDir);

            for (Path modJar : SyntheticLibraries.librariesFirst(incompatible, jar -> jar)) {
                if (rewriteJarInMods(modJar, backupDir, transformer::transformMod, libraries)) {
                    libraries.add(modJar);
                    LOGGER.info("Transformed in place: {}", modJar.getFileName());
                    count++;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error scanning mods/ for transformation: {}", e.getMessage());
        }
        return count;
    }

    /** Backs up one old jar in mods/ and replaces it with its translation; false when it did not. */
    static boolean rewriteJarInMods(Path modJar, Path backupDir,
            InPlaceLaunch.LinkingTranslator translator, SyntheticLibraries libraries) {
        String fileName = modJar.getFileName().toString();
        try {
            Files.createDirectories(backupDir);
            ArchivePublication.copyReplacing(modJar, backupDir.resolve(fileName));
            return withInPlaceTransformDirectory(tempDir -> {
                Path transformed = translator.translate(modJar, tempDir, libraries);
                if (transformed == null) return false;
                ArchivePublication.moveReplacing(transformed, modJar);
                return true;
            });
        } catch (Exception e) {
            // The old jar stays in mods/, so latest.log has to say why it was not updated.
            LOGGER.warn("Could not translate {} in mods/: {}. Retromod tries again on the next "
                    + "launch.", fileName, e.toString());
            return false;
        }
    }

    @FunctionalInterface
    interface TemporaryTransformOperation<T> {
        T run(Path directory) throws Exception;
    }

    /** Runs one in-place transform with cleanup that cannot hide the transform failure. */
    static <T> T withInPlaceTransformDirectory(TemporaryTransformOperation<T> operation)
            throws Exception {
        Path tempDir = WorkFolder.createTempDirectory("retromod-inplace-");
        try {
            return operation.run(tempDir);
        } finally {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                                // A cleanup failure must not replace the transform exception.
                            }
                        });
            } catch (Exception ignored) {
                // The temporary tree is isolated and can be reclaimed by the operating system.
            }
        }
    }

    /** Recommends the earlier offline path when startup had to update jars in place. */
    private void recommendAotFlow(int inPlaceCount) {
        LOGGER.info("Retromod updated {} mod(s) during NeoForge startup.", inPlaceCount);
        LOGGER.debug(RetromodLog.DETAIL, "For future setup, `retromod prepare <minecraft-dir> --target {} --target-loader "
            + "neoforge` is more reliable because it updates jars before NeoForge builds its module "
            + "layer.", RetromodVersion.TARGET_MC_VERSION);
    }

    private void scanForRuntimeTransformableMods(InPlaceLaunch launch) {
        try {
            Path modsFolder = Paths.get("mods");
            if (!Files.exists(modsFolder)) return;
            
            ModVersionDetector detector = new ModVersionDetector();
            java.io.File[] modFiles = modsFolder.toFile().listFiles(
                (dir, name) -> name.endsWith(".jar") && !name.contains("-retromod")
            );
            
            if (modFiles == null) return;
            
            for (java.io.File modFile : modFiles) {
                // The loader runs the cached translation of a held-back original, not this file.
                if (launch.isHiddenOriginal(modFile.toPath())) continue;
                try {
                    var info = detector.detectVersion(modFile.toPath());
                    if (info != null && info.needsTransformation(RetromodVersion.TARGET_MC_VERSION)) {
                        String sourceVersion = info.targetMcVersion();

                        if (sourceVersion != null &&
                            "neoforge".equals(info.modLoaderType())) {
                            LOGGER.debug(RetromodLog.DETAIL, "Runtime transforming: {} ({} -> {})",
                                modFile.getName(), sourceVersion, RetromodVersion.TARGET_MC_VERSION);
                            
                            for (String pkg : info.modPackages()) {
                                RetromodTransformer.getInstance().addTransformablePackage(pkg);
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("Could not analyze: {}", modFile.getName());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error scanning mods", e);
        }
    }
}
