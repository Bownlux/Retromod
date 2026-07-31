/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.core;

import com.retromod.gui.RetromodGui;
import com.retromod.gui.TitleScreenButtonInjector;
import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * NeoForge entry point. Runs on clients (Swing setup + title-screen button) and
 * dedicated servers (headless, console only), and handles Forge -> NeoForge migration.
 */
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

        LOGGER.info("Starting Retromod on NeoForge for Minecraft {}",
                RetromodVersion.TARGET_MC_VERSION);

        // First-time users need a real file they can edit.
        RetromodConfig.ensureDefaultConfig();

        boolean isServer = EnvironmentDetector.isDedicatedServer();
        LOGGER.info("Environment: {}", isServer ? "Dedicated Server" : "Client");

        RetromodTransformer transformer = RetromodTransformer.getInstance();

        // NeoForge shims, including Forge migration shims.
        loadNeoForgeShims(transformer);

        // Forge SRG -> Mojang member names. NeoForge has been Mojang-named since 1.17,
        // but a Forge SRG-baked mod (Jade, JEI Forge) migrated onto NeoForge carries
        // SRG names with it and crashes without this mapping.
        try {
            int srgEntries = com.retromod.mapping.SrgToMojangMapper.getInstance()
                    .applyTo(transformer);
            if (srgEntries > 0) {
                LOGGER.info("Registered {} SRG → Mojang mapping(s)", srgEntries);
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
                LOGGER.info("Registered {} vanilla class move(s) for NeoForge", moves);
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
                    LOGGER.info("AutoFix: loaded {} saved fix(es) from previous launch", savedFixes);
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

        initializeHybridEngine();

        // On a 26.2+ client, prefer the still-present OpenGL backend so translated old
        // mods' OpenGL rendering keeps working. The constructor may run after the GpuDevice
        // is created, in which case it takes effect on next launch. See GraphicsBackendCompat.
        try {
            GraphicsBackendCompat.ensureOpenGlForOldMods(
                Paths.get(".").toAbsolutePath().normalize(), RetromodVersion.TARGET_MC_VERSION);
        } catch (Exception e) {
            LOGGER.debug("Graphics backend preference skipped: {}", e.getMessage());
        }

        int transformed = transformModsFromInput();

        int inPlace = transformModsInPlace();
        transformed += inPlace;

        // In-place transform runs at constructor time, after the module layer is built,
        // too late to fix module-layer failures. Nudge the user toward the offline path
        // for next time (#95).
        if (inPlace > 0) {
            recommendAotFlow(inPlace);
        }

        com.retromod.gui.RestartPrompt.markPending(transformed);

        if (!isServer && EnvironmentDetector.canShowGui()) {
            if (transformed > 0) {
                showRestartPopup(transformed);
            } else {
                initializeClientGui();
            }
        } else {
            LOGGER.info("Running without the in-game interface on this server");
            if (transformed > 0) {
                LOGGER.info("Updated {} {}. Restart the server to load {}.",
                        transformed, transformed == 1 ? "mod" : "mods",
                        transformed == 1 ? "it" : "them");
            }
        }

        scanForRuntimeTransformableMods();

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

        LOGGER.info("Retromod initialized.");
        
        if (isServer) {
            LOGGER.info("Retromod is running in NeoForge server mode.");
            LOGGER.info("Bytecode transforms, ahead-of-time compilation, and Forge migration are enabled.");
            LOGGER.info("GUI features are unavailable on a headless server.");
        }
    }
    
    /**
     * Read the {@code polyfills_enabled} config flag (default {@code true}). Uses only
     * {@link RetromodConfig} + Gson, so it adds no foreign-loader reference to this
     * class's constant pool (LoaderIsolationTest).
     */
    private static boolean readPolyfillsEnabled() {
        try {
            com.google.gson.JsonObject cfg = RetromodConfig.loadOrNull();
            if (cfg != null && cfg.has("polyfills_enabled")) {
                return cfg.get("polyfills_enabled").getAsBoolean();
            }
        } catch (Exception ignored) {
            // a config read must never block mod loading
        }
        return true;
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

    private void initializeHybridEngine() {
        try {
            Path gameDir = Paths.get(".").toAbsolutePath().normalize();
            Path modsFolder = gameDir.resolve("mods");
            
            HybridTransformationEngine hybrid = HybridTransformationEngine.getInstance();
            hybrid.initialize(modsFolder, RetromodVersion.TARGET_MC_VERSION);
            
            LOGGER.info("Hybrid AOT/JIT engine initialized");
        } catch (Exception e) {
            LOGGER.warn("Could not initialize hybrid engine: {}", e.getMessage());
        }
    }
    
    /**
     * Client-only GUI: in-game title screen button plus the Swing first-run
     * setup / floating "Add Mods" button as a fallback.
     */
    private void initializeClientGui() {
        Path gameDir = Paths.get(".").toAbsolutePath().normalize();

        try {
            TitleScreenButtonInjector.register();
            LOGGER.info("Title screen button registered");
        } catch (Exception e) {
            LOGGER.debug("Title screen button not available: {}", e.getMessage());
        }

        try {
            RetromodGui gui = new RetromodGui(gameDir);

            if (gui.isFirstRun()) {
                LOGGER.info("Opening the Retromod setup dialog for the first run");
                gui.showFirstRunDialog();
            } else {
                gui.showAddModsButton();
            }
        } catch (Exception e) {
            LOGGER.warn("Could not initialize GUI: {}", e.getMessage());
            LOGGER.info("Use CLI instead: retromod aot <mod.jar>");
        }
    }
    
    private void loadNeoForgeShims(RetromodTransformer transformer) {
        try {
            java.util.ServiceLoader<VersionShim> loader = 
                java.util.ServiceLoader.load(VersionShim.class);
            
            int count = 0;
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
                    if (RetromodVersion.mcVersionExceeds(
                            shim.getTargetVersion(), RetromodVersion.TARGET_MC_VERSION)) {
                        continue;
                    }
                    shim.registerRedirects(transformer);
                    count++;
                }
            }
            
            LOGGER.info("Loaded {} NeoForge/Forge version shims", count);
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
            Files.createDirectories(processedDir);

            if (!Files.isDirectory(inputDir)) return 0;

            List<Path> jars;
            try (var stream = Files.list(inputDir)) {
                jars = stream.filter(p -> p.toString().endsWith(".jar"))
                             .filter(Files::isRegularFile).toList();
            }

            if (jars.isEmpty()) return 0;

            ForgeModTransformer transformer = new ForgeModTransformer(RetromodVersion.TARGET_MC_VERSION);

            for (Path modJar : jars) {
                try {
                    String fileName = modJar.getFileName().toString();
                    LOGGER.info("Transforming from retromod-input/: {}", fileName);

                    Path transformed = transformer.transformMod(modJar, modsDir);
                    if (transformed != null) {
                        Files.move(modJar, processedDir.resolve(fileName),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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

            for (Path modJar : jars) {
                try {
                    var info = detector.detectVersion(modJar);
                    if (info != null && info.needsTransformation(RetromodVersion.TARGET_MC_VERSION)) {
                        String fileName = modJar.getFileName().toString();
                        LOGGER.info("Found incompatible mod in mods/: {} ({})", fileName, info.targetMcVersion());

                        Files.createDirectories(backupDir);
                        Files.copy(modJar, backupDir.resolve(fileName),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                        // Transform to temp, then replace.
                        Path tempDir = Files.createTempDirectory("retromod-inplace-");
                        Path transformed = transformer.transformMod(modJar, tempDir);
                        if (transformed != null) {
                            Files.move(transformed, modJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            LOGGER.info("Transformed in place: {}", fileName);
                            count++;
                        }
                        try (var walk = Files.walk(tempDir)) {
                            walk.sorted(java.util.Comparator.reverseOrder())
                                .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("Could not check: {}", modJar.getFileName());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error scanning mods/ for transformation: {}", e.getMessage());
        }
        return count;
    }

    /** Asks the player to restart after jars changed on disk. */
    private void showRestartPopup(int transformedCount) {
        if (!EnvironmentDetector.canShowGui()) return;

        Thread popupThread = new Thread(() -> {
            try {
                javax.swing.UIManager.setLookAndFeel(
                    javax.swing.UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            javax.swing.JOptionPane.showMessageDialog(null,
                "Retromod updated " + transformedCount + " mod(s).\n\n" +
                "Restart Minecraft to load the new jars. You should only need\n" +
                "this restart after Retromod changes a mod.",
                "Restart Minecraft",
                javax.swing.JOptionPane.INFORMATION_MESSAGE);
        }, "Retromod-RestartPopup");
        popupThread.setDaemon(true);
        popupThread.start();
    }

    /** Recommends the earlier offline path when startup had to update jars in place. */
    private void recommendAotFlow(int inPlaceCount) {
        LOGGER.info("Retromod updated {} mod(s) during NeoForge startup.", inPlaceCount);
        LOGGER.info("For future setup, `retromod prepare <minecraft-dir>` is more reliable because "
            + "it updates jars before NeoForge builds its module layer.");
    }

    private void scanForRuntimeTransformableMods() {
        try {
            Path modsFolder = Paths.get("mods");
            if (!Files.exists(modsFolder)) return;
            
            ModVersionDetector detector = new ModVersionDetector();
            java.io.File[] modFiles = modsFolder.toFile().listFiles(
                (dir, name) -> name.endsWith(".jar") && !name.contains("-retromod")
            );
            
            if (modFiles == null) return;
            
            for (java.io.File modFile : modFiles) {
                try {
                    var info = detector.detectVersion(modFile.toPath());
                    if (info != null && info.needsTransformation(RetromodVersion.TARGET_MC_VERSION)) {
                        String sourceVersion = info.targetMcVersion();

                        if (sourceVersion != null &&
                            "neoforge".equals(info.modLoaderType())) {
                            LOGGER.info("Runtime transforming: {} ({} -> {})",
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
