/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.core;

import com.retromod.gui.RetromodGui;
import com.retromod.gui.TitleScreenButtonInjector;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.ArchivePublication;
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

/**
 * Forge entry point. Runs on clients (GUI file picker, "Add Mods" button) and
 * dedicated servers (headless, console warnings).
 */
@net.minecraftforge.fml.common.Mod("retromod")
public class RetromodForge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    public RetromodForge() {
        RetromodVersion.logPresenceBanner(LOGGER);
        // Forge does not load the Fabric entry point that normally records this version.
        detectMcVersionForForge();

        LOGGER.info("Starting Retromod on Forge for Minecraft {}",
                RetromodVersion.TARGET_MC_VERSION);

        // First-time users need a real file they can edit.
        RetromodConfig.ensureDefaultConfig();

        publishForgeEnvironment();
        boolean isServer = EnvironmentDetector.isDedicatedServer();
        LOGGER.info("Running on a {}", isServer ? "dedicated server" : "client");

        try {
            com.retromod.resources.ResourceManager.processStagedPacks(
                    RetromodVersion.TARGET_MC_VERSION,
                    Paths.get(".").toAbsolutePath().normalize());
        } catch (Exception e) {
            LOGGER.warn("Could not initialize resource manager", e);
        }

        RetromodTransformer transformer = RetromodTransformer.getInstance();

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
                LOGGER.info("Registered {} cross-version SRG mapping(s)", srgEntries);
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
                LOGGER.info("Registered {} vanilla class move(s) for Forge", moves);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not register vanilla class moves", e);
        }

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
                    LOGGER.info("AutoFix: loaded {} saved fix(es) from previous launch", savedFixes);
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

        initializeHybridEngine();

        // On a 26.2+ client, prefer the OpenGL backend so translated old mods'
        // OpenGL rendering keeps working. No-op below 26.2 / on a server / if the
        // user already chose a backend.
        try {
            GraphicsBackendCompat.ensureOpenGlForOldMods(
                Paths.get(".").toAbsolutePath().normalize(), RetromodVersion.TARGET_MC_VERSION);
        } catch (Exception e) {
            LOGGER.debug("Graphics backend preference skipped: {}", e.getMessage());
        }

        int transformed = transformModsFromInput();
        transformed += transformModsInPlace();

        // Arm the in-game restart prompt (#33), shown on the title screen.
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

        // Scan for mods that can be runtime-transformed (minor versions)
        scanForRuntimeTransformableMods();

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

        LOGGER.info("Retromod initialized.");
        
        if (isServer) {
            LOGGER.info("Retromod is running in server mode.");
            LOGGER.info("Bytecode transforms and ahead-of-time compilation are enabled.");
            LOGGER.info("GUI features are unavailable on a headless server.");
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
     * Client-only GUI: the in-game title screen button plus a Swing fallback
     * (first-run setup or the floating "Add Mods" button).
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
    
    private void loadForgeShims(RetromodTransformer transformer) {
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
                    shim.registerRedirects(transformer);
                    count++;
                }
            }
            
            LOGGER.info("Loaded {} Forge version shims", count);
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

            for (Path modJar : jars) {
                try {
                    String fileName = modJar.getFileName().toString();
                    LOGGER.info("Transforming from retromod-input/: {}", fileName);

                    Path transformed = transformer.transformMod(modJar, modsDir);
                    if (transformed != null) {
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

            for (Path modJar : jars) {
                try {
                    var info = detector.detectVersion(modJar);
                    if (info != null && info.needsTransformation(RetromodVersion.TARGET_MC_VERSION)) {
                        String fileName = modJar.getFileName().toString();
                        LOGGER.info("Found incompatible mod in mods/: {} ({})", fileName, info.targetMcVersion());

                        Files.createDirectories(backupDir);
                        ArchivePublication.copyReplacing(
                                modJar, backupDir.resolve(fileName));

                        Path tempDir = Files.createTempDirectory("retromod-inplace-");
                        try {
                            Path transformed = transformer.transformMod(modJar, tempDir);
                            if (transformed != null) {
                                ArchivePublication.moveReplacing(transformed, modJar);
                                LOGGER.info("Transformed in place: {}", fileName);
                                count++;
                            }
                        } finally {
                            try (var walk = Files.walk(tempDir)) {
                                walk.sorted(java.util.Comparator.reverseOrder())
                                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                            }
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

    /** Tell the user (via a Swing dialog) to restart Minecraft. */
    private void showRestartPopup(int transformedCount) {
        if (!EnvironmentDetector.canShowGui()) return;

        Thread popupThread = new Thread(() -> {
            try {
                javax.swing.UIManager.setLookAndFeel(
                    javax.swing.UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            javax.swing.JOptionPane.showMessageDialog(null,
                "Retromod transformed " + transformedCount + " mod(s).\n\n" +
                "Please close Minecraft and launch it again\n" +
                "for the changes to take effect.\n\n" +
                "This only happens the first time.",
                "Retromod - Restart Required",
                javax.swing.JOptionPane.INFORMATION_MESSAGE);
        }, "Retromod-RestartPopup");
        popupThread.setDaemon(true);
        popupThread.start();
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

                        if (sourceVersion != null) {
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
