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

// @Mod is referenced by FQN, not imported, so this class still compiles when
// Forge isn't on the classpath (the standalone CLI build). It must be present at
// runtime under Forge: FML refuses to load a JAR whose mods.toml declares a
// modId without a matching @Mod class ("The Mod File <jar> has mods that were not found").

/** Forge entry point: translates old Forge mods while Forge constructs mods. */
@net.minecraftforge.fml.common.Mod("retromod")
public class RetromodForge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    public RetromodForge() {
        RetromodVersion.logPresenceBanner(LOGGER);
        // Forge does not load the Fabric entry point that normally records this version.
        detectMcVersionForForge();

        LOGGER.info("Starting Retromod {} on Forge for Minecraft {}",
                RetromodVersion.RETROMOD_VERSION, RetromodVersion.TARGET_MC_VERSION);
        // A jar built for several loaders is read through the metadata Forge reads.
        ModVersionDetector.setDefaultPreferredLoader("forge");
        // Set before any shim or polyfill registers, so none replaces a class Forge still ships.
        com.retromod.util.McReflect.setForceForge(true);

        // First-time users need a real file they can edit.
        RetromodConfig.ensureDefaultConfig();
        // Stage transforms in the game folder, not a temp folder a launcher sandbox may limit.
        WorkFolder.useGameFolder(Paths.get(".").toAbsolutePath().normalize());

        publishForgeEnvironment();
        boolean isServer = EnvironmentDetector.isDedicatedServer();
        LOGGER.debug(RetromodLog.DETAIL, "Running on a {}", isServer ? "dedicated server" : "client");
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
        // Old mods that read the enchantment registry during a data pack load find that load's registries.
        com.retromod.shim.common.ReloadRegistryRecorder.listen();

        // AutoFix is opt-in on every loader: latest.log is writable by any mod
        // via SLF4J, so a crafted log line matching AutoFixEngine's error regex
        // could register attacker-chosen redirects on the shared transformer.
        // The flag gates both the saved-fix loader here and the log analyzer
        // below. See Retromod.java for the full security note.
        boolean autoFixEnabled = Boolean.parseBoolean(
                System.getProperty("retromod.autoFix", "false"));

        // Load fixes discovered from a prior launch's crash log. After shims (so
        // shim redirects win) but before transformation (so fixes get applied).
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

        // Fuzzy resolver: last-resort fallback for unresolved references (null = auto-detect MC JAR).
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

        // On a 26.2+ client, prefer the OpenGL backend so translated old mods'
        // OpenGL rendering keeps working. No-op below 26.2 / on a server / if the
        // user already chose a backend.
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
            transformed += transformModsInPlace();
        } else {
            // The same transform the rewrite flow uses, so cached jars match it. A failure is
            // thrown, so the cache keeps its reason and retries a disk error.
            transformed = launch.translatePending(RetromodVersion.TARGET_MC_VERSION, () ->
                    (InPlaceLaunch.LinkingTranslator)
                            new ForgeModTransformer(RetromodVersion.TARGET_MC_VERSION)::translateOrThrow);
        }

        // Arm the in-game restart prompt (#33), shown on the title screen.
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

        // Scan for mods that can be runtime-transformed (minor versions)
        scanForRuntimeTransformableMods(launch);

        // Scan the previous launch's log for known crash patterns and register
        // fixes for the next retransformation. Gated on the same opt-in flag
        // above (latest.log is attacker-writable via any mod's SLF4J logger).
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
            LOGGER.debug(RetromodLog.DETAIL, "Retromod is running in server mode.");
            LOGGER.debug(RetromodLog.DETAIL, "Bytecode transforms and ahead-of-time compilation are enabled.");
            LOGGER.debug(RetromodLog.DETAIL, "GUI features are unavailable on a headless server.");
        }
    }

    /** Publish Forge's authoritative logical side before generic environment checks run. */
    private static void publishForgeEnvironment() {
        try {
            Class<?> environment = Class.forName(
                    "net.minecraftforge.fml.loading.FMLEnvironment", false,
                    RetromodForge.class.getClassLoader());
            Object side;
            try {
                side = environment.getField("dist").get(null);
            } catch (NoSuchFieldException missingField) {
                side = environment.getMethod("getDist").invoke(null);
            }
            String sideName = side instanceof Enum<?> value ? value.name() : String.valueOf(side);
            Boolean client = EnvironmentDetector.clientSideFromLoaderName(sideName);
            if (client != null) {
                EnvironmentDetector.setLoaderEnvironment(client);
            } else {
                LOGGER.warn("Forge reported an unknown logical side: {}", sideName);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.warn("Could not read Forge's logical side; using resource detection", e);
        }
    }
    
    /**
     * Detect the running MC version from Forge's loader and store it in
     * {@link RetromodVersion#TARGET_MC_VERSION}. Tries FancyModLoader first,
     * then the legacy {@code MCPVersion} class; leaves the default if neither works.
     */
    private static void detectMcVersionForForge() {
        // FancyModLoader (new Forge / NeoForge), robust across FML API versions.
        // The older versionInfo() probe threw NoSuchMethodException on FML 10.x
        // and gated out every newer-MC shim (#47).
        String mcVersion = RetromodVersion.detectFmlMcVersion();
        if (mcVersion != null) {
            RetromodVersion.TARGET_MC_VERSION = mcVersion;
            return;
        }

        // Legacy Forge MCPVersion fallback
        try {
            Class<?> mcpVersion = Class.forName("net.minecraftforge.versions.mcp.MCPVersion");
            String mc = (String) mcpVersion.getMethod("getMCVersion").invoke(null);
            if (mc != null && !mc.isBlank()) {
                RetromodVersion.TARGET_MC_VERSION = mc;
                return;
            }
        } catch (Throwable ignored) {}

        // The shim gate needs the host version to decide which API changes are safe to apply.
        LOGGER.error("Retromod could not detect the Forge Minecraft version. It will use {} "
                + "and skip shims for newer versions, so some mods may not work. Please report "
                + "your Forge and FML versions.",
                RetromodVersion.TARGET_MC_VERSION);
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

    /** Registers what this entry point transforms Forge mods with: shims, polyfills, SRG names, class moves. */
    static void registerTransformRedirects(RetromodTransformer transformer) {
        loadForgeShims(transformer);

        // NeoForge transfer polyfills target a different runtime and must stay disabled here.
        try {
            com.retromod.polyfill.PolyfillRegistry polyfillRegistry =
                    new com.retromod.polyfill.PolyfillRegistry();
            polyfillRegistry.setEnabled(
                    RetromodConfig.getBoolean("polyfills_enabled", true));
            polyfillRegistry.setCategoryEnabled("neoforge", false);
            polyfillRegistry.loadAndRegister(transformer);
        } catch (Exception e) {
            LOGGER.warn("Could not load polyfills", e);
        }

        // Forge 1.20.6+ runs Minecraft with Mojang member names. Older Forge targets instead
        // need source SRG -> Mojang -> the target version's SRG namespace.
        try {
            int srgEntries = 0;
            boolean officialTarget = RetromodVersion.usesOfficialForgeRuntimeNames(
                    RetromodVersion.TARGET_MC_VERSION);
            var targetSrg = com.retromod.mapping.TargetSrgMapper.forVersion(
                    RetromodVersion.TARGET_MC_VERSION);
            int targetEntries = officialTarget ? 0 : targetSrg.applyTo(transformer);
            if (officialTarget || targetEntries > 0) {
                srgEntries = com.retromod.mapping.SrgToMojangMapper.getInstance()
                        .applyTo(transformer);
                srgEntries += targetEntries;
            }
            if (srgEntries > 0) {
                LOGGER.debug(RetromodLog.DETAIL, "Registered {} cross-version SRG mapping(s)", srgEntries);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not register SRG mappings", e);
        }

        // Forge mods are Mojang-named after the SRG remap and skip the
        // intermediary remap, but still need vanilla net/minecraft/* class
        // renames or they crash with NoClassDefFoundError. applyClassMovesOnly
        // is host-aware: it applies a rename only where the host has the new
        // class and not the old one, so it's safe on any host (avoids the #9 hazard).
        try {
            int moves = com.retromod.mapping.IntermediaryToMojangMapper
                    .applyClassMovesOnly(transformer);
            if (moves > 0) {
                LOGGER.debug(RetromodLog.DETAIL, "Registered {} vanilla class move(s) for Forge", moves);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not register vanilla class moves", e);
        }

        // Minecraft renames between the version a mod was built for and this host. Registered
        // last, because a shim or polyfill for the old name keeps it.
        try {
            com.retromod.mapping.MojangRenameTable.register(transformer, RetromodVersion.TARGET_MC_VERSION);
        } catch (Exception e) {
            LOGGER.warn("Could not register Minecraft renames", e);
        }
    }

    private static void loadForgeShims(RetromodTransformer transformer) {
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
                    continue; // class absent, expected in lite builds
                }
                String loaderType = shim.getModLoaderType();
                if ("forge".equals(loaderType) || "common".equals(loaderType)) {
                    // Only register shims whose target MC is <= the host. The
                    // 1.21.11→26.1 shim renames classes to 26.1 names that don't
                    // exist on a 1.21.x host, which would crash at load (#38).
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
            
            LOGGER.debug(RetromodLog.DETAIL, "Loaded {} Forge version shims", count);
            if (failed > 0) {
                LOGGER.warn("{} Forge version shim(s) could not be registered; see the "
                        + "warnings above", failed);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load Forge shims", e);
        }
    }
    
    /** Transform mods from the retromod-input/ folder into mods/. */
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

            java.util.List<Path> jars;
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

    /** Scan mods/ for incompatible mods and transform them in place. */
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

            java.util.List<Path> jars;
            try (var stream = Files.list(modsDir)) {
                jars = stream.filter(p -> p.toString().endsWith(".jar"))
                             .filter(Files::isRegularFile)
                             .filter(p -> !p.getFileName().toString().contains("-retromod"))
                             .filter(p -> !p.getFileName().toString().startsWith("retromod"))
                             .toList();
            }

            java.util.List<Path> incompatible = new java.util.ArrayList<>();
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

            Path tempDir = WorkFolder.createTempDirectory("retromod-inplace-");
            try {
                Path transformed = translator.translate(modJar, tempDir, libraries);
                if (transformed == null) return false;
                ArchivePublication.moveReplacing(transformed, modJar);
                return true;
            } finally {
                try (var walk = Files.walk(tempDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                }
            }
        } catch (Exception e) {
            // The old jar stays in mods/, so latest.log has to say why it was not updated.
            LOGGER.warn("Could not translate {} in mods/: {}. Retromod tries again on the next "
                    + "launch.", fileName, e.toString());
            return false;
        }
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

                        if (sourceVersion != null) {
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
