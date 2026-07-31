/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.core;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.retromod.util.ZipSecurity;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.ArrayList;
import java.util.ServiceLoader;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.regex.*;

/**
 * Fabric pre-launch entry point. Runs before Fabric scans mods/, so transformed
 * mods only appear on the next launch (hence the restart prompt).
 *
 * <p>Old mods go in either {@code .minecraft/retromod-input/} (primary) or
 * {@code .minecraft/mods/retromod-input/}; a guide in mods/ points users to them.
 */
public class RetromodPreLaunch implements PreLaunchEntrypoint {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    
    private static final String PRIMARY_INPUT = "retromod-input";
    private static final String SECONDARY_INPUT = "mods/retromod-input";
    private static final String PROCESSED_SUFFIX = "/processed";

    // CurseForge-export folder (#78): loader-ready jars (Retromod + already-transformed
    // mods shipped as CF pack overrides), not raw old mods. NeoForge loads it in-place
    // via RetromodModLocator; Fabric has no locator SPI, so we drain it into mods/.
    private static final String CF_EXPORT_FOLDER = "mods/Retromod";

    private static int totalTransformed = 0;
    private static List<String> transformedMods = new ArrayList<>();

    /** Whether mods were transformed this launch (for the in-game restart screen). */
    public static boolean hasPendingRestart() {
        return totalTransformed > 0;
    }

    /** Filenames of mods transformed this launch. */
    public static List<String> getTransformedMods() {
        return List.copyOf(transformedMods);
    }

    /** Number of mods transformed this launch. */
    public static int getTotalTransformed() {
        return totalTransformed;
    }
    
    @Override
    public void onPreLaunch() {
        LOGGER.info("Starting Retromod {} on Fabric", RetromodVersion.RETROMOD_VERSION);
        RetromodVersion.logPresenceBanner(LOGGER);
        
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            if (gameDir == null) {
                LOGGER.warn("Could not determine game directory, using current directory");
                gameDir = Path.of(".");
            }
            String targetVersion = getMinecraftVersion();

            LOGGER.info("Target Minecraft version: {}", targetVersion);

            // Publish the host before shims register: the API bridges gate on
            // RetromodVersion.isUnobfuscatedTarget(TARGET_MC_VERSION), which would
            // otherwise still hold its compile-time default during prelaunch (#9).
            RetromodVersion.TARGET_MC_VERSION = targetVersion;

            // Register shims before transforming so redirects are available. Pass the
            // host so 26.1-only transforms aren't applied to pre-26.1 hosts, where the
            // Fabric runtime still uses intermediary names (#21).
            registerShimsForTransform(targetVersion);

            // Auto-fix redirects mined from a prior launch's crash logs. Loaded after
            // shims (so shim redirects win) but before transformation (so they apply).
            try {
                AutoFixEngine autoFixEngine = new AutoFixEngine();
                int savedFixes = autoFixEngine.loadAndApplySavedFixes(
                    RetromodTransformer.getInstance());
                if (savedFixes > 0) {
                    LOGGER.info("AutoFix: loaded {} saved fix(es) from previous launch", savedFixes);
                }
            } catch (Exception e) {
                LOGGER.debug("Could not load auto-fix saved fixes: {}", e.getMessage());
            }

            createFoldersAndGuides(gameDir);

            // On a 26.2+ client, prefer the still-present OpenGL backend so translated
            // old mods keep rendering (26.2 made Vulkan the default). No-op below 26.2,
            // on a server, or when the user picked a backend.
            try {
                GraphicsBackendCompat.ensureOpenGlForOldMods(gameDir, targetVersion);
            } catch (Exception e) {
                LOGGER.debug("Graphics backend preference skipped: {}", e.getMessage());
            }

            int fromPrimary = transformModsFromFolder(
                gameDir.resolve(PRIMARY_INPUT),
                gameDir.resolve(PRIMARY_INPUT + PROCESSED_SUFFIX),
                gameDir.resolve("mods"),
                targetVersion
            );
            
            int fromSecondary = transformModsFromFolder(
                gameDir.resolve(SECONDARY_INPUT),
                gameDir.resolve(SECONDARY_INPUT + PROCESSED_SUFFIX),
                gameDir.resolve("mods"),
                targetVersion
            );

            // Drain the CF-export folder into mods/. Skip when -Dfabric.addMods already
            // points here: Fabric loaded those jars in-place this launch, so moving them
            // would double-handle loaded files.
            int fromCfFolder;
            Path cfFolder = gameDir.resolve(CF_EXPORT_FOLDER);
            if (fabricAddModsCovers(gameDir, cfFolder)) {
                LOGGER.info("mods/Retromod/ is on -Dfabric.addMods - Fabric loaded it in-place "
                    + "this launch; skipping drain (#78 option 2, no restart needed)");
                fromCfFolder = 0;
            } else {
                fromCfFolder = drainReadyModsFolder(cfFolder, gameDir.resolve("mods"));
            }

            totalTransformed = fromPrimary + fromSecondary + fromCfFolder;

            if (totalTransformed > 0) {
                showRestartMessage();
                com.retromod.gui.RestartPrompt.markPending(totalTransformed);
            }

            LOGGER.info("Retromod finished its Fabric pre-launch work");
            
        } catch (Exception e) {
            LOGGER.error("Retromod pre-launch error: {}", e.getMessage());
        }
    }
    
    // Version math lives on the loader-agnostic RetromodVersion so the NeoForge/Forge
    // entry points can use it without pulling in this Fabric class (#40). These
    // delegates keep the Fabric call sites and tests pointing here.

    static boolean isUnobfuscatedTarget(String hostVersion) {
        return RetromodVersion.isUnobfuscatedTarget(hostVersion);
    }

    static boolean mcVersionExceeds(String a, String b) {
        return RetromodVersion.mcVersionExceeds(a, b);
    }

    static int compareMcVersions(String a, String b) {
        return RetromodVersion.compareMcVersions(a, b);
    }

    /**
     * Register all version shims and polyfills so the bytecode transformer has
     * redirects available before transformation runs.
     *
     * @param hostVersion the running MC version; gates 26.1-only remapping
     */
    private void registerShimsForTransform(String hostVersion) {
        try {
            RetromodTransformer transformer = RetromodTransformer.getInstance();

            ServiceLoader<VersionShim> shims = ServiceLoader.load(VersionShim.class);
            int shimCount = 0;
            int skippedNewer = 0;
            int skippedOtherLoader = 0;
            java.util.Iterator<VersionShim> shimIt = shims.iterator();
            while (shimIt.hasNext()) {
                VersionShim shim;
                try {
                    shim = shimIt.next();
                } catch (java.util.ServiceConfigurationError e) {
                    // missing class, expected in lite builds
                    continue;
                }
                try {
                    // Fabric transform path: register only "fabric" and "common" shims,
                    // the same filter the other three entry points apply. Without it a
                    // NeoForge/Forge shim's Mojang-named redirect fires on a Fabric mod
                    // after the intermediary→Mojang harvest. #119: NeoForge_1_20_6_to_1_21's
                    // Enchantment.getMaxLevel redirect rewrote AER's anvil call to the
                    // nonexistent EnchantmentShim, NoClassDefFoundError on first anvil use.
                    // The converse hazard (Fabric shims on Forge/NeoForge) is handled by
                    // their filters.
                    String loaderType = shim.getModLoaderType();
                    if (!"fabric".equals(loaderType) && !"common".equals(loaderType)) {
                        skippedOtherLoader++;
                        continue;
                    }
                    // Only register shims whose target version is <= the host MC. A shim
                    // X→Y rewrites references to Y-version names; if Y is newer than the
                    // host those names don't exist at runtime and the shim breaks the mod.
                    // Fabric API names match between mod and runtime, so unlike the
                    // intermediary remap this bites on Fabric too (#31/#32/#35).
                    if (mcVersionExceeds(shim.getTargetVersion(), hostVersion)) {
                        skippedNewer++;
                        continue;
                    }
                    shim.registerRedirects(transformer);
                    shimCount++;
                } catch (Exception e) {
                    LOGGER.debug("Could not register shim: {}", e.getMessage());
                }
            }
            LOGGER.info("Registered {} version shims for transformation "
                    + "({} skipped as newer than host MC {}, {} skipped as non-Fabric)",
                shimCount, skippedNewer, hostVersion, skippedOtherLoader);

            // Pre-26.1 hosts only: these bridges work in the intermediary namespace,
            // so on a Mojang-named 26.x runtime they'd fail to load (e.g. the model
            // bridge's `extends class_630`). The intermediary→Mojang remap is gated
            // off here, so without them changed/removed APIs go unbridged (#55).
            if (!isUnobfuscatedTarget(hostVersion)) {
                // ModelPart self-construction (new class_630 + addBox/texOffs) removed in
                // the 1.17 model rewrite; names survive but signatures/owners changed, so
                // the name-keyed shims can't fix it.
                try {
                    com.retromod.shim.fabric.Pre1_17ModelBridge.register(transformer);
                    LOGGER.info("Registered pre-1.17 entity-model bridge (ModelPart construction layer)");
                } catch (Exception e) {
                    LOGGER.warn("Could not register pre-1.17 model bridge: {}", e.getMessage());
                }
                // 1.19 removed the TranslatableText/LiteralText constructors (Text factories
                // replaced them); host-introspecting, so it no-ops on <=1.18.x hosts.
                try {
                    com.retromod.shim.fabric.Pre1_19TextBridge.register(transformer);
                } catch (Exception e) {
                    LOGGER.warn("Could not register pre-1.19 Text bridge: {}", e.getMessage());
                }

                // Entity.onGround went non-public in the 1.17 access cleanup; rewrite direct
                // field access to the isOnGround/setOnGround accessors. Host-introspecting,
                // so it no-ops on 1.16.x hosts where the field is still public.
                try {
                    com.retromod.shim.fabric.Pre1_17EntityFieldBridge.register(transformer);
                } catch (Exception e) {
                    LOGGER.warn("Could not register pre-1.17 Entity field bridge: {}",
                            e.getMessage());
                }

                // 1.20 deleted the Material system (class_3614); retype every reference to
                // the shipped MaterialPolyfill so pre-1.20 classes still load. Host-probing,
                // so it no-ops on hosts that still have Material.
                try {
                    com.retromod.shim.fabric.Pre1_20MaterialBridge.register(transformer);
                } catch (Exception e) {
                    LOGGER.warn("Could not register pre-1.20 Material bridge: {}",
                            e.getMessage());
                }

                // class_1269 became a sealed interface in 1.21.2, breaking pre-1.21.2 mods
                // that read its static fields. Probes the host, so it no-ops where the
                // legacy shape is intact.
                try {
                    com.retromod.shim.fabric.Pre1_21_2InteractionResultBridge.register(transformer);
                } catch (Exception e) {
                    LOGGER.warn("Could not register pre-1.21.2 InteractionResult bridge: {}", e.getMessage());
                }

                // EntityType$Builder.build(String) -> build(ResourceKey) (the 1.21.2 descriptor
                // flip, #162): a 1.20.1-1.21.1 mod registering an entity dies NoSuchMethodError
                // at <clinit> on a 1.21.2-1.21.11 host. Probes the host builder's method_5905
                // shape, so it no-ops where the String form still exists.
                try {
                    com.retromod.shim.fabric.Pre1_21_2EntityTypeBuildBridge.register(transformer);
                } catch (Exception e) {
                    LOGGER.warn("Could not register pre-1.21.2 EntityType build bridge: {}", e.getMessage());
                }

                // Identifier (String)/(String,String) ctors removed in 1.20.5 for static
                // parse/fromNamespaceAndPath factories. RegistryPolyfill handles the Mojang
                // variants, but pre-26.1 Fabric keeps intermediary names (class_2960); this
                // discovers the host's factory names so it spans the version range.
                try {
                    com.retromod.shim.fabric.Pre1_20_5IdentifierCtorBridge.register(transformer);
                } catch (Exception e) {
                    LOGGER.warn("Could not register pre-1.20.5 Identifier ctor bridge: {}", e.getMessage());
                }

                // class_1959$class_1961 (Biome.Category) deleted in 1.18.2 (categories →
                // tags). A synthetic enum + redirect + getCategory rewrite lets pre-1.18.2
                // spawn helpers load instead of dying in <clinit>.
                try {
                    com.retromod.shim.fabric.Pre1_18_2BiomeCategoryBridge.register(transformer);
                } catch (Exception e) {
                    LOGGER.warn("Could not register pre-1.18.2 Biome.Category bridge: {}", e.getMessage());
                }
            }

            // 26.1+ only: MC 26.1 dropped obfuscation. Before it the Fabric runtime
            // exposes MC under intermediary names, and a pre-26.1 mod already references
            // those, so remapping to Mojang names would rewrite working references into
            // 26.1 names absent at runtime → ClassNotFoundException (#21).
            try {
                com.retromod.mapping.IntermediaryToMojangMapper mapper =
                    com.retromod.mapping.IntermediaryToMojangMapper.getInstance();
                if (!mapper.isLoaded()) {
                    LOGGER.warn("IntermediaryToMojangMapper not loaded - bytecode class remapping disabled");
                } else if (!isUnobfuscatedTarget(hostVersion)) {
                    LOGGER.info("Host MC {} is pre-26.1 (Fabric runtime uses intermediary "
                        + "names) - skipping intermediary→Mojang remap and 26.1 class moves "
                        + "so mods keep their working names (#21/#29)", hostVersion);
                } else {
                    // ASM ClassRemapper is single-pass, so compose intermediary→Mojang with
                    // class moves to reach intermediary→final-26.1 in one step: otherwise
                    // class_4064→CycleOption stops there and CycleOption→OptionInstance never
                    // fires (the bytecode held class_4064, not CycleOption).
                    java.util.Map<String, String> classMoveMap = mapper.getClassMoves();

                    int classRedirects = 0;
                    int composed = 0;
                    for (java.util.Map.Entry<String, String> entry : mapper.getClassMap().entrySet()) {
                        String intermediary = entry.getKey();
                        String mojang = entry.getValue();
                        // if the Mojang target was itself moved in 26.1, point at the final name
                        String finalName = classMoveMap.getOrDefault(mojang, mojang);
                        if (!finalName.equals(mojang)) composed++;
                        transformer.registerClassRedirect(intermediary, finalName);
                        classRedirects++;
                    }
                    transformer.registerIntermediaryNameMappings(
                        mapper.getMethodMap(), mapper.getFieldMap());
                    // class moves direct, for mods already on Mojang names (e.g. Jade on GuiGraphics)
                    int classMoves = 0;
                    for (java.util.Map.Entry<String, String> entry : classMoveMap.entrySet()) {
                        transformer.registerClassRedirect(entry.getKey(), entry.getValue());
                        classMoves++;
                    }
                    LOGGER.info("Composed {}/{} intermediary mappings with class moves", composed, classRedirects);
                    // 26.1 ctor→factory redirects: ResourceLocation(String) → Identifier.parse, etc.
                    com.retromod.mapping.IntermediaryToMojangMapper.registerIdentifierCtorRedirects(transformer);
                    LOGGER.info("Registered {} intermediary→Mojang class redirects + {} class moves + 2 constructor redirects",
                        classRedirects, classMoves);
                }
            } catch (Exception e) {
                LOGGER.warn("Could not register intermediary→Mojang mappings: {}", e.getMessage());
            }

            try {
                ServiceLoader<com.retromod.polyfill.PolyfillProvider> polyfills =
                    ServiceLoader.load(com.retromod.polyfill.PolyfillProvider.class);
                int polyfillCount = 0;
                java.util.Iterator<com.retromod.polyfill.PolyfillProvider> polyfillIt = polyfills.iterator();
                while (polyfillIt.hasNext()) {
                    com.retromod.polyfill.PolyfillProvider provider;
                    try {
                        provider = polyfillIt.next();
                    } catch (java.util.ServiceConfigurationError e) {
                        // missing class, expected in lite builds
                        continue;
                    }
                    try {
                        provider.registerPolyfills(transformer);
                        polyfillCount++;
                    } catch (Exception e) {
                        LOGGER.debug("Could not register polyfill: {}", e.getMessage());
                    }
                }
                if (polyfillCount > 0) {
                    LOGGER.info("Registered {} polyfill providers for transformation", polyfillCount);
                }
            } catch (Exception e) {
                LOGGER.debug("No polyfill providers found");
            }
            // Fallback for unresolved references; detects the MC jar from the
            // classpath (Fabric Loader always has it loaded).
            try {
                transformer.initFuzzyResolver(null);
            } catch (Exception e) {
                LOGGER.debug("Could not initialize fuzzy resolver: {}", e.getMessage());
            }
        } catch (Exception e) {
            LOGGER.warn("Could not register shims for pre-launch transform: {}", e.getMessage());
        }
    }

    private String getMinecraftVersion() {
        try {
            return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("26.1");
        } catch (Exception e) {
            return "26.1";
        }
    }
    
    private void createFoldersAndGuides(Path gameDir) {
        try {
            Path primaryInput = gameDir.resolve(PRIMARY_INPUT);
            Path primaryProcessed = gameDir.resolve(PRIMARY_INPUT + PROCESSED_SUFFIX);
            Files.createDirectories(primaryInput);
            Files.createDirectories(primaryProcessed);

            Path secondaryInput = gameDir.resolve(SECONDARY_INPUT);
            Path secondaryProcessed = gameDir.resolve(SECONDARY_INPUT + PROCESSED_SUFFIX);
            Files.createDirectories(secondaryInput);
            Files.createDirectories(secondaryProcessed);

            createPrimaryReadme(primaryInput);
            createSecondaryReadme(secondaryInput);

            Path cfFolder = gameDir.resolve(CF_EXPORT_FOLDER);
            Files.createDirectories(cfFolder);
            createCfExportReadme(cfFolder);

            // guide in mods/ points users at the input folders
            createModsFolderGuide(gameDir.resolve("mods"));

            LOGGER.info("Created retromod-input/ folders");
            
        } catch (Exception e) {
            LOGGER.error("Could not create folders: {}", e.getMessage());
        }
    }
    
    private void createPrimaryReadme(Path folder) {
        writeReadmeIfMissing(folder, inputFolderReadme());
    }

    private void createSecondaryReadme(Path folder) {
        writeReadmeIfMissing(folder, inputFolderReadme());
    }

    private String inputFolderReadme() {
        return """
            Retromod input folder

            Put old mod jars directly in this folder, then start Minecraft.
            Retromod will update them and ask you to restart so Fabric can load
            the new jars.

            Updated jars go to mods/. Originals are kept in processed/.
            Do not put new mods inside processed/, because Retromod skips it.

            You can use either of these input folders:
              retromod-input/
              mods/retromod-input/
            """;
    }

    private void writeReadmeIfMissing(Path folder, String content) {
        try {
            Path readme = folder.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, content);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not write {}: {}", folder.resolve("README.txt"), e.getMessage());
        }
    }
    
    private void createModsFolderGuide(Path modsFolder) {
        try {
            Path guide = modsFolder.resolve("!RETROMOD-READ-ME-FIRST!.txt");

            // Refresh this file so installation instructions stay current.
            Files.writeString(guide, """
                Retromod on Fabric

                Keep Retromod and mods made for this Minecraft version here.

                Put older mods directly in one of these folders instead:
                  ../retromod-input/
                  retromod-input/

                Start Minecraft after adding them, then restart when Retromod asks.
                Fabric scans mods before Retromod runs, so the updated jars can only
                load on the next launch.

                Retromod keeps the originals in retromod-input/processed/.

                Help: https://github.com/Bownlux/Retromod/issues
                """);
            
        } catch (Exception e) {
            LOGGER.debug("Could not create guide file: {}", e.getMessage());
        }
    }
    
    /** Transform all mods from one input folder. */
    private int transformModsFromFolder(Path inputFolder, Path processedFolder,
                                        Path outputFolder, String targetVersion) {
        if (!Files.exists(inputFolder)) {
            return 0;
        }

        // reject symlinked dirs (symlink-attack guard)
        try {
            ZipSecurity.validateNotSymlink(inputFolder);
            ZipSecurity.validateNotSymlink(outputFolder);
        } catch (java.io.IOException e) {
            LOGGER.error("Security check failed: {}", e.getMessage());
            return 0;
        }

        int count = 0;

        try {
            List<Path> modsToTransform;
            try (var stream = Files.list(inputFolder)) {
                modsToTransform = stream
                    .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                    .filter(p -> Files.isRegularFile(p))
                    .toList();
            }

            if (modsToTransform.isEmpty()) {
                return 0;
            }

            LOGGER.info("Found {} mod(s) in {}", modsToTransform.size(), inputFolder.getFileName());

            Files.createDirectories(processedFolder);
            Files.createDirectories(outputFolder);

            FabricModTransformer transformer = new FabricModTransformer(targetVersion);

            for (Path modJar : modsToTransform) {
                try {
                    String fileName = modJar.getFileName().toString();
                    String modVersion = extractModMinecraftVersion(modJar);

                    LOGGER.info("Checking {} (source {}, target {})",
                        fileName, modVersion != null ? modVersion : "unknown", targetVersion);

                    // Complexity is advisory here. Staged mods are transformed unless
                    // they already target the host exactly.
                    com.retromod.gui.ModComplexityAnalyzer analyzer =
                        new com.retromod.gui.ModComplexityAnalyzer();
                    com.retromod.gui.ModComplexityAnalyzer.ComplexityReport report =
                        analyzer.analyze(modJar);

                    if (report.isUnlikelyToWork()) {
                        LOGGER.warn("{} has a high compatibility risk ({}/100): {}",
                            fileName, report.score(), report.reason());
                    }

                    boolean needsTransform = !isExactVersionMatch(modVersion, targetVersion);

                    if (!needsTransform) {
                        LOGGER.info("{} already targets Minecraft {}; copying it unchanged",
                            fileName, targetVersion);
                        Files.copy(modJar, outputFolder.resolve(fileName),
                            StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Path transformed = transformer.transformMod(modJar, outputFolder);
                        if (transformed != null) {
                            LOGGER.info("Updated {} as {}", fileName, transformed.getFileName());
                            transformedMods.add(fileName);
                        } else {
                            LOGGER.warn("Retromod could not update {}", fileName);
                        }
                    }
                    
                    // move original to processed
                    Path processedPath = processedFolder.resolve(fileName);
                    Files.move(modJar, processedPath, StandardCopyOption.REPLACE_EXISTING);

                    count++;

                } catch (Exception e) {
                    LOGGER.error("Failed to process {}: {}", modJar.getFileName(), e.getMessage());
                }
            }
            
        } catch (Exception e) {
            LOGGER.error("Error scanning {}: {}", inputFolder, e.getMessage());
        }

        return count;
    }

    /**
     * Drain the CurseForge-export folder (mods/Retromod/) into mods/ (#78, Fabric).
     *
     * <p>The Fabric counterpart to NeoForge's {@code RetromodModLocator}. NeoForge
     * loads mods/Retromod/ in-place through a loader SPI; Fabric has none and
     * {@code PreLaunch} runs after mod discovery, so we move these jars into mods/
     * and let the next launch scan them (hence the one-time restart).
     *
     * <p>Unlike {@link #transformModsFromFolder}, jars here are already loader-ready
     * (Retromod itself, or mods pre-built for this MC via {@code retromod batch}),
     * so they're moved verbatim, never re-transformed.
     *
     * @return number of jars moved (a non-zero count arms the restart prompt)
     */
    static int drainReadyModsFolder(Path folder, Path modsFolder) {
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        // reject symlinked dirs (symlink-attack guard), same as the input folders
        try {
            ZipSecurity.validateNotSymlink(folder);
            ZipSecurity.validateNotSymlink(modsFolder);
        } catch (java.io.IOException e) {
            LOGGER.error("Security check failed for {}: {}", folder, e.getMessage());
            return 0;
        }

        List<Path> jars;
        try (var stream = Files.list(folder)) {
            jars = stream
                .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                .filter(Files::isRegularFile)
                .sorted()
                .toList();
        } catch (Exception e) {
            LOGGER.error("Could not list {}: {}", folder, e.getMessage());
            return 0;
        }
        if (jars.isEmpty()) {
            return 0;
        }

        LOGGER.info("Found {} ready jar(s) in mods/Retromod/ - moving into mods/ (CF-export folder, #78)",
            jars.size());
        LOGGER.info("  (tip: launch with -Dfabric.addMods=<gamedir>/mods/Retromod to load them "
            + "in-place and skip this restart)");

        int moved = 0;
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            try {
                Files.move(jar, modsFolder.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                transformedMods.add(name);
                moved++;
                LOGGER.info("  moved {} → mods/", name);
            } catch (Exception e) {
                LOGGER.error("  could not move {}: {}", name, e.getMessage());
            }
        }
        return moved;
    }

    /**
     * True if the JVM was launched with {@code -Dfabric.addMods} pointing at
     * {@code folder}. Fabric already discovered those jars in-place this launch,
     * so {@link #drainReadyModsFolder} must not also move them.
     *
     * <p>{@code fabric.addMods} is a {@link java.io.File#pathSeparator}-separated
     * list of paths; entries may be relative (resolved against the game dir).
     */
    static boolean fabricAddModsCovers(Path gameDir, Path folder) {
        String prop = System.getProperty("fabric.addMods");
        if (prop == null || prop.isBlank()) {
            return false;
        }
        Path target;
        try {
            target = folder.toAbsolutePath().normalize();
        } catch (Exception e) {
            return false;
        }
        for (String entry : prop.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path p = Path.of(entry.trim());
                if (!p.isAbsolute()) {
                    p = gameDir.resolve(p);
                }
                if (p.toAbsolutePath().normalize().equals(target)) {
                    return true;
                }
            } catch (Exception ignored) {
                // malformed entry, ignore
            }
        }
        return false;
    }

    /** Explains which jars belong in the CurseForge export folder. */
    private void createCfExportReadme(Path folder) {
        writeReadmeIfMissing(folder, """
            Retromod CurseForge export folder

            This folder is for Retromod and jars that are already ready for the
            current Minecraft version. CurseForge includes them as pack overrides.

            Put old, unconverted mods in retromod-input/ instead.

            On Fabric, Retromod moves these jars into mods/ and asks for one restart.
            Pack authors can load them in place with:
              -Dfabric.addMods=<game-directory>/mods/Retromod
            """);
    }

    /** True only when the mod version matches the target exactly. */
    private boolean isExactVersionMatch(String modVersion, String targetVersion) {
        if (modVersion == null) return false;

        String clean = modVersion
            .replace(">=", "")
            .replace("<=", "")
            .replace(">", "")
            .replace("<", "")
            .replace("~", "")
            .replace("^", "")
            .trim();

        return clean.equals(targetVersion);
    }

    /** Read the mod's declared Minecraft version from its metadata. */
    private String extractModMinecraftVersion(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            // Fabric
            ZipEntry fabricJson = jar.getEntry("fabric.mod.json");
            if (fabricJson != null) {
                String content;
                try (InputStream in = jar.getInputStream(fabricJson)) {
                    content = new String(in.readAllBytes());
                }
                Pattern p = Pattern.compile("\"minecraft\"\\s*:\\s*\"([^\"]+)\"");
                Matcher m = p.matcher(content);
                if (m.find()) return m.group(1);
            }

            // Quilt
            ZipEntry quiltJson = jar.getEntry("quilt.mod.json");
            if (quiltJson != null) {
                String content;
                try (InputStream in = jar.getInputStream(quiltJson)) {
                    content = new String(in.readAllBytes());
                }
                Pattern p = Pattern.compile("\"minecraft\"\\s*:\\s*\"([^\"]+)\"");
                Matcher m = p.matcher(content);
                if (m.find()) return m.group(1);
            }

            // Forge/NeoForge
            ZipEntry modsToml = jar.getEntry("META-INF/mods.toml");
            if (modsToml == null) modsToml = jar.getEntry("META-INF/neoforge.mods.toml");
            if (modsToml != null) {
                String content;
                try (InputStream in = jar.getInputStream(modsToml)) {
                    content = new String(in.readAllBytes());
                }
                Pattern p = Pattern.compile("versionRange\\s*=\\s*\"\\[([0-9.]+)");
                Matcher m = p.matcher(content);
                if (m.find()) return m.group(1);
            }
            
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
    
    /** Logs the same restart request shown later in game. */
    private void showRestartMessage() {
        LOGGER.info("Retromod updated {} mod(s). Restart Minecraft to load them:",
            totalTransformed);
        for (String mod : transformedMods) {
            LOGGER.info("  - {}", mod);
        }
        LOGGER.info("Fabric scans mods before Retromod runs, so this one restart is expected.");
    }
}
