/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import com.retromod.shim.ShimRegistry;
import com.retromod.embedder.ApiEmbedder;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.aot.AotCompiler;
import com.retromod.mixin.MixinCompatibilityTransformer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Fabric entry point. Transforms older mod bytecode, embeds removed APIs into
 * mod JARs, and registers shims for deleted functionality.
 */
public class Retromod implements ModInitializer {

    public static final String MOD_ID = "retromod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Runtime membership predicate: did Retromod transform this mod jar? Reads the
     * {@code Retromod-Transformed} manifest attribute stamped at transform time, so
     * runtime helpers never touch a native mod (pitfalls #14/#46). Loader-agnostic:
     * delegates to {@link ForgeModTransformer#isTransformedMod(Path)}.
     */
    public static boolean isTransformedMod(Path jar) {
        return ForgeModTransformer.isTransformedMod(jar);
    }

    // Auto-detected at runtime from the loader.
    public static String TARGET_MC_VERSION = "1.21.4";

    // Mirror to RetromodVersion so the Forge/NeoForge entry points can read the
    // version without linking Retromod (it implements Fabric's ModInitializer,
    // absent on those classpaths -> NoClassDefFoundError).
    static {
        try {
            String mcVersion = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
            if (mcVersion != null) {
                TARGET_MC_VERSION = mcVersion;
                RetromodVersion.TARGET_MC_VERSION = mcVersion;
            }
        } catch (Exception e) {
            // not Fabric; try NeoForge via reflection
            try {
                Class<?> fmlLoader = Class.forName("net.neoforged.fml.loading.FMLLoader");
                Object versionInfo = fmlLoader.getMethod("versionInfo").invoke(null);
                String mcVersion = (String) versionInfo.getClass().getMethod("mcVersion").invoke(versionInfo);
                if (mcVersion != null) {
                    TARGET_MC_VERSION = mcVersion;
                    RetromodVersion.TARGET_MC_VERSION = mcVersion;
                }
            } catch (Exception e2) {
                // not NeoForge; try Forge via reflection
                try {
                    Class<?> mcpVersion = Class.forName("net.minecraftforge.versions.mcp.MCPVersion");
                    String mcVersion = (String) mcpVersion.getMethod("getMCVersion").invoke(null);
                    if (mcVersion != null) {
                        TARGET_MC_VERSION = mcVersion;
                        RetromodVersion.TARGET_MC_VERSION = mcVersion;
                    }
                } catch (Exception e3) {
                    // keep the default
                }
            }
        }
    }

    // Mods built for these versions can be transformed.
    public static final String[] SUPPORTED_SOURCE_VERSIONS = {
        "1.14", "1.14.1", "1.14.2", "1.14.3", "1.14.4",
        "1.15", "1.15.1", "1.15.2",
        "1.16", "1.16.1", "1.16.2", "1.16.3", "1.16.4", "1.16.5",
        "1.17", "1.17.1",
        "1.18", "1.18.1", "1.18.2",
        "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4",
        "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
        "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4",
        "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10"
    };

    public static final Path BACKUP_FOLDER = Path.of("mods/retromod-backups");

    private static Retromod instance;
    private ShimRegistry shimRegistry;
    private ApiEmbedder apiEmbedder;
    private ModVersionDetector versionDetector;
    private AotCompiler aotCompiler;
    private MixinCompatibilityTransformer mixinTransformer;

    private boolean useAotCompilation = true;
    private boolean transformMixins = true;
    private boolean passNativeModsThrough = true; // mods already on the current version pass through untouched
    private boolean polyfillsEnabled = true;

    public static final String[] SUPPORTED_TARGET_VERSIONS = {
        "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5",
        "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
        // Minecraft stopped obfuscating production names in 26.1.
        "26.1", "26.1-pre.1", "26.1-pre.2", "26.1-pre-1", "26.1-pre-2",
        "26.1.0", "26.1.1", "26.1.2",
        "26.2", "26.2.0", "26.2.1"
    };
    
    @Override
    public void onInitialize() {
        instance = this;
        RetromodVersion.logPresenceBanner(LOGGER);
        LOGGER.info("Starting Retromod on Fabric for Minecraft {}", TARGET_MC_VERSION);

        EnvironmentDetector.logEnvironment();

        // Informational only; a modified or forked build still runs fine.
        com.retromod.security.SignatureVerifier.verifyAndLog();

        loadConfig();

        Path gameDir;
        try {
            gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
            if (gameDir == null) {
                gameDir = Path.of(".");
            }
            ensureRetromodFolders(gameDir);
        } catch (Exception e) {
            LOGGER.warn("Could not create Retromod folders", e);
            gameDir = Path.of(".");
        }

        // each component is isolated so one failure doesn't crash the mod
        try {
            com.retromod.legacy.LegacyVersionSupport legacySupport =
                new com.retromod.legacy.LegacyVersionSupport();
            legacySupport.logSupportStatus(gameDir.resolve("mods"));
        } catch (Exception e) {
            LOGGER.warn("Could not initialize legacy version support", e);
        }

        try {
            CrossModDependencyResolver dependencyResolver =
                new CrossModDependencyResolver(TARGET_MC_VERSION);
            dependencyResolver.scanMods(gameDir.resolve("mods"));
            dependencyResolver.logResolutionInfo();
        } catch (Exception e) {
            LOGGER.warn("Could not initialize dependency resolver", e);
        }

        try {
            com.retromod.resources.ResourceManager resourceManager =
                new com.retromod.resources.ResourceManager(TARGET_MC_VERSION, gameDir);
            resourceManager.ensureFolders();
            resourceManager.processAll();
        } catch (Exception e) {
            LOGGER.warn("Could not initialize resource manager", e);
        }

        try {
            shimRegistry = new ShimRegistry();
        } catch (Exception e) {
            LOGGER.warn("Could not initialize shim registry", e);
            shimRegistry = new ShimRegistry();
        }

        try {
            apiEmbedder = new ApiEmbedder();
        } catch (Exception e) {
            LOGGER.warn("Could not initialize API embedder", e);
        }

        versionDetector = new ModVersionDetector();

        try {
            registerShims();
        } catch (Exception e) {
            LOGGER.warn("Could not register shims", e);
        }

        try {
            registerPolyfills();
        } catch (Exception e) {
            LOGGER.warn("Could not register polyfills", e);
        }

        // Forge SRG -> Mojang member names. Loaded everywhere, not just Forge
        // runtimes, so the dictionary covers a Forge SRG mod fed through Fabric.
        try {
            int srgEntries = com.retromod.mapping.SrgToMojangMapper.getInstance()
                    .applyTo(RetromodTransformer.getInstance());
            if (srgEntries > 0) {
                LOGGER.info("Registered {} SRG → Mojang mapping(s)", srgEntries);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not register SRG mappings", e);
        }

        // Apply fixes the AutoFixEngine persisted from a prior launch, before
        // transformation. Gated on -Dretromod.autoFix so disabling AutoFix also
        // stops reloading auto-fixes.json (a mod could leave poisoned redirects).
        boolean autoFixEnabled = Boolean.parseBoolean(
                System.getProperty("retromod.autoFix", "false"));
        if (autoFixEnabled) {
            try {
                AutoFixEngine autoFixEngine = new AutoFixEngine();
                int savedFixes = autoFixEngine.loadAndApplySavedFixes(RetromodTransformer.getInstance());
                if (savedFixes > 0) {
                    LOGGER.info("AutoFix: loaded {} saved fix(es) from previous launch", savedFixes);
                }
            } catch (Exception e) {
                LOGGER.warn("Could not load auto-fix saved fixes", e);
            }
        }

        try {
            aotCompiler = new AotCompiler(shimRegistry, TARGET_MC_VERSION);
        } catch (Exception e) {
            LOGGER.warn("Could not initialize AOT compiler", e);
        }

        try {
            mixinTransformer = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());
        } catch (Exception e) {
            LOGGER.warn("Could not initialize Mixin transformer", e);
        }

        try {
            if (useAotCompilation && aotCompiler != null) {
                performAotCompilation();
            } else {
                scanAndPrepare();
            }
        } catch (Exception e) {
            LOGGER.warn("Could not scan/compile mods", e);
        }

        // Bridge old HUD callbacks to the new HudElementRegistry API (26.1+).
        try {
            com.retromod.shim.fabric.embedded.HudRenderCallbackShim.bridgeToNewApi();
        } catch (Throwable e) {
            // Throwable: IdentifierShim can throw ExceptionInInitializerError
            LOGGER.warn("Could not bridge HUD callbacks: {}", e.getMessage());
        }

        try {
            if (RetromodPreLaunch.hasPendingRestart()) {
                scheduleRestartScreen();
            }
        } catch (Exception e) {
            LOGGER.warn("Could not show restart screen: {}", e.getMessage());
        }

        // Scan the previous launch's latest.log for known crash patterns and
        // register redirects so the next retransformation picks them up. Opt-in:
        // latest.log is mod-writable, so a crafted line could register chosen
        // redirects. They're constrained to real MC methods (fuzzy resolver needs
        // a >=85 match against the indexed JAR), so not RCE, but one mod could
        // mis-route another's rewrites. Each redirect is logged at WARN.
        if (autoFixEnabled) {
            try {
                Path logFile = gameDir.resolve("logs/latest.log");
                if (java.nio.file.Files.exists(logFile)) {
                    AutoFixEngine autoFixEngine = new AutoFixEngine();
                    List<AutoFixEngine.AppliedFix> fixes =
                        autoFixEngine.analyzeAndFix(logFile, RetromodTransformer.getInstance());
                    if (!fixes.isEmpty()) {
                        LOGGER.warn("AutoFix: registered {} redirect(s) from previous log (opt-in feature). "
                                + "Review each one - log lines are an attacker-writable surface:",
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
            LOGGER.debug("AutoFix disabled by default. Enable with -Dretromod.autoFix=true "
                    + "(see security notes in Retromod.java).");
        }

        LOGGER.info("Retromod is ready with {} method redirects and {} class redirects",
                RetromodTransformer.getInstance().getMethodRedirectCount(),
                RetromodTransformer.getInstance().getClassRedirectCount());
    }

    // Log-only; no in-game screen (Fabric's screen API churns across versions).
    private void scheduleRestartScreen() {
        List<String> mods = RetromodPreLaunch.getTransformedMods();
        if (!mods.isEmpty()) {
            LOGGER.info("Updated {} {}. Restart Minecraft to load {}.",
                    mods.size(), mods.size() == 1 ? "mod" : "mods",
                    mods.size() == 1 ? "it" : "them");
        }
    }

    private void ensureRetromodFolders(Path gameDir) {
        try {
            Path inputFolder = gameDir.resolve("retromod-input");
            if (!java.nio.file.Files.exists(inputFolder)) {
                java.nio.file.Files.createDirectories(inputFolder);
                LOGGER.info("Created retromod-input/ folder");

                Path readme = inputFolder.resolve("README.txt");
                java.nio.file.Files.writeString(readme, getReadmeContent());
            }

            Path processedFolder = inputFolder.resolve("processed");
            if (!java.nio.file.Files.exists(processedFolder)) {
                java.nio.file.Files.createDirectories(processedFolder);
            }

            Path backupFolder = gameDir.resolve("retromod-backups");
            if (!java.nio.file.Files.exists(backupFolder)) {
                java.nio.file.Files.createDirectories(backupFolder);
            }

            ModHealthChecker.ensureFoldersExist(gameDir);

        } catch (Exception e) {
            LOGGER.warn("Could not create folders: {}", e.getMessage());
        }
    }

    private String getReadmeContent() {
        return """
            Retromod input folder

            Put old mod jars directly in this folder. Retromod will update them
            for this Minecraft version, install the results in mods/, and move
            the originals to processed/.

            Mods already made for this Minecraft version can go straight in mods/.

            Help and bug reports:
            https://github.com/Bownlux/Retromod/issues
            """;
    }
    
    /**
     * Whether a mod already targets the current MC version, so it passes
     * through untransformed.
     */
    public boolean isNativeVersionMod(String modMinecraftVersion) {
        if (modMinecraftVersion == null) return false;

        String cleanVersion = modMinecraftVersion
            .replace(">=", "")
            .replace("<=", "")
            .replace(">", "")
            .replace("<", "")
            .replace("~", "")
            .replace("^", "")
            .trim();

        if (cleanVersion.equals(TARGET_MC_VERSION)) {
            return true;
        }

        // a range that spans the current version
        if (modMinecraftVersion.contains(TARGET_MC_VERSION)) {
            return true;
        }

        // wildcards like "1.21.x" / "1.21.*"
        if (cleanVersion.endsWith(".x") || cleanVersion.endsWith(".*")) {
            String base = cleanVersion.substring(0, cleanVersion.length() - 2);
            if (TARGET_MC_VERSION.startsWith(base)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Loads config from JSON, generating the default if absent. Delegates to
     * {@link RetromodConfig} so every loader entry point writes the same default.
     */
    private void loadConfig() {
        var config = RetromodConfig.loadOrNull();
        if (config == null) return;
        try {
            if (config.has("use_aot")) useAotCompilation = config.get("use_aot").getAsBoolean();
            if (config.has("transform_mixins")) transformMixins = config.get("transform_mixins").getAsBoolean();
            if (config.has("polyfills_enabled")) polyfillsEnabled = config.get("polyfills_enabled").getAsBoolean();
            LOGGER.info("Loaded config from {}", RetromodConfig.CONFIG_PATH);
        } catch (Exception e) {
            LOGGER.warn("Could not apply config, using defaults", e);
        }
    }
    
    // AOT-compiles every mod, backing up originals first.
    private void performAotCompilation() {
        Path modsFolder = Path.of("mods");
        if (!modsFolder.toFile().exists()) {
            return;
        }

        createBackupFolder();

        LOGGER.info("Preparing legacy mods ahead of launch");
        LOGGER.info("Original mod backups will be kept in {}", BACKUP_FOLDER);

        List<AotCompiler.AotResult> results = aotCompiler.compileAllModsSync(
            modsFolder,
            (current, total, name) -> {
                LOGGER.info("Preparing mod {} of {}: {}", current, total, name);
            }
        );

        int success = 0, cached = 0, skipped = 0, failed = 0;
        for (var result : results) {
            switch (result.status()) {
                case SUCCESS -> success++;
                case CACHED -> cached++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }

        LOGGER.info("AOT compilation complete: {} compiled, {} cached, {} skipped, {} failed",
                success, cached, skipped, failed);
    }

    private void createBackupFolder() {
        try {
            if (!java.nio.file.Files.exists(BACKUP_FOLDER)) {
                java.nio.file.Files.createDirectories(BACKUP_FOLDER);
                LOGGER.info("Created backup folder: {}", BACKUP_FOLDER);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to create backup folder", e);
        }
    }
    
    /** Backs up a mod JAR before transformation. Returns false on failure. */
    public boolean backupMod(Path modFile) {
        try {
            createBackupFolder();

            String fileName = modFile.getFileName().toString();
            Path backupPath = BACKUP_FOLDER.resolve(fileName);

            // timestamp the name if a backup already exists
            if (java.nio.file.Files.exists(backupPath)) {
                String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                String extension = fileName.substring(fileName.lastIndexOf('.'));
                backupPath = BACKUP_FOLDER.resolve(baseName + "-" + timestamp + extension);
            }

            java.nio.file.Files.copy(modFile, backupPath);
            LOGGER.info("Backed up {} to {}", fileName, backupPath);
            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to backup mod: {}", modFile, e);
            return false;
        }
    }

    /** Restores a mod from backup. Returns false on failure. */
    public boolean restoreMod(String modName) {
        try {
            if (!isSafeModFileName(modName)) {
                LOGGER.error("Refusing unsafe backup name: {}", modName);
                return false;
            }
            Path backupPath = BACKUP_FOLDER.resolve(modName);
            // prefer the Fabric game dir over CWD (Retromod may run from a
            // different working directory, e.g. CLI mode)
            Path modsFolder;
            try {
                modsFolder = net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getGameDir().resolve("mods");
            } catch (Throwable t) {
                modsFolder = Path.of("mods");
            }
            Path targetPath = modsFolder.resolve(modName);

            if (!java.nio.file.Files.exists(backupPath)) {
                LOGGER.error("No backup found for: {}", modName);
                return false;
            }

            java.nio.file.Files.createDirectories(modsFolder);
            Path staged = java.nio.file.Files.createTempFile(modsFolder,
                    ".retromod-restore-", ".tmp");
            try {
                java.nio.file.Files.copy(backupPath, staged,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                moveReplacing(staged, targetPath);
            } finally {
                java.nio.file.Files.deleteIfExists(staged);
            }

            // drop any -retromod variant too
            String baseName = modName.substring(0, modName.lastIndexOf('.'));
            try (var stream = java.nio.file.Files.list(modsFolder)) {
                stream.filter(p -> p.getFileName().toString().startsWith(baseName + "-retromod"))
                    .forEach(p -> {
                        try { java.nio.file.Files.delete(p); }
                        catch (Exception e) { /* ignore */ }
                    });
            }

            LOGGER.info("Restored {} from backup", modName);
            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to restore mod: {}", modName, e);
            return false;
        }
    }

    static boolean isSafeModFileName(String modName) {
        if (modName == null || modName.isBlank() || !modName.endsWith(".jar")) return false;
        if (modName.contains("/") || modName.contains("\\")) return false;
        Path name = Path.of(modName);
        return name.getNameCount() == 1 && !".".equals(modName) && !"..".equals(modName);
    }

    private static void moveReplacing(Path source, Path target) throws java.io.IOException {
        try {
            java.nio.file.Files.move(source, target,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(source, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
    
    /**
     * Registers Fabric and loader-agnostic ("common") shims. Forge/NeoForge
     * shims are skipped here so their redirects don't leak into the shared
     * RetromodTransformer map and corrupt Fabric mods; those load via their own
     * entry points.
     */
    private void registerShims() {
        ServiceLoader<VersionShim> shims = ServiceLoader.load(VersionShim.class);

        int loaded = 0;
        int skippedNonFabric = 0;
        // iterate defensively: lite builds exclude some shim classes
        java.util.Iterator<VersionShim> it = shims.iterator();
        while (it.hasNext()) {
            VersionShim shim;
            try {
                shim = it.next();
            } catch (java.util.ServiceConfigurationError e) {
                LOGGER.debug("Skipping unavailable shim: {}", e.getMessage());
                continue;
            }

            String loaderType = shim.getModLoaderType();
            if (!"fabric".equals(loaderType) && !"common".equals(loaderType)) {
                skippedNonFabric++;
                continue;
            }

            // Pitfall #9: never register a shim whose target exceeds the host. The other entry
            // points had this; the Fabric onInitialize path didn't, so e.g. the 26.2 dyed-field
            // accessors would rewrite a 1.21.11 host's live Blocks.WHITE_WOOL reads to a
            // nonexistent ColorCollection (review finding).
            if (RetromodVersion.mcVersionExceeds(shim.getTargetVersion(), TARGET_MC_VERSION)) {
                LOGGER.debug("Skipping shim {} (target {} exceeds host {})",
                        shim.getShimName(), shim.getTargetVersion(), TARGET_MC_VERSION);
                continue;
            }

            LOGGER.debug("Loading shim: {} ({} -> {})",
                    shim.getShimName(), shim.getSourceVersion(), shim.getTargetVersion());
            shim.registerRedirects(RetromodTransformer.getInstance());
            shimRegistry.register(shim);
            loaded++;
        }
        LOGGER.info("Loaded {} Fabric/common shims (skipped {} non-Fabric shims)",
                loaded, skippedNonFabric);

        registerBuiltInShims();
    }

    private void registerBuiltInShims() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();

        // examples of the two redirect shapes (rename vs. removed-to-shim)
        transformer.registerMethodRedirect(
            "net/minecraft/entity/Entity", "getWorld", "()Lnet/minecraft/world/World;",
            "net/minecraft/entity/Entity", "getEntityWorld", "()Lnet/minecraft/world/World;"
        );

        transformer.registerMethodRedirect(
            "net/minecraft/entity/Entity", "method_removed_example", "()V",
            "com/retromod/shim/embedded/EntityShim", "method_removed_example", "(Lnet/minecraft/entity/Entity;)V"
        );

        transformer.registerMethodRedirect(
            "net/fabricmc/loader/api/FabricLoader", "getModContainer",
            "(Ljava/lang/String;)Ljava/util/Optional;",
            "com/retromod/shim/fabric/FabricLoaderShim", "getModContainer",
            "(Ljava/lang/String;)Ljava/util/Optional;"
        );

        // OpenGL -> Vulkan / Metal
        try {
            com.retromod.shim.api.fabric.RenderingBackendShim renderShim =
                new com.retromod.shim.api.fabric.RenderingBackendShim();
            renderShim.registerRedirects(transformer);
            shimRegistry.register(renderShim);
        } catch (Exception e) {
            LOGGER.warn("Could not register rendering backend shim", e);
        }

        LOGGER.info("Registered built-in shims for 1.21.x compatibility");
    }

    // Stub classes for removed APIs, to head off ClassNotFoundException and
    // mixin hierarchy failures.
    private void registerPolyfills() {
        com.retromod.polyfill.PolyfillRegistry polyfillRegistry =
            new com.retromod.polyfill.PolyfillRegistry();
        polyfillRegistry.setEnabled(polyfillsEnabled);
        polyfillRegistry.loadAndRegister(RetromodTransformer.getInstance());
    }

    private void scanAndPrepare() {
        Path modsFolder = Path.of("mods");
        if (!modsFolder.toFile().exists()) {
            return;
        }

        File[] modFiles = modsFolder.toFile().listFiles((dir, name) ->
                name.endsWith(".jar") && !name.contains("-retromod"));

        if (modFiles == null) return;

        for (File modFile : modFiles) {
            try {
                ModVersionInfo info = versionDetector.detectVersion(modFile.toPath());

                if (info != null && info.needsTransformation(TARGET_MC_VERSION)) {
                    LOGGER.info("Found legacy mod: {} (built for {}, current: {})",
                            info.modId(), info.targetMcVersion(), TARGET_MC_VERSION);

                    for (String pkg : info.modPackages()) {
                        RetromodTransformer.getInstance().addTransformablePackage(pkg);
                    }

                    if (info.usesRemovedApis()) {
                        LOGGER.info("Mod {} uses removed APIs - embedding shims", info.modId());
                        apiEmbedder.embedRequiredShims(modFile.toPath(), info);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to analyze mod: {}", modFile.getName(), e);
            }
        }
    }
    
    public static Retromod getInstance() {
        return instance;
    }
    
    public ShimRegistry getShimRegistry() {
        return shimRegistry;
    }
    
    public ApiEmbedder getApiEmbedder() {
        return apiEmbedder;
    }
}
