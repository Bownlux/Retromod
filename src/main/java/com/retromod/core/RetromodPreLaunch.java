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
import javax.swing.*;
import java.awt.*;
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
    private static List<String> skippedComplexMods = new ArrayList<>();

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
        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
        LOGGER.info("║  Retromod v1.3.0-snapshot.3                                ║");
        LOGGER.info("╚════════════════════════════════════════════════════════════╝");
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

            if (!skippedComplexMods.isEmpty()) {
                showComplexityWarning();
            }

            LOGGER.info("Retromod pre-launch complete!");
            
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
                    // This is the FABRIC transform path (pre-launch, before Fabric scans
                    // mods/): register ONLY Fabric and loader-agnostic ("common") shims, the
                    // same filter the other three entry points already apply
                    // (Retromod.onInitialize, RetromodForge, RetromodNeoForge). Without it,
                    // a NeoForge/Forge shim's MOJANG-named redirect fires on a Fabric mod:
                    // after the intermediary→Mojang harvest the mod's names match the shim's
                    // keys, so e.g. NeoForge_1_20_6_to_1_21's Enchantment.getMaxLevel redirect
                    // rewrote AER's anvil call to the nonexistent EnchantmentShim →
                    // NoClassDefFoundError the first time the anvil ran (#119). Fabric-owned
                    // shims that rewrite net/minecraft names into fabric-API targets are the
                    // converse hazard on Forge/NeoForge, already handled by their filters.
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
        try {
            Path readme = folder.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, """
                    ╔════════════════════════════════════════════════════════════╗
                    ║  RETROMOD INPUT FOLDER (PRIMARY)                           ║
                    ╠════════════════════════════════════════════════════════════╣
                    ║                                                            ║
                    ║  Put your OLD mods here!                                   ║
                    ║                                                            ║
                    ║  HOW TO USE:                                               ║
                    ║  1. Drop old .jar mod files into this folder               ║
                    ║  2. Start Minecraft                                        ║
                    ║  3. Retromod transforms them automatically                 ║
                    ║  4. ⚠️ RESTART Minecraft (required first time)             ║
                    ║  5. Your mods work!                                        ║
                    ║                                                            ║
                    ║  WHY RESTART?                                              ║
                    ║  Fabric scans mods BEFORE Retromod can run. Transformed    ║
                    ║  mods only load on the next launch. This is a Fabric       ║
                    ║  limitation, not a Retromod bug.                           ║
                    ║                                                            ║
                    ║  AFTER TRANSFORMATION:                                     ║
                    ║  - Originals move to: processed/                           ║
                    ║  - Transformed mods go to: mods/                           ║
                    ║                                                            ║
                    ║  ⚠️  Do NOT drop new mods in processed/!                   ║
                    ║  That subfolder is the AFTER-transform staging area.      ║
                    ║  Mods dropped there are treated as already-handled and    ║
                    ║  skipped on the next scan.                                ║
                    ║                                                            ║
                    ║  ALTERNATIVE LOCATION:                                     ║
                    ║  You can also use: mods/retromod-input/                    ║
                    ║  (Same rule - don't use its processed/ subfolder either.) ║
                    ║                                                            ║
                    ╚════════════════════════════════════════════════════════════╝
                    """);
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    private void createSecondaryReadme(Path folder) {
        try {
            Path readme = folder.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, """
                    ╔════════════════════════════════════════════════════════════╗
                    ║  RETROMOD INPUT FOLDER (SECONDARY)                         ║
                    ╠════════════════════════════════════════════════════════════╣
                    ║                                                            ║
                    ║  This folder works too! Put old mods here.                 ║
                    ║                                                            ║
                    ║  Both locations work:                                      ║
                    ║  • .minecraft/retromod-input/      (primary)               ║
                    ║  • .minecraft/mods/retromod-input/ (this folder)           ║
                    ║                                                            ║
                    ║  Same instructions:                                        ║
                    ║  1. Drop old .jar files here                               ║
                    ║  2. Start Minecraft                                        ║
                    ║  3. RESTART Minecraft                                      ║
                    ║  4. Mods work!                                             ║
                    ║                                                            ║
                    ║  ⚠️  Drop mods HERE, not in the processed/ subfolder.      ║
                    ║  processed/ is where originals go AFTER transformation -  ║
                    ║  mods dropped there are treated as already-handled and    ║
                    ║  skipped on the next scan.                                ║
                    ║                                                            ║
                    ╚════════════════════════════════════════════════════════════╝
                    """);
            }
        } catch (Exception e) {
            // Ignore
        }
    }
    
    private void createModsFolderGuide(Path modsFolder) {
        try {
            Path guide = modsFolder.resolve("!RETROMOD-READ-ME-FIRST!.txt");

            // overwrite each launch so instruction changes propagate
            Files.writeString(guide, """
                ╔════════════════════════════════════════════════════════════╗
                ║                                                            ║
                ║   ██████╗ ███████╗████████╗██████╗  ██████╗               ║
                ║   ██╔══██╗██╔════╝╚══██╔══╝██╔══██╗██╔═══██╗              ║
                ║   ██████╔╝█████╗     ██║   ██████╔╝██║   ██║              ║
                ║   ██╔══██╗██╔══╝     ██║   ██╔══██╗██║   ██║              ║
                ║   ██║  ██║███████╗   ██║   ██║  ██║╚██████╔╝              ║
                ║   ╚═╝  ╚═╝╚══════╝   ╚═╝   ╚═╝  ╚═╝ ╚═════╝               ║
                ║                                                            ║
                ║   MOD INSTALLATION GUIDE                                   ║
                ║                                                            ║
                ╠════════════════════════════════════════════════════════════╣
                ║                                                            ║
                ║   ⚠️  DO NOT PUT OLD MODS DIRECTLY IN THIS FOLDER!  ⚠️    ║
                ║                                                            ║
                ║   If you put old mods here, Minecraft will CRASH           ║
                ║                                                            ║
                ║                                                            ║
                ╠════════════════════════════════════════════════════════════╣
                ║                                                            ║
                ║   WHERE TO PUT OLD MODS:                                   ║
                ║                                                            ║
                ║   Option 1 (Recommended):                                  ║
                ║   .minecraft/retromod-input/                               ║
                ║                                                            ║
                ║   Option 2 (Also works):                                   ║
                ║   .minecraft/mods/retromod-input/                           ║
                ║      (There's a folder inside THIS mods folder!)           ║
                ║                                                            ║
                ║   IMPORTANT: drop mods directly in retromod-input/.       ║
                ║   Do NOT put them in the `processed/` subfolder! That's    ║
                ║   where Retromod moves originals AFTER it transforms       ║
                ║   them - mods dropped there are skipped and won't load.   ║
                ║                                                            ║
                ╠════════════════════════════════════════════════════════════╣
                ║                                                            ║
                ║   STEP BY STEP:                                            ║
                ║                                                            ║
                ║   1. Find the retromod-input folder                        ║
                ║      (either location above)                               ║
                ║                                                            ║
                ║   2. Put your OLD mod .jar files there                     ║
                ║      Example: sodium-1.20.4.jar                            ║
                ║                                                            ║
                ║   3. Start Minecraft                                       ║
                ║      Retromod will transform the mods                      ║
                ║                                                            ║
                ║   4. ⚠️ RESTART Minecraft ⚠️                               ║
                ║      (This is required! Transformed mods load on restart)  ║
                ║                                                            ║
                ║   5. Your old mods now work! 🎉                            ║
                ║                                                            ║
                ╠════════════════════════════════════════════════════════════╣
                ║                                                            ║
                ║   WHERE DO MODS GO AFTER TRANSFORMATION?                   ║
                ║                                                            ║
                ║   • Transformed mods appear in THIS folder (mods/)         ║
                ║     with "-retromod" added to the name                     ║
                ║     Example: sodium-1.20.4-retromod.jar                    ║
                ║                                                            ║
                ║   • Original mods move to retromod-input/processed/        ║
                ║     (as a backup)                                          ║
                ║                                                            ║
                ╠════════════════════════════════════════════════════════════╣
                ║                                                            ║
                ║   WHY IS RESTART REQUIRED?                                 ║
                ║                                                            ║
                ║   Fabric Loader scans this folder ONCE at startup.         ║
                ║   Retromod transforms mods, but Fabric already read        ║
                ║   the folder. On restart, Fabric sees the new mods.        ║
                ║                                                            ║
                ║   This is a Fabric limitation, not a Retromod bug.         ║
                ║                                                            ║
                ╚════════════════════════════════════════════════════════════╝
                
                Need help? Visit: github.com/Bownlux/Retromod/issues
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

            boolean forceComplex = isForceTranslateEnabled();

            for (Path modJar : modsToTransform) {
                try {
                    String fileName = modJar.getFileName().toString();
                    String modVersion = extractModMinecraftVersion(modJar);

                    LOGGER.info("┌─ Processing: {}", fileName);
                    LOGGER.info("│  Mod version: {}", modVersion != null ? modVersion : "unknown");
                    LOGGER.info("│  Target: {}", targetVersion);

                    // warn on complexity, but always transform
                    com.retromod.gui.ModComplexityAnalyzer analyzer =
                        new com.retromod.gui.ModComplexityAnalyzer();
                    com.retromod.gui.ModComplexityAnalyzer.ComplexityReport report =
                        analyzer.analyze(modJar);

                    if (report.isUnlikelyToWork()) {
                        LOGGER.warn("│  ⚠ High complexity (score: {}) - some features may not work", report.score());
                        LOGGER.warn("│  Reason: {}", report.reason());
                    }

                    // transform unless the version matches exactly
                    boolean needsTransform = !isExactVersionMatch(modVersion, targetVersion);

                    if (!needsTransform) {
                        LOGGER.info("│  Status: Already compatible (exact match)");
                        LOGGER.info("└─ Copying directly to mods/");
                        Files.copy(modJar, outputFolder.resolve(fileName),
                            StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        LOGGER.info("│  Status: Needs transformation");
                        Path transformed = transformer.transformMod(modJar, outputFolder);
                        if (transformed != null) {
                            LOGGER.info("└─ Created: {}", transformed.getFileName());
                            transformedMods.add(fileName);
                        } else {
                            LOGGER.warn("└─ Transformation failed!");
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

    /** Create the README in the CurseForge-export folder (mods/Retromod/), #78. */
    private void createCfExportReadme(Path folder) {
        try {
            Path readme = folder.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, """
                    RETROMOD - mods/Retromod/  (CurseForge-export folder, issue #78)
                    ===============================================================

                    Put LOADER-READY jars here: Retromod itself, and mods already
                    transformed for THIS Minecraft version (the *-retromod.jar files
                    produced by `retromod batch`).

                    WHY THIS FOLDER EXISTS:
                      CurseForge rejects modpack EXPORTS that contain jars not hosted
                      on CurseForge. Jars in this subfolder ship as pack "overrides"
                      (which CurseForge allows), and Retromod loads them anyway.

                    HOW THEY LOAD:
                      - NeoForge: loaded in-place at startup, no restart.
                      - Fabric:   Retromod MOVES these into mods/ on the next launch,
                                  then asks you to RESTART once (Fabric scans mods/
                                  before Retromod can run). To load in-place and skip
                                  the restart, add this JVM argument instead:
                                    -Dfabric.addMods=<.minecraft>/mods/Retromod

                    NOTE: RAW old mods that still need transforming do NOT go here -
                    put those in retromod-input/. This folder is for jars that are
                    already built for the current Minecraft version.
                    """);
            }
        } catch (Exception e) {
            // ignore: README is a convenience, not required
        }
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
    
    /**
     * Log the restart-required banner. The in-game screen is shown later by
     * Retromod.onInitialize() via InGameScreenFactory.
     */
    private void showRestartMessage() {
        LOGGER.info("");
        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
        LOGGER.info("║                                                            ║");
        LOGGER.info("║   RESTART REQUIRED!                                        ║");
        LOGGER.info("║                                                            ║");
        LOGGER.info("║   Retromod transformed {} mod(s):                          ║", String.format("%-2d", totalTransformed));
        for (String mod : transformedMods) {
            String display = mod.length() > 48 ? mod.substring(0, 45) + "..." : mod;
            LOGGER.info("║     - {}║", String.format("%-51s", display));
        }
        LOGGER.info("║                                                            ║");
        LOGGER.info("║   Close Minecraft and launch again for mods to load.       ║");
        LOGGER.info("║                                                            ║");
        LOGGER.info("║   This is normal! Fabric needs a restart to see new mods.  ║");
        LOGGER.info("║                                                            ║");
        LOGGER.info("╚════════════════════════════════════════════════════════════╝");
        LOGGER.info("");
    }

    /** Whether force_translate_complex is set in config. */
    private boolean isForceTranslateEnabled() {
        try {
            Path configPath = Path.of("config/retromod/config.json");
            if (Files.exists(configPath)) {
                String json = Files.readString(configPath);
                return json.contains("\"force_translate_complex\": true") ||
                       json.contains("\"force_translate_complex\":true");
            }
        } catch (Exception e) {
            // default to false
        }
        return false;
    }

    /** Warn (log + GUI) about mods skipped for high complexity. */
    private void showComplexityWarning() {
        LOGGER.warn("");
        LOGGER.warn("╔════════════════════════════════════════════════════════════╗");
        LOGGER.warn("║   MODS SKIPPED (UNLIKELY TO WORK)                          ║");
        LOGGER.warn("╠════════════════════════════════════════════════════════════╣");
        for (String mod : skippedComplexMods) {
            String display = mod.length() > 51 ? mod.substring(0, 48) + "..." : mod;
            LOGGER.warn("║   ⚠ {}║", String.format("%-54s", display));
        }
        LOGGER.warn("╠════════════════════════════════════════════════════════════╣");
        LOGGER.warn("║   These mods were NOT translated because they use          ║");
        LOGGER.warn("║   features Retromod cannot fully transform (coremods,      ║");
        LOGGER.warn("║   heavy reflection, ASM manipulation, etc.).               ║");
        LOGGER.warn("║                                                            ║");
        LOGGER.warn("║   To try anyway, set in config/retromod/config.json:       ║");
        LOGGER.warn("║     \"force_translate_complex\": true                       ║");
        LOGGER.warn("╚════════════════════════════════════════════════════════════╝");
        LOGGER.warn("");

        if (EnvironmentDetector.canShowGui()) {
            Thread warningThread = new Thread(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {}

                StringBuilder msg = new StringBuilder();
                msg.append("Retromod skipped ").append(skippedComplexMods.size())
                   .append(" mod(s) because they are unlikely to work:\n\n");
                for (String mod : skippedComplexMods) {
                    msg.append("  - ").append(mod).append("\n");
                }
                msg.append("\nThese mods use features that Retromod cannot fully\n");
                msg.append("transform (coremods, heavy reflection, ASM, etc.).\n\n");
                msg.append("To force translation anyway, set:\n");
                msg.append("  \"force_translate_complex\": true\n");
                msg.append("in config/retromod/config.json");

                JOptionPane.showMessageDialog(
                    null,
                    msg.toString(),
                    "Retromod - Mods Skipped",
                    JOptionPane.WARNING_MESSAGE
                );
            }, "Retromod-ComplexityWarning");
            warningThread.setDaemon(true);
            warningThread.start();
        }
    }
}
