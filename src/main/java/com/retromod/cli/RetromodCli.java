/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.cli;

import com.retromod.core.*;
import com.retromod.embedder.*;
import com.retromod.aot.AotCompiler;
import com.retromod.archive.ApiArchiveManager;
import com.retromod.gui.ModComplexityAnalyzer;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.fabric.*;
import com.retromod.shim.neoforge.*;
import com.retromod.shim.forge.*;
import com.retromod.legacy.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Standalone command-line tool for processing mods. Run with no args (or {@code help}) for the
 * full command list.
 */
public class RetromodCli {
    
    private static final String VERSION = "1.3.0-snapshot.3";
    // Each command can override this with --target.
    private static String TARGET_MC_VERSION = "26.1";
    
    private static ShimRegistry shimRegistry;
    private static ModVersionDetector detector;
    private static ApiArchiveManager archiveManager;
    
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        
        shimRegistry = new ShimRegistry();
        detector = new ModVersionDetector();
        archiveManager = new ApiArchiveManager();
        registerAllShims();

        try {
            ModHealthChecker.ensureFoldersExist(Path.of("."));
        } catch (Exception e) {
            // Commands that do not write game folders can still run.
        }

        String command = args[0].toLowerCase();

        // Version-gated helpers read the shared target, even in CLI mode.
        com.retromod.core.RetromodVersion.TARGET_MC_VERSION = TARGET_MC_VERSION;

        for (int i = 1; i < args.length - 1; i++) {
            if ("--target".equals(args[i])) {
                String v = args[i + 1].trim();
                if (!v.isEmpty()) {
                    TARGET_MC_VERSION = v;
                    com.retromod.core.RetromodVersion.TARGET_MC_VERSION = v;
                    System.out.println("Target Minecraft version: " + TARGET_MC_VERSION);
                }
                break;
            }
        }

        // Offline Forge migration needs an explicit target because no loader is running.
        for (int i = 1; i < args.length - 1; i++) {
            if ("--target-loader".equals(args[i])) {
                String v = args[i + 1].trim();
                if ("neoforge".equalsIgnoreCase(v)) {
                    com.retromod.util.McReflect.setForceNeoForge(true);
                    System.out.println("Target loader: NeoForge");
                }
                break;
            }
        }

        try {
            switch (command) {
                case "analyze" -> analyzeCommand(args);
                case "transform" -> transformCommand(args);
                case "aot" -> aotCommand(args);
                case "embed" -> embedCommand(args);
                case "batch" -> batchCommand(args);
                case "diff" -> diffCommand(args);
                case "archive" -> archiveCommand(args);
                case "shims" -> shimsCommand(args);
                case "legacy" -> legacyCommand(args);
                case "overrides" -> overridesCommand(args);
                case "prepare" -> prepareCommand(args);
                case "score" -> scoreCommand(args);
                case "devhelp", "migrate" -> devhelpCommand(args);
                case "autofix" -> autofixCommand(args);
                case "gaps" -> gapsCommand(args);
                case "mixin-scan" -> mixinScanCommand(args);
                case "help", "-h", "--help" -> printUsage();
                case "version", "-v", "--version" -> 
                    System.out.println("Retromod CLI v" + VERSION + " (Target: MC " + TARGET_MC_VERSION + ")");
                default -> {
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.err.println("Retromod could not finish: " + e.getMessage());
            if (System.getenv("RETROMOD_DEBUG") != null) {
                e.printStackTrace();
            }
            System.exit(1);
        } finally {
            archiveManager.shutdown();
        }
    }
    
    private static void registerAllShims() {
        // This helper recognizes the old annotation shape and leaves newer mods alone.
        try {
            com.retromod.shim.forge.Forge1122LifecycleSynthetics.register(
                    RetromodTransformer.getInstance());
        } catch (Exception ignored) {
        }

        // Fabric shims: complete 1.14.4 to 26.1 chain
        shimRegistry.register(new Fabric_1_14_4_to_1_15_2());
        shimRegistry.register(new Fabric_1_15_2_to_1_16_5());
        shimRegistry.register(new Fabric_1_16_5_to_1_17());
        shimRegistry.register(new Fabric_1_17_to_1_17_1());
        shimRegistry.register(new Fabric_1_17_1_to_1_18());
        shimRegistry.register(new Fabric_1_18_to_1_18_1());
        shimRegistry.register(new Fabric_1_18_1_to_1_18_2());
        shimRegistry.register(new Fabric_1_18_2_to_1_19());
        shimRegistry.register(new Fabric_1_19_to_1_19_1());
        shimRegistry.register(new Fabric_1_19_1_to_1_19_2());
        shimRegistry.register(new Fabric_1_19_2_to_1_19_3());
        shimRegistry.register(new Fabric_1_19_3_to_1_19_4());
        shimRegistry.register(new Fabric_1_19_4_to_1_20());
        shimRegistry.register(new Fabric_1_20_to_1_20_1());
        shimRegistry.register(new Fabric_1_20_1_to_1_20_2());
        shimRegistry.register(new Fabric_1_20_2_to_1_20_3());
        shimRegistry.register(new Fabric_1_20_3_to_1_20_4());
        shimRegistry.register(new Fabric_1_20_4_to_1_20_5());
        shimRegistry.register(new Fabric_1_20_5_to_1_20_6());
        shimRegistry.register(new Fabric_1_20_6_to_1_21());
        shimRegistry.register(new Fabric_1_21_to_1_21_1());
        shimRegistry.register(new Fabric_1_21_1_to_1_21_2());
        shimRegistry.register(new Fabric_1_21_2_to_1_21_3());
        shimRegistry.register(new Fabric_1_21_3_to_1_21_4());
        shimRegistry.register(new Fabric_1_21_4_to_1_21_5());
        shimRegistry.register(new Fabric_1_21_5_to_1_21_6());
        shimRegistry.register(new Fabric_1_21_6_to_1_21_7());
        shimRegistry.register(new Fabric_1_21_7_to_1_21_8());
        shimRegistry.register(new Fabric_1_21_8_to_1_21_9());
        shimRegistry.register(new Fabric_1_21_9_to_1_21_10());
        shimRegistry.register(new Fabric_1_21_10_to_1_21_11());
        shimRegistry.register(new Fabric_1_21_11_to_26_1());

        // NeoForge shims
        shimRegistry.register(new NeoForge_1_21_to_1_21_1());
        shimRegistry.register(new NeoForge_1_21_1_to_1_21_2());
        shimRegistry.register(new NeoForge_1_21_2_to_1_21_3());
        shimRegistry.register(new NeoForge_1_21_3_to_1_21_4());
        shimRegistry.register(new NeoForge_1_21_4_to_1_21_5());
        shimRegistry.register(new NeoForge_1_21_5_to_1_21_6());
        shimRegistry.register(new NeoForge_1_21_6_to_1_21_7());
        shimRegistry.register(new NeoForge_1_21_7_to_1_21_8());
        shimRegistry.register(new NeoForge_1_21_8_to_1_21_9());
        shimRegistry.register(new NeoForge_1_21_9_to_1_21_10());
        shimRegistry.register(new NeoForge_1_21_10_to_1_21_11());
        
        // Forge shims: complete 1.21 to 26.1 chain (step by step)
        shimRegistry.register(new Forge_1_21_to_1_21_1());
        shimRegistry.register(new Forge_1_21_1_to_1_21_2());
        shimRegistry.register(new Forge_1_21_2_to_1_21_3());
        shimRegistry.register(new Forge_1_21_3_to_1_21_4());
        shimRegistry.register(new Forge_1_21_4_to_1_21_5());
        shimRegistry.register(new Forge_1_21_5_to_1_21_6());
        shimRegistry.register(new Forge_1_21_6_to_1_21_7());
        shimRegistry.register(new Forge_1_21_7_to_1_21_8());
        shimRegistry.register(new Forge_1_21_8_to_1_21_9());
        shimRegistry.register(new Forge_1_21_9_to_1_21_10());
        shimRegistry.register(new Forge_1_21_10_to_1_21_11());
        
        // Forge shims: legacy Forge to NeoForge transition
        shimRegistry.register(new Forge_1_20_to_NeoForge_1_21());

        // Pick up the service-loaded API shims; the block above only covers version-jump
        // shims. Dedupe by class so a shim doesn't double-fire.
        java.util.Set<Class<?>> already = new java.util.HashSet<>();
        for (VersionShim s : shimRegistry.getAllShims()) already.add(s.getClass());
        for (VersionShim s : java.util.ServiceLoader.load(VersionShim.class)) {
            if (already.add(s.getClass())) {
                shimRegistry.register(s);
            }
        }
    }
    
    private static void analyzeCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: analyze <mod.jar>");
            System.exit(1);
        }
        
        Path modPath = Path.of(args[1]);
        if (!Files.exists(modPath)) {
            System.err.println("File not found: " + modPath);
            System.exit(1);
        }
        
        System.out.println("Retromod analysis");
        System.out.println("File: " + modPath.getFileName());
        System.out.println("Size: " + Files.size(modPath) / 1024 + " KB");
        
        ModVersionInfo info = detector.detectVersion(modPath);
        
        if (info == null) {
            System.out.println();
            System.out.println("Retromod could not read supported mod metadata from this jar.");
            System.out.println("Check that it is a Fabric, NeoForge, or Forge mod.");
            return;
        }
        
        printSection("Mod");
        System.out.println("ID: " + info.modId());
        System.out.println("Version: " + info.modVersion());
        System.out.println("Built for Minecraft: " + info.targetMcVersion());
        System.out.println("Loader: " + info.modLoaderType());
        System.out.println("Loader version: "
            + (info.modLoaderVersion() != null ? info.modLoaderVersion() : "not declared"));

        printSection("Packages (" + info.modPackages().size() + ")");
        int pkgCount = 0;
        for (String pkg : info.modPackages()) {
            if (pkgCount++ < 10) {
                System.out.println("  " + pkg.replace('/', '.'));
            }
        }
        if (info.modPackages().size() > 10) {
            System.out.println("  and " + (info.modPackages().size() - 10) + " more");
        }

        printSection("Compatibility");
        
        if (info.needsTransformation(TARGET_MC_VERSION)) {
            System.out.println("This mod needs changes for Minecraft " + TARGET_MC_VERSION + ".");
            
            List<VersionShim> chain = shimRegistry.findShimChain(
                info.modLoaderType(),
                info.targetMcVersion(),
                TARGET_MC_VERSION
            );
            
            if (chain.isEmpty()) {
                System.out.println("Retromod does not have a complete version path for this combination.");
            } else {
                System.out.println("Version path:");
                for (VersionShim shim : chain) {
                    System.out.println("  " + shim.getShimName());
                }
            }
        } else {
            System.out.println("No version change is needed.");
        }

        ModComplexityAnalyzer.ComplexityReport complexityReport =
            new ModComplexityAnalyzer().analyze(modPath);

        printSection("Compatibility risk");
        System.out.println("Score: " + complexityReport.score() + "/100");
        System.out.println(complexityReport.isUnlikelyToWork()
            ? "Retromod expects this mod to need manual work."
            : "This mod looks like a reasonable transform candidate.");
        printRiskFactors(complexityReport.riskFactors());
        if (complexityReport.isUnlikelyToWork()) {
            System.out.println("To test it anyway: retromod aot --force " + modPath.getFileName());
        }
    }

    private static void printSection(String title) {
        System.out.println();
        System.out.println(title);
    }

    private static void printRiskFactors(List<String> riskFactors) {
        if (riskFactors.isEmpty()) {
            return;
        }
        System.out.println("Reasons:");
        for (String factor : riskFactors) {
            System.out.println("  - " + factor);
        }
    }

    /** Prepares a mod with the AOT compiler. */
    private static void aotCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: aot [--force] <mod.jar> [--output <output.jar>]");
            System.exit(1);
        }

        boolean forceTranslate = false;
        Path modPath = null;
        Path outputPath = null;

        for (int i = 1; i < args.length; i++) {
            if ("--force".equals(args[i])) {
                forceTranslate = true;
            } else if ("--output".equals(args[i]) && i + 1 < args.length) {
                outputPath = Path.of(args[++i]);
            } else if (modPath == null) {
                modPath = Path.of(args[i]);
            }
        }

        if (modPath == null) {
            System.err.println("Usage: aot [--force] <mod.jar> [--output <output.jar>]");
            System.exit(1);
        }

        System.out.println("Preparing " + modPath.getFileName());

        ModComplexityAnalyzer.ComplexityReport complexityReport =
            new ModComplexityAnalyzer().analyze(modPath);

        if (complexityReport.isUnlikelyToWork() && !forceTranslate) {
            System.out.println();
            System.out.println("Retromod skipped this mod because its compatibility risk is high.");
            System.out.println("Score: " + complexityReport.score() + "/100");
            printRiskFactors(complexityReport.riskFactors());
            System.out.println("To test it anyway: retromod aot --force " + modPath.getFileName());
            return;
        }

        if (complexityReport.isUnlikelyToWork() && forceTranslate) {
            System.out.println("Trying the mod despite its risk score of "
                + complexityReport.score() + "/100.");
        }

        AotCompiler compiler = new AotCompiler(shimRegistry, TARGET_MC_VERSION);

        // These bridges must be registered before compilation so their rewritten
        // references can be embedded in the output jar.
        try {
            com.retromod.shim.forge.ForgeNeoForgeSynthetics.registerAll(
                    RetromodTransformer.getInstance());
        } catch (Exception e) {
            // Other AOT repairs can still produce a useful result.
        }

        long startTime = System.currentTimeMillis();
        Path result = compiler.compileModAot(modPath);
        long duration = System.currentTimeMillis() - startTime;

        // Only the generated jar may receive embedded classes.
        if (!result.equals(modPath)) {
            try {
                com.retromod.core.SyntheticEmbedder.embedIntoJar(
                        result, modPath.getFileName().toString(), RetromodTransformer.getInstance());
            } catch (Exception e) {
                // The transformed classes are still usable without optional bridges.
            }
        }

        System.out.println("Output: " + result.getFileName());
        System.out.println("Time: " + duration + " ms");
        
        if (result.equals(modPath)) {
            System.out.println("No new jar was needed.");
        } else {
            System.out.println("The prepared jar includes transformed bytecode and any required compatibility classes.");
        }
    }
    
    /** Transform a mod using JIT-style transformation (writes to a new JAR). */
    private static void transformCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: transform <mod.jar> [--output <output.jar>]");
            System.exit(1);
        }
        
        Path modPath = Path.of(args[1]);
        Path outputPath = modPath.resolveSibling(
            modPath.getFileName().toString().replace(".jar", "-transformed.jar")
        );
        
        for (int i = 2; i < args.length; i++) {
            if ("--output".equals(args[i]) && i + 1 < args.length) {
                outputPath = Path.of(args[++i]);
            }
        }
        
        System.out.println("Updating " + modPath.getFileName() + " for Minecraft " + TARGET_MC_VERSION);
        System.out.println("Output: " + outputPath.getFileName());
        
        ModVersionInfo info = detector.detectVersion(modPath);
        if (info == null) {
            System.err.println("Retromod could not read supported mod metadata from this jar.");
            System.exit(1);
        }

        String sourceMcVersion = info.targetMcVersion();
        if (sourceMcVersion == null || sourceMcVersion.isEmpty()) {
            System.err.println("Retromod could not determine the source Minecraft version.");
            System.err.println("It will try every shim that applies to the target.");
            RetromodTransformer transformer = RetromodTransformer.getInstance();
            registerAllShimsGated(transformer);
            // Offline output needs the same removed API replacements as a normal launch.
            new com.retromod.polyfill.PolyfillRegistry().loadAndRegister(transformer);
            register26xTargetMappings(transformer, info);
            transformJar(modPath, outputPath, transformer, info);
            com.retromod.core.SyntheticEmbedder.embedIntoJar(
                    outputPath, modPath.getFileName().toString(), transformer);
            System.out.println("Updated: " + outputPath);
            verifyIfRequested(outputPath, modPath.getFileName().toString(), args);
            return;
        }

        List<VersionShim> chain = shimRegistry.findShimChain(
            info.modLoaderType(),
            sourceMcVersion,
            TARGET_MC_VERSION
        );

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        for (VersionShim shim : chain) {
            System.out.println("Applying: " + shim.getShimName());
            shim.registerRedirects(transformer);
        }

        // Offline output needs the same removed API replacements as a normal launch.
        new com.retromod.polyfill.PolyfillRegistry().loadAndRegister(transformer);

        // API shims have their own versions and sit outside the Minecraft version path.
        java.util.Set<VersionShim> chainSet = new java.util.HashSet<>(chain);
        int apiApplied = 0;
        for (VersionShim shim : shimRegistry.getAllShims()) {
            if (chainSet.contains(shim)) continue;
            String loader = shim.getModLoaderType();
            if (loader != null && !"any".equalsIgnoreCase(loader)
                    && !loader.equalsIgnoreCase(info.modLoaderType())) continue;
            String pkg = shim.getClass().getName();
            if (!pkg.startsWith("com.retromod.shim.api.")) continue;
            try {
                shim.registerRedirects(transformer);
                apiApplied++;
            } catch (Exception e) {
                // One optional API shim should not block the others.
            }
        }

        int classMovesApplied = register26xTargetMappings(transformer, info);

        if (chain.isEmpty() && apiApplied == 0 && classMovesApplied == 0) {
            System.out.println("No applicable changes were found.");
            return;
        }
        if (apiApplied > 0) {
            System.out.println("Applied " + apiApplied + " API shim(s).");
        }
        if (classMovesApplied > 0) {
            System.out.println("Applied " + classMovesApplied + " vanilla 26.1 class move(s).");
        }

        transformJar(modPath, outputPath, transformer, info);
        com.retromod.core.SyntheticEmbedder.embedIntoJar(
                outputPath, modPath.getFileName().toString(), transformer);
        System.out.println("Updated: " + outputPath);
        verifyIfRequested(outputPath, modPath.getFileName().toString(), args);
    }

    /** Registers every shim whose target is not newer than the requested host. */
    static void registerAllShimsGated(RetromodTransformer transformer) {
        if (shimRegistry == null) {
            // Tests can call this helper without running main().
            shimRegistry = new ShimRegistry();
            registerAllShims();
        }
        for (VersionShim shim : shimRegistry.getAllShims()) {
            if (com.retromod.core.RetromodVersion.mcVersionExceeds(
                    shim.getTargetVersion(), TARGET_MC_VERSION)) {
                continue;
            }
            shim.registerRedirects(transformer);
        }
    }

    /** Registers the extra mappings and bridges required by unobfuscated hosts. */
    static int register26xTargetMappings(RetromodTransformer transformer, ModVersionInfo info) {
        int classMovesApplied = 0;
        if (com.retromod.core.RetromodVersion.isUnobfuscatedTarget(TARGET_MC_VERSION)) {
            try {
                var moves = com.retromod.mapping.IntermediaryToMojangMapper
                        .getInstance().getClassMoves();
                for (var e : moves.entrySet()) {
                    transformer.registerClassRedirect(e.getKey(), e.getValue());
                    classMovesApplied++;
                }
            } catch (Exception e) {
                // Other registered changes can still produce a useful result.
            }
            // A missing graph path must not skip the shared 26.1 adaptations.
            try {
                com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.register(transformer);
            } catch (Exception e) {
                // The name remap and class moves above still apply.
            }
            // Offline 26.2 output needs the core moves even without a graph edge.
            if (!com.retromod.core.RetromodVersion.mcVersionExceeds("26.2", TARGET_MC_VERSION)) {
                try {
                    com.retromod.shim.common.Mc26_1To26_2CoreMoves.register(transformer);
                } catch (Exception e) {
                    // The 26.1 adaptations above still apply.
                }
            }
            // Keep constructor handling consistent with a normal game launch.
            com.retromod.mapping.IntermediaryToMojangMapper.registerIdentifierCtorRedirects(transformer);
            // Only distributed Fabric mods carry intermediary member names.
            if ("fabric".equalsIgnoreCase(info.modLoaderType())) {
                try {
                    int memberMappings = com.retromod.mapping.IntermediaryToMojangMapper.applyTo(transformer);
                    if (memberMappings > 0) {
                        System.out.println("Applied intermediary->Mojang member mappings ("
                                + memberMappings + ").");
                    }
                } catch (Exception e) {
                    // Class moves above still apply.
                }
            }
            // A cross-loader jar can be detected as Forge and later run on NeoForge.
            // Reference scanning decides whether these bridges are embedded.
            String synLoaderT = info.modLoaderType();
            if ("neoforge".equalsIgnoreCase(synLoaderT) || "forge".equalsIgnoreCase(synLoaderT)) {
                try {
                    com.retromod.shim.forge.ForgeNeoForgeSynthetics.registerAll(transformer);
                } catch (Exception e) {
                    // Optional bridges should not discard the transformed jar.
                }
            }
        }
        return classMovesApplied;
    }

    /** Adds the replacement APIs required by a mod. */
    private static void embedCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: embed <mod.jar>");
            System.exit(1);
        }
        
        Path modPath = Path.of(args[1]);
        
        System.out.println("Adding replacement APIs to " + modPath.getFileName());
        
        ModVersionInfo info = detector.detectVersion(modPath);
        if (info == null) {
            System.err.println("Retromod could not read supported mod metadata from this jar.");
            System.exit(1);
        }
        
        ApiEmbedder embedder = new ApiEmbedder();
        embedder.embedRequiredShims(modPath, info);
        
        System.out.println("Replacement APIs added.");
    }
    
    /**
     * Adds loader API shims and unobfuscated-host mappings after the version
     * chain. Returns a short summary when anything was added.
     */
    static String registerAuxiliaryRedirects(
            RetromodTransformer transformer, ModVersionInfo info, List<VersionShim> chain) {
        // Batch and ahead-of-time paths both reach polyfills through this helper.
        new com.retromod.polyfill.PolyfillRegistry().loadAndRegister(transformer);
        java.util.Set<VersionShim> chainSet = new java.util.HashSet<>(chain);
        int apiApplied = 0;
        // Unit tests can exercise this helper without starting the CLI.
        java.util.List<VersionShim> allShims =
                (shimRegistry != null) ? shimRegistry.getAllShims() : java.util.List.of();
        for (VersionShim shim : allShims) {
            if (chainSet.contains(shim)) continue;
            String loader = shim.getModLoaderType();
            if (loader != null && !"any".equalsIgnoreCase(loader)
                    && !loader.equalsIgnoreCase(info.modLoaderType())) continue;
            if (!shim.getClass().getName().startsWith("com.retromod.shim.api.")) continue;
            try {
                shim.registerRedirects(transformer);
                apiApplied++;
            } catch (Exception e) {
                // One optional API shim should not block the others.
            }
        }

        int classMovesApplied = 0;
        int memberMappings = 0;
        if (com.retromod.core.RetromodVersion.isUnobfuscatedTarget(TARGET_MC_VERSION)) {
            // Vanilla class moves apply to every loader.
            try {
                var moves = com.retromod.mapping.IntermediaryToMojangMapper
                        .getInstance().getClassMoves();
                for (var e : moves.entrySet()) {
                    transformer.registerClassRedirect(e.getKey(), e.getValue());
                    classMovesApplied++;
                }
            } catch (Exception e) {
                // Other registered changes can still produce a useful result.
            }
            // A missing graph path must not skip the shared 26.1 adaptations.
            try {
                com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.register(transformer);
            } catch (Exception e) {
                // The name remap and class moves above still apply.
            }
            // The same rule applies to core moves on a 26.2 target.
            if (!com.retromod.core.RetromodVersion.mcVersionExceeds("26.2", TARGET_MC_VERSION)) {
                try {
                    com.retromod.shim.common.Mc26_1To26_2CoreMoves.register(transformer);
                } catch (Exception e) {
                    // The 26.1 adaptations above still apply.
                }
            }
            // Every loader can construct ResourceLocation, so keep this shared.
            com.retromod.mapping.IntermediaryToMojangMapper.registerIdentifierCtorRedirects(transformer);
            // NeoForge and Forge already use Mojang member names.
            if ("fabric".equalsIgnoreCase(info.modLoaderType())) {
                try {
                    memberMappings = com.retromod.mapping.IntermediaryToMojangMapper.applyTo(transformer);
                } catch (Exception e) {
                    // Class moves above still apply.
                }
            }
            // Reference scanning keeps these cross-loader bridges out of pure Forge mods.
            String synLoader = info.modLoaderType();
            if ("neoforge".equalsIgnoreCase(synLoader) || "forge".equalsIgnoreCase(synLoader)) {
                try {
                    com.retromod.shim.forge.ForgeNeoForgeSynthetics.registerAll(transformer);
                } catch (Exception e) {
                    // Optional bridges should not discard the transformed jar.
                }
            }
        }

        if (apiApplied == 0 && classMovesApplied == 0 && memberMappings == 0) {
            return null;
        }
        return "Applied alongside the version chain: " + apiApplied + " API shim(s), "
                + classMovesApplied + " class move(s), " + memberMappings + " member mapping(s).";
    }

    private static void batchCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: batch <mods-folder> [--output <output-folder>] [--aot]");
            System.exit(1);
        }
        
        Path modsFolder = Path.of(args[1]);
        Path outputFolder = modsFolder.resolve("retromod-output");
        boolean useAot = false;
        
        for (int i = 2; i < args.length; i++) {
            if ("--output".equals(args[i]) && i + 1 < args.length) {
                outputFolder = Path.of(args[++i]);
            } else if ("--aot".equals(args[i])) {
                useAot = true;
            }
        }
        
        Files.createDirectories(outputFolder);
        
        System.out.println("Retromod batch");
        System.out.println("Input:  " + modsFolder);
        System.out.println("Output: " + outputFolder);
        System.out.println("Mode:   " + (useAot ? "precompile" : "transform"));
        System.out.println();
        
        File[] modFiles = modsFolder.toFile().listFiles(
            (dir, name) -> name.endsWith(".jar") && !name.contains("-aot") && !name.contains("-transformed")
        );
        
        if (modFiles == null || modFiles.length == 0) {
            System.out.println("No JAR files found.");
            return;
        }
        
        int processed = 0, skipped = 0, failed = 0;
        long totalTime = 0;
        
        AotCompiler aotCompiler = useAot ? new AotCompiler(shimRegistry, TARGET_MC_VERSION) : null;
        
        for (int i = 0; i < modFiles.length; i++) {
            File modFile = modFiles[i];
            System.out.printf("[%d/%d] %s... ", i + 1, modFiles.length, modFile.getName());
            
            try {
                long start = System.currentTimeMillis();
                
                ModVersionInfo info = detector.detectVersion(modFile.toPath());
                if (info == null) {
                    System.out.println("skipped: no supported mod metadata");
                    skipped++;
                    continue;
                }
                
                // Current unobfuscated hosts still need relaxed loader metadata.
                boolean needs26Patch = TARGET_MC_VERSION.startsWith("26.");
                boolean needsBytecodeTransform = info.needsTransformation(TARGET_MC_VERSION);
                // Unknown source versions cannot safely take the metadata-only path.
                if (!needsBytecodeTransform && needs26Patch) {
                    String mv = info.targetMcVersion();
                    boolean readable = mv != null && !mv.isBlank() && !mv.contains("$")
                            && mv.matches(".*\\d+\\.\\d+.*");
                    if (!readable) {
                        needsBytecodeTransform = true;
                    }
                }

                Path outputPath = outputFolder.resolve(modFile.getName());
                String status;

                if (needsBytecodeTransform) {
                    if (useAot) {
                        Path result = aotCompiler.compileModAot(modFile.toPath());
                        // Copy the prepared jar before the shared metadata pass.
                        outputPath = outputFolder.resolve(
                            modFile.getName().replace(".jar", "-aot.jar"));
                        Files.copy(result, outputPath,
                            StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        RetromodTransformer transformer = RetromodTransformer.getInstance();
                        List<VersionShim> chain = shimRegistry.findShimChain(
                            info.modLoaderType(), info.targetMcVersion(), TARGET_MC_VERSION);
                        for (VersionShim shim : chain) {
                            shim.registerRedirects(transformer);
                        }
                        // Match the extra mappings used by the single-mod command.
                        registerAuxiliaryRedirects(transformer, info, chain);
                        transformJar(modFile.toPath(), outputPath, transformer, info);
                    }
                    status = "updated";
                } else if (!needs26Patch) {
                    Files.copy(modFile.toPath(), outputPath,
                        StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("copied: no changes needed");
                    skipped++;
                    continue;
                } else {
                    Files.copy(modFile.toPath(), outputPath,
                        StandardCopyOption.REPLACE_EXISTING);
                    status = "metadata updated";
                }

                if (needs26Patch) {
                    patchModMetadata(outputPath);
                    // Reference scanning embeds only the replacement classes this jar uses.
                    try {
                        RetromodTransformer rt = RetromodTransformer.getInstance();
                        String embLoader = info.modLoaderType();
                        if ("neoforge".equalsIgnoreCase(embLoader) || "forge".equalsIgnoreCase(embLoader)) {
                            com.retromod.shim.forge.ForgeNeoForgeSynthetics.registerAll(rt);
                        }
                        com.retromod.core.SyntheticEmbedder.embedIntoJar(
                                outputPath, modFile.getName(), rt);
                    } catch (Exception e) {
                        // The rest of the updated jar is still usable.
                    }
                }

                long elapsed = System.currentTimeMillis() - start;
                totalTime += elapsed;
                System.out.printf("%s (%d ms)%n", status, elapsed);
                processed++;
                
            } catch (Exception e) {
                System.out.println("failed: " + e.getMessage());
                failed++;
            }
        }
        
        System.out.println();
        System.out.printf("Summary: %d processed, %d skipped, %d failed%n", processed, skipped, failed);
        System.out.printf("Total time: %d ms (avg: %d ms/mod)%n", 
            totalTime, processed > 0 ? totalTime / processed : 0);
    }
    
    /** Show API differences between two versions. */
    private static void diffCommand(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: diff <loader> <version1> <version2>");
            System.err.println("Example: diff fabric 1.21.8 1.21.9");
            System.exit(1);
        }
        
        String loader = args[1];
        String v1 = args[2];
        String v2 = args.length > 3 ? args[3] : TARGET_MC_VERSION;
        
        System.out.println("API differences: " + loader + " " + v1 + " -> " + v2);

        List<VersionShim> chain = shimRegistry.findShimChain(loader, v1, v2);

        if (chain.isEmpty()) {
            System.out.println("No shim data available for this transition.");
            return;
        }
        
        for (VersionShim shim : chain) {
            System.out.println();
            System.out.println(shim.getShimName());
            
            RetromodTransformer temp = RetromodTransformer.getInstance();
            shim.registerRedirects(temp);
            
            System.out.println("Method redirects: " + temp.getMethodRedirectCount());
            System.out.println("Class redirects: " + temp.getClassRedirectCount());
            
            String[] shimClasses = shim.getShimClasses();
            if (shimClasses.length > 0) {
                System.out.println("Embedded classes:");
                for (String cls : shimClasses) {
                    System.out.println("  - " + cls);
                }
            }
        }
    }
    
    /** Manage API archives. */
    private static void archiveCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: archive <action> [options]");
            System.err.println("Actions:");
            System.err.println("  download <loader> <version>  - Download an API archive");
            System.err.println("  list                         - List cached archives");
            System.err.println("  preload                      - Download all known archives");
            System.err.println("  clear                        - Clear archive cache");
            System.exit(1);
        }
        
        String action = args[1].toLowerCase();
        
        switch (action) {
            case "download" -> {
                if (args.length < 4) {
                    System.err.println("Usage: archive download <loader> <version> [--yes]");
                    System.exit(1);
                }
                String loader = args[2];
                String version = args[3];
                boolean autoYes = args.length > 4 && "--yes".equalsIgnoreCase(args[4]);

                // Prompt before any network traffic; see ApiArchiveManager for the policy.
                boolean downloaded = archiveManager.downloadArchiveWithUserConsent(
                    loader, version,
                    () -> promptForDownloadConsent(loader, version, autoYes));
                if (downloaded) {
                    System.out.println("Download complete");
                } else {
                    System.out.println("Skipped: no download was made.");
                }
            }
            case "list" -> {
                System.out.println("Cached archives:");
                var stats = archiveManager.getCacheStats();
                if (stats.isEmpty()) {
                    System.out.println("  (none)");
                } else {
                    for (var entry : stats.entrySet()) {
                        System.out.printf("  %s: %d classes%n", entry.getKey(), entry.getValue());
                    }
                }
            }
            case "preload" -> {
                boolean autoYes = args.length > 2 && "--yes".equalsIgnoreCase(args[2]);
                archiveManager.preloadAllArchives(() -> promptForPreloadConsent(autoYes)).join();
                System.out.println("Archive preload finished");
            }
            case "clear" -> {
                archiveManager.clearCache();
                System.out.println("Archive cache cleared");
            }
            default -> System.err.println("Unknown action: " + action);
        }
    }

    /** Consent prompt for a single archive download; --yes skips it for scripted/CI use. */
    private static boolean promptForDownloadConsent(String loader, String version, boolean autoYes) {
        System.out.println();
        System.out.println("Retromod needs to download one API archive.");
        System.out.println();
        System.out.println("Loader: " + loader);
        System.out.println("Minecraft: " + version);
        System.out.println("Source: the loader's official Maven repository");

        if (autoYes) {
            System.out.println("--yes was provided, continuing without a prompt.");
            return true;
        }

        System.out.print("Proceed with download? [y/N] ");
        try {
            String line = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in)).readLine();
            return line != null && (line.equalsIgnoreCase("y") || line.equalsIgnoreCase("yes"));
        } catch (java.io.IOException e) {
            System.err.println("Could not read a response, so the download was cancelled.");
            return false;
        }
    }

    /** Consent prompt for the bulk preload action; --yes skips it for scripted/CI use. */
    private static boolean promptForPreloadConsent(boolean autoYes) {
        System.out.println();
        System.out.println("Retromod will download API archives for every known Minecraft version.");
        System.out.println("This is about 22 jars from the loaders' official Maven repositories.");

        if (autoYes) {
            System.out.println("--yes was provided, continuing without a prompt.");
            return true;
        }

        System.out.print("Proceed with preload? [y/N] ");
        try {
            String line = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in)).readLine();
            return line != null && (line.equalsIgnoreCase("y") || line.equalsIgnoreCase("yes"));
        } catch (java.io.IOException e) {
            System.err.println("Could not read a response, so the download was cancelled.");
            return false;
        }
    }
    
    /** List all registered shims. */
    private static void shimsCommand(String[] args) {
        System.out.println("Registered version shims");
        
        List<VersionShim> allShims = shimRegistry.getAllShims();
        
        String currentLoader = "";
        for (VersionShim shim : allShims) {
            if (!shim.getModLoaderType().equals(currentLoader)) {
                currentLoader = shim.getModLoaderType();
                System.out.println();
                System.out.println(currentLoader);
            }
            System.out.printf("  %s -> %s (%s)%n",
                shim.getSourceVersion(),
                shim.getTargetVersion(),
                shim.getShimName());
        }
        System.out.println();
        System.out.println("Total: " + allShims.size());
    }
    
    /** Transform a JAR file using the configured transformer. */
    private static void transformJar(Path input, Path output,
            RetromodTransformer transformer, ModVersionInfo info) throws Exception {

        try (var inJar = new java.util.jar.JarFile(input.toFile());
             var outJar = new java.util.jar.JarOutputStream(
                     new FileOutputStream(output.toFile()))) {

            // Offline analogue of the runtime mixin blocklist: neutralize blocklisted mixin
            // handlers / classes that fatally fail on the target MC.
            var mixinStripper = new com.retromod.mixin.MixinCompatibilityTransformer(transformer);

            // Forge -> NeoForge toml promotion: on a NeoForge target (real host or the offline
            // --target-loader neoforge override) a Forge mod's META-INF/mods.toml must be renamed
            // to neoforge.mods.toml (NeoForge 1.20.2+ skips a jar that only has mods.toml). Only
            // when the jar has mods.toml and no neoforge.mods.toml already. Mirrors
            // ForgeModTransformer.promoteToNeoForgeToml, done at the CLI entry-writing boundary.
            boolean promoteToml = com.retromod.util.McReflect.isNeoForge()
                    && !com.retromod.core.RetromodVersion.mcVersionExceeds("1.20.2", TARGET_MC_VERSION)
                    && inJar.getEntry("META-INF/mods.toml") != null
                    && inJar.getEntry("META-INF/neoforge.mods.toml") == null;

            var entries = inJar.entries();
            // Aggregate decompression cap: the per-entry safeReadAllBytes below bounds MEMORY, but a
            // high-entry-count jar could still force hundreds of GB of inflate/deflate work (CPU DoS).
            // The runtime extractJar paths cap the total; match that here for the offline path.
            long totalRead = 0;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();

                // Decide the output entry name up front so a promoted mods.toml lands under its
                // NeoForge filename. safeEntryName throws on path-traversal patterns.
                String outName = entry.getName();
                if (promoteToml && "META-INF/mods.toml".equals(entry.getName())) {
                    outName = "META-INF/neoforge.mods.toml";
                }
                outJar.putNextEntry(new java.util.jar.JarEntry(
                        com.retromod.util.ZipSecurity.safeEntryName(outName)));

                if (!entry.isDirectory()) {
                    try (var is = inJar.getInputStream(entry)) {
                        // Trust bytes read, not the size declared by the zip entry.
                        byte[] data = com.retromod.util.ZipSecurity.safeReadAllBytes(is);
                        totalRead += data.length;
                        if (totalRead > com.retromod.util.ZipSecurity.DEFAULT_MAX_TOTAL_SIZE) {
                            throw new IOException("mod jar exceeds max total decompressed size: " + input);
                        }

                        if (entry.getName().endsWith(".class")) {
                            if (shouldTransformClass(entry.getName(), info)) {
                                // Mojang-named jars need GUI migration before the owner redirect.
                                if (com.retromod.core.RetromodVersion.isUnobfuscatedTarget(TARGET_MC_VERSION)) {
                                    data = com.retromod.shim.common.Gui2DTransformMigration.migrate(data);
                                }
                                data = transformer.transformClass(data, entry.getName());
                                // Fabric only matches after its intermediary names are remapped.
                                if (com.retromod.core.RetromodVersion.isUnobfuscatedTarget(TARGET_MC_VERSION)) {
                                    data = com.retromod.shim.common.Gui2DTransformMigration.migrate(data);
                                }
                            }
                            // A mixin can need a safety repair even when its other bytecode does not.
                            data = mixinStripper.stripBlocklistedHandlers(data);
                            // ValueIO matching needs the Mojang parameter names produced above.
                            data = mixinStripper.adaptValueIoHandlers(data);
                            // These helpers inspect the class and ignore unrelated versions.
                            data = com.retromod.shim.forge.ForgeEventBusSynthetics
                                    .stripLenientAutoSubscriber(data);
                            data = com.retromod.shim.forge.Forge1122LifecycleSynthetics
                                    .upgradeLegacyModClass(data);
                        } else if (entry.getName().equals("fabric.mod.json")) {
                            data = relaxFabricModDependencies(data);
                        } else if (entry.getName().equals("quilt.mod.json")) {
                            data = relaxFabricModDependencies(data);
                        } else if (entry.getName().equals("META-INF/mods.toml") ||
                                   entry.getName().equals("META-INF/neoforge.mods.toml")) {
                            data = relaxNeoForgeDependencies(data);
                            if (promoteToml && entry.getName().equals("META-INF/mods.toml")) {
                                // repoint the mandatory `forge` loader dep at `neoforge` and relax
                                // the loaderVersion, as the runtime promotion does.
                                data = com.retromod.core.ForgeModTransformer
                                        .promoteTomlContentForNeoForge(new String(data,
                                                java.nio.charset.StandardCharsets.UTF_8))
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            }
                        } else if (entry.getName().endsWith(".mixins.json") ||
                                   entry.getName().endsWith("mixin.json") ||
                                   (entry.getName().contains("mixin") && entry.getName().endsWith(".json"))) {
                            // make mixin configs non-fatal so @Accessor/@Invoker on removed fields don't crash
                            data = makeMixinConfigNonFatal(data);
                        } else if ((entry.getName().toLowerCase().endsWith(".accesswidener")
                                    || entry.getName().toLowerCase().endsWith(".classtweaker"))
                                   && com.retromod.core.RetromodVersion.isUnobfuscatedTarget(TARGET_MC_VERSION)) {
                            // 26.1+ runs the OFFICIAL namespace; an intermediary access widener /
                            // classTweaker is rejected by Fabric's classTweaker reader at load (before
                            // any mixin/mod construction, so no crash-report - the game just dies).
                            // Remap it to official, matching the runtime FabricModTransformer path.
                            data = com.retromod.core.AccessWidenerRemapper.remapToOfficial(
                                    new String(data, java.nio.charset.StandardCharsets.UTF_8),
                                    com.retromod.mapping.IntermediaryToMojangMapper.getInstance())
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        } else if ((entry.getName().endsWith("-refmap.json")
                                    || entry.getName().contains("refmap"))
                                   && com.retromod.core.RetromodVersion.isUnobfuscatedTarget(TARGET_MC_VERSION)) {
                            // Remap the mixin refmap's intermediary selectors -> Mojang and add a
                            // data.official section, so @Inject/@At target classes resolve on a 26.1+
                            // host (else InvalidInjectionException on 'net/minecraft/class_XXXX').
                            // Parity with the runtime FabricModTransformer refmap pass.
                            data = com.retromod.core.MixinRefmapRemapper.remap(
                                    new String(data, java.nio.charset.StandardCharsets.UTF_8),
                                    com.retromod.mapping.IntermediaryToMojangMapper.getInstance())
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        } else if (com.retromod.resources.ModDataMigrator.isMigratableData(entry.getName())) {
                            // 26.x data-only changes the bytecode pass can't reach (item renames,
                            // JSON shape changes); gated to 26.x inside migrate()
                            data = com.retromod.resources.ModDataMigrator.migrate(
                                    entry.getName(), data, TARGET_MC_VERSION);
                        } else if ((entry.getName().startsWith("META-INF/jars/")        // Fabric JiJ
                                    || entry.getName().startsWith("META-INF/jarjar/"))  // NeoForge/Forge JiJ
                                   && entry.getName().endsWith(".jar")) {
                            // a mod that registers content through a JiJ'd library (#71) needs
                            // the nested jar transformed too
                            data = transformNestedJar(data, 1);
                        }

                        outJar.write(data);
                    }
                }

                outJar.closeEntry();
            }

            // 1.21.4+ client item definitions: synthesize assets/<ns>/items/<id>.json for item
            // models without one, or every item renders as the purple/black missing model.
            // Mirrors ModDataMigrator.migrateTree on the runtime (extracted-tree) paths.
            java.util.Set<String> entryNames = new java.util.HashSet<>();
            var nameScan = inJar.entries();
            while (nameScan.hasMoreElements()) entryNames.add(nameScan.nextElement().getName());
            for (var def : com.retromod.resources.ModDataMigrator
                    .synthesizeItemDefinitionEntries(entryNames, TARGET_MC_VERSION).entrySet()) {
                outJar.putNextEntry(new java.util.jar.JarEntry(
                        com.retromod.util.ZipSecurity.safeEntryName(def.getKey())));
                outJar.write(def.getValue());
                outJar.closeEntry();
            }
        }
    }

    /** Run verification if --verify is present or verify_transforms config is on. */
    private static void verifyIfRequested(Path outputJar, String modName, String[] args) {
        boolean verify = TransformVerifier.isEnabled();
        for (String arg : args) {
            if ("--verify".equals(arg)) { verify = true; break; }
        }
        if (!verify) return;

        var result = TransformVerifier.verifyAndReport(outputJar, modName, TARGET_MC_VERSION);
        if (result.passed()) {
            System.out.println("Verification passed.");
        } else {
            System.out.println("Verification found " + result.issueCount() + " issue(s).");
            for (var issue : result.issues()) {
                System.out.println("  - " + issue.toReadableString(TARGET_MC_VERSION));
            }
        }
    }

    /** Relax version constraints in fabric.mod.json so the mod can load on the target MC version. */
    private static byte[] relaxFabricModDependencies(byte[] jsonData) {
        try {
            String json = new String(jsonData, java.nio.charset.StandardCharsets.UTF_8);

            // string-level edits: minecraft -> "*", fabricloader -> permissive minimum,
            // fabric-api submodules -> "*"
            json = json.replaceAll(
                "(\"minecraft\"\\s*:\\s*)(?:\"[^\"]*\"|\\[[^\\]]*\\]|\\{[^}]*\\})",
                "$1\"*\""
            );

            json = json.replaceAll(
                "(\"fabricloader\"\\s*:\\s*)\"[^\"]*\"",
                "$1\">=0.14.0\""
            );

            json = json.replaceAll(
                "(\"fabric-[a-z-]+(?:-v[0-9]+)?\"\\s*:\\s*)\"(?:>=)?[0-9][^\"]*\"",
                "$1\"*\""
            );

            return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return jsonData;
        }
    }

    /**
     * Make a mixin config non-fatal ("required": false, "injectors":{"defaultRequire":0}) so
     * @Accessor/@Invoker targeting removed fields/methods don't crash the game.
     */
    private static byte[] makeMixinConfigNonFatal(byte[] jsonData) {
        try {
            String json = new String(jsonData, java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();

            // only mixin configs have a "package" key
            if (!root.has("package")) return jsonData;

            root.addProperty("required", false);

            com.google.gson.JsonObject injectors = root.has("injectors") && root.get("injectors").isJsonObject()
                ? root.getAsJsonObject("injectors")
                : new com.google.gson.JsonObject();
            injectors.addProperty("defaultRequire", 0);
            root.add("injectors", injectors);

            com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                .setPrettyPrinting().disableHtmlEscaping().create();
            return gson.toJson(root).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return jsonData;
        }
    }

    /** Max Jar-in-Jar nesting depth Retromod recurses through (libraries inside libraries). */
    private static final int MAX_JIJ_DEPTH = 4;

    /**
     * Recursively transform a nested Jar-in-Jar library: rewrite its bytecode, relax its metadata,
     * make its mixin configs non-fatal, and recurse into its own bundled jars up to
     * {@link #MAX_JIJ_DEPTH}. A mod registering content through a JiJ'd library references
     * relocated/intermediary names there too (#71). Mirrors FabricModTransformer.remapNestedJar.
     */
    // Package-private for NestedJarRecursionTest.
    static byte[] transformNestedJar(byte[] jarData, int depth) {
        try {
            var bais = new java.io.ByteArrayInputStream(jarData);
            var baos = new java.io.ByteArrayOutputStream(jarData.length);
            boolean modified = false;

            try (var jis = new java.util.jar.JarInputStream(bais);
                 var jos = new java.util.jar.JarOutputStream(baos)) {

                java.util.jar.JarEntry entry;
                while ((entry = jis.getNextJarEntry()) != null) {
                    jos.putNextEntry(new java.util.jar.JarEntry(
                            com.retromod.util.ZipSecurity.safeEntryName(entry.getName())));

                    if (!entry.isDirectory()) {
                        byte[] data = com.retromod.util.ZipSecurity.safeReadAllBytes(jis);
                        String name = entry.getName();

                        if (name.endsWith(".class")) {
                            String className = name.substring(0, name.length() - ".class".length());
                            try {
                                byte[] t = RetromodTransformer.getInstance().transformClass(data, className);
                                if (t != null && t != data) { data = t; modified = true; }
                            } catch (Exception ignored) {
                                // leave the class untouched on any transform error
                            }
                        } else if (name.endsWith(".mixins.json") || name.endsWith("mixin.json")
                                || (name.contains("mixin") && name.endsWith(".json"))) {
                            byte[] patched = makeMixinConfigNonFatal(data);
                            if (patched != data) modified = true;
                            data = patched;
                        } else if ((name.toLowerCase().endsWith(".accesswidener")
                                    || name.toLowerCase().endsWith(".classtweaker"))
                                   && com.retromod.core.RetromodVersion.isUnobfuscatedTarget(TARGET_MC_VERSION)) {
                            // Nested-jar access widener (e.g. cloth-config bundled inside a mod): an
                            // intermediary AW crashes Fabric's classTweaker reader on a 26.1+ host.
                            // Remap it to official (parity with the runtime nested-jar path).
                            String remapped = com.retromod.core.AccessWidenerRemapper.remapToOfficial(
                                    new String(data, java.nio.charset.StandardCharsets.UTF_8),
                                    com.retromod.mapping.IntermediaryToMojangMapper.getInstance());
                            byte[] t = remapped.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            if (!java.util.Arrays.equals(t, data)) { data = t; modified = true; }
                        } else if ((name.endsWith("-refmap.json") || name.contains("refmap"))
                                   && com.retromod.core.RetromodVersion.isUnobfuscatedTarget(TARGET_MC_VERSION)) {
                            // Nested-jar mixin refmap: remap intermediary selectors -> Mojang + add
                            // data.official (parity with the runtime nested-jar refmap pass).
                            String rf = com.retromod.core.MixinRefmapRemapper.remap(
                                    new String(data, java.nio.charset.StandardCharsets.UTF_8),
                                    com.retromod.mapping.IntermediaryToMojangMapper.getInstance());
                            byte[] t2 = rf.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            if (!java.util.Arrays.equals(t2, data)) { data = t2; modified = true; }
                        } else if (name.equals("fabric.mod.json") || name.equals("quilt.mod.json")) {
                            data = relaxFabricModDependencies(data);
                            modified = true;
                        } else if (name.equals("META-INF/mods.toml") || name.equals("META-INF/neoforge.mods.toml")) {
                            data = relaxNeoForgeDependencies(data);
                            modified = true;
                        } else if (com.retromod.resources.ModDataMigrator.isMigratableData(name)) {
                            // migrate JiJ'd data-pack JSON across 1.21.x -> 26.x data-only
                            // changes; gated to 26.x inside migrate()
                            byte[] t = com.retromod.resources.ModDataMigrator.migrate(
                                    name, data, TARGET_MC_VERSION);
                            if (t != data) { data = t; modified = true; }
                        } else if (depth < MAX_JIJ_DEPTH
                                && (name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jarjar/"))
                                && name.endsWith(".jar")) {
                            byte[] t = transformNestedJar(data, depth + 1);
                            if (t != data) { data = t; modified = true; }
                        }

                        jos.write(data);
                    }

                    jos.closeEntry();
                }
            }

            return modified ? baos.toByteArray() : jarData;
        } catch (Exception e) {
            return jarData;
        }
    }

    /**
     * Relax version constraints in mods.toml / neoforge.mods.toml so the mod can load on 26.1+:
     * widen minecraft/neoforge/forge ranges and make non-core dependencies optional.
     */
    private static byte[] relaxNeoForgeDependencies(byte[] tomlData) {
        try {
            String toml = new String(tomlData, java.nio.charset.StandardCharsets.UTF_8);

            StringBuilder result = new StringBuilder();
            String[] blocks = toml.split("(?=\\[\\[dependencies\\.)");

            for (String block : blocks) {
                if (!block.contains("modId") && !block.contains("modId")) {
                    // preamble or non-dependency block
                    result.append(block);
                    continue;
                }

                boolean isMinecraft = block.contains("\"minecraft\"");
                boolean isNeoForge = block.contains("\"neoforge\"");
                boolean isForge = block.contains("\"forge\"");
                boolean isCoreDependent = isMinecraft || isNeoForge || isForge;

                // Maven range format: [1.21,1.21.1) or [1.21.8,1.22)
                block = block.replaceAll(
                    "(versionRange\\s*=\\s*\")\\[([^,\"]+),[^\"]*\"",
                    "$1[$2,)\""
                );

                // bare version format: "1.21.8" (no brackets)
                block = block.replaceAll(
                    "(versionRange\\s*=\\s*\")([0-9][^\"\\[\\]]*)\"",
                    "$1[$2,)\""
                );

                if (!isCoreDependent) {
                    block = block.replaceAll(
                        "(type\\s*=\\s*\")required\"",
                        "$1optional\""
                    );
                    // old mandatory=true format
                    block = block.replaceAll(
                        "(mandatory\\s*=\\s*)true",
                        "$1false"
                    );
                }

                result.append(block);
            }

            return result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return tomlData;
        }
    }

    /**
     * Patch mod metadata (version constraints) in-place: rewrite fabric.mod.json, quilt.mod.json,
     * mods.toml, neoforge.mods.toml to relax version ranges for 26.1+.
     */
    private static void patchModMetadata(Path jarPath) throws Exception {
        Path tempJar = jarPath.resolveSibling(jarPath.getFileName() + ".tmp");

        try (var inJar = new java.util.jar.JarFile(jarPath.toFile());
             var outJar = new java.util.jar.JarOutputStream(
                     new FileOutputStream(tempJar.toFile()))) {

            var entries = inJar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                // safeEntryName throws on path-traversal patterns
                outJar.putNextEntry(new java.util.jar.JarEntry(
                        com.retromod.util.ZipSecurity.safeEntryName(entry.getName())));

                if (!entry.isDirectory()) {
                    try (var is = inJar.getInputStream(entry)) {
                        // bounded read against falsified-size entries
                        byte[] data = com.retromod.util.ZipSecurity.safeReadAllBytes(is);

                        if (entry.getName().equals("fabric.mod.json") ||
                                entry.getName().equals("quilt.mod.json")) {
                            data = relaxFabricModDependencies(data);
                        } else if (entry.getName().equals("META-INF/mods.toml") ||
                                   entry.getName().equals("META-INF/neoforge.mods.toml")) {
                            data = relaxNeoForgeDependencies(data);
                        } else if (com.retromod.resources.ModDataMigrator.isMigratableData(entry.getName())) {
                            // a "compatible by version" mod takes this metadata-only branch yet can
                            // still ship data hitting a 26.x change; gated to 26.x inside migrate()
                            data = com.retromod.resources.ModDataMigrator.migrate(
                                    entry.getName(), data, TARGET_MC_VERSION);
                        }

                        outJar.write(data);
                    }
                }

                outJar.closeEntry();
            }
        }

        Files.move(tempJar, jarPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean shouldTransformClass(String entryName, ModVersionInfo info) {
        String pkg = entryName.substring(0, Math.max(0, entryName.lastIndexOf('/') + 1));
        return info.modPackages().contains(pkg);
    }
    
    /** Transform a legacy mod for the current target version. */
    private static void legacyCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: legacy <mod.jar> [--output <output.jar>]");
            System.exit(1);
        }
        
        Path modPath = Path.of(args[1]);
        Path outputPath = null;
        
        for (int i = 2; i < args.length; i++) {
            if ("--output".equals(args[i]) && i + 1 < args.length) {
                outputPath = Path.of(args[++i]);
            }
        }
        
        System.out.println("Retromod legacy transform");
        System.out.println("Target: Minecraft " + TARGET_MC_VERSION);
        System.out.println();

        LegacyModSupport legacySupport = new LegacyModSupport(
            modPath.getParent(), TARGET_MC_VERSION
        );

        System.out.println("Analyzing mod: " + modPath.getFileName());
        LegacyModAnalysis analysis = legacySupport.analyzeMod(modPath);
        
        System.out.println();
        System.out.println("Analysis");
        System.out.println("Loader: " + analysis.modLoader);
        System.out.println("Built for Minecraft: " + analysis.targetMcVersion);
        System.out.println("Source era: " + analysis.sourceEpoch.name);
        System.out.println("Java version: " + analysis.sourceJavaVersion);
        System.out.println("Class file version: " + analysis.classFileVersion);
        System.out.println("Complexity: " + analysis.complexity);
        System.out.println("Virtual loader: " + (analysis.needsVirtualLoader ? "required" : "not needed"));
        System.out.println("Version steps: " + analysis.epochTransitions.size());
        System.out.println();
        
        if (analysis.epochTransitions.isEmpty()) {
            System.out.println("This mod does not need any version changes.");
            return;
        }
        
        System.out.println("Changes");
        for (EpochTransition t : analysis.epochTransitions) {
            System.out.println("  -> " + t.name());
        }
        System.out.println();

        Path result = legacySupport.transformMod(modPath, analysis);
        
        if (outputPath != null && !result.equals(outputPath)) {
            Files.move(result, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            result = outputPath;
        }
        
        System.out.println();
        System.out.println("Updated mod");
        System.out.println("Output: " + result);
        System.out.println("Test the result in game and review any warnings above.");
    }
    
    /**
     * Generate Fabric dependency overrides to bypass version checks. Fabric blocks mods
     * before Retromod can transform them.
     */
    private static void overridesCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: retromod overrides <minecraft-dir> [target-version]");
            System.err.println("  minecraft-dir: Path to .minecraft folder");
            System.err.println("  target-version: Target MC version (default: " + TARGET_MC_VERSION + ")");
            return;
        }
        
        Path minecraftDir = Paths.get(args[1]);
        String targetVersion = args.length > 2 ? args[2] : TARGET_MC_VERSION;
        
        if (!Files.isDirectory(minecraftDir)) {
            System.err.println("This is not a directory: " + minecraftDir);
            return;
        }
        
        Path modsDir = minecraftDir.resolve("mods");
        Path configDir = minecraftDir.resolve("config");
        
        if (!Files.isDirectory(modsDir)) {
            System.err.println("No mods folder was found in " + minecraftDir);
            return;
        }
        
        Files.createDirectories(configDir);
        
        System.out.println("Retromod dependency overrides");
        System.out.println("Scanning mods folder: " + modsDir);
        System.out.println("Target Minecraft version: " + targetVersion);
        System.out.println();
        
        Set<String> modIds = new java.util.HashSet<>();

        try (var stream = Files.list(modsDir)) {
            for (Path jar : stream.filter(p -> p.toString().endsWith(".jar")).toList()) {
                try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar.toFile())) {
                    var fabricEntry = jarFile.getEntry("fabric.mod.json");
                    if (fabricEntry != null) {
                        try (var is = jarFile.getInputStream(fabricEntry)) {
                            String json = new String(
                                com.retromod.util.ZipSecurity.safeReadAllBytes(is),
                                java.nio.charset.StandardCharsets.UTF_8);
                            // pull the mod ID out by hand
                            int idStart = json.indexOf("\"id\"");
                            if (idStart > 0) {
                                int colonPos = json.indexOf(":", idStart);
                                int quoteStart = json.indexOf("\"", colonPos + 1);
                                int quoteEnd = json.indexOf("\"", quoteStart + 1);
                                if (quoteStart > 0 && quoteEnd > quoteStart) {
                                    String modId = json.substring(quoteStart + 1, quoteEnd);
                                    modIds.add(modId);
                                    System.out.println("  Found mod: " + modId + " (" + jar.getFileName() + ")");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("  Warning: Could not read " + jar.getFileName() + ": " + e.getMessage());
                }
            }
        }
        
        if (modIds.isEmpty()) {
            System.out.println("No Fabric mods found that need overrides.");
            return;
        }
        
        Path overridesFile = configDir.resolve("fabric_loader_dependencies.json");
        
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"version\": 1,\n");
        json.append("  \"overrides\": {\n");
        
        boolean first = true;
        for (String modId : modIds) {
            if (!first) json.append(",\n");
            first = false;
            
            json.append("    \"").append(modId).append("\": {\n");
            json.append("      \"-depends\": {\n");
            json.append("        \"minecraft\": \"*\",\n");
            json.append("        \"fabricloader\": \"*\"\n");
            json.append("      },\n");
            json.append("      \"+depends\": {\n");
            json.append("        \"minecraft\": \">=1.14\",\n");
            json.append("        \"fabricloader\": \">=0.14.0\"\n");
            json.append("      }\n");
            json.append("    }");
        }
        
        json.append("\n  }\n");
        json.append("}\n");
        
        Files.writeString(overridesFile, json.toString());
        
        System.out.println("Generated dependency overrides for " + modIds.size() + " mods");
        System.out.println("File: " + overridesFile);
        System.out.println();
        System.out.println("These overrides only let Fabric inspect the mods. They do not repair");
        System.out.println("incompatible APIs. Use `retromod prepare` to update the jars too.");
    }
    
    /** Generate dependency overrides and transform all mods in an instance. */
    private static void prepareCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: retromod prepare <minecraft-dir> [--aot]");
            System.err.println("  minecraft-dir: Path to .minecraft folder");
            System.err.println("  --aot: Prepare mods ahead of launch (recommended)");
            return;
        }
        
        Path minecraftDir = Paths.get(args[1]);
        boolean useAot = Arrays.asList(args).contains("--aot");
        
        System.out.println("Preparing this Minecraft instance");
        System.out.println("Minecraft directory: " + minecraftDir);
        System.out.println("Mode: " + (useAot ? "ahead-of-time" : "at launch"));
        System.out.println();
        
        System.out.println("Generating dependency overrides...");
        overridesCommand(new String[]{"overrides", args[1], TARGET_MC_VERSION});

        System.out.println("Updating mods...");
        Path modsDir = minecraftDir.resolve("mods");
        
        if (useAot) {
            batchCommand(new String[]{"batch", modsDir.toString(), "--aot"});
        } else {
            batchCommand(new String[]{"batch", modsDir.toString()});
        }
        
        System.out.println();
        System.out.println("The mods folder is ready for Minecraft " + TARGET_MC_VERSION + ".");
        System.out.println("Some mods may still need manual work for APIs that changed substantially.");
    }
    
    /**
     * Help mod developers update their own mod to a newer MC version: scan the JAR and emit a
     * migration guide of API changes with find-and-replace suggestions for their source.
     */
    private static void devhelpCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: devhelp <mod.jar> [--to <version>]");
            System.err.println();
            System.err.println("Scans a mod and reports source changes for a newer Minecraft version.");
            System.err.println();
            System.err.println("Examples:");
            System.err.println("  retromod devhelp mymod-1.21.4.jar");
            System.err.println("  retromod devhelp mymod.jar --to " + TARGET_MC_VERSION);
            System.exit(1);
        }

        Path modPath = Path.of(args[1]);
        if (!Files.exists(modPath)) {
            System.err.println("File not found: " + modPath);
            System.exit(1);
        }

        String targetVersion = TARGET_MC_VERSION;
        for (int i = 2; i < args.length; i++) {
            if ("--to".equals(args[i]) && i + 1 < args.length) {
                targetVersion = args[++i];
            }
        }

        System.out.println();
        System.out.println("Retromod source migration guide");

        ModVersionInfo info = detector.detectVersion(modPath);
        if (info == null) {
            System.err.println("Could not read mod metadata. Is this a valid mod JAR?");
            System.exit(1);
        }

        String sourceVersion = info.targetMcVersion();
        System.out.println("  Mod:    " + info.modId() + " (v" + info.modVersion() + ")");
        System.out.println("  Loader: " + info.modLoaderType());
        System.out.println("  From:   MC " + sourceVersion);
        System.out.println("  To:     MC " + targetVersion);
        System.out.println();

        if (sourceVersion.equals(targetVersion)) {
            System.out.println("This mod already targets " + targetVersion + ".");
            return;
        }

        List<VersionShim> chain = shimRegistry.findShimChain(
            info.modLoaderType(), sourceVersion, targetVersion);

        if (chain.isEmpty()) {
            System.out.println("No migration data is available for " + sourceVersion + " -> " + targetVersion + ".");
            return;
        }

        printSection("Changes from " + sourceVersion + " to " + targetVersion);

        int totalMethods = 0;
        int totalClasses = 0;

        for (VersionShim shim : chain) {
            RetromodTransformer temp = RetromodTransformer.getInstance();
            shim.registerRedirects(temp);

            int methods = temp.getMethodRedirectCount();
            int classes = temp.getClassRedirectCount();

            if (methods > 0 || classes > 0) {
                System.out.println("--- " + shim.getShimName() + " ---");
                System.out.println();

                var classRedirects = temp.getClassRedirects();
                if (classRedirects != null && !classRedirects.isEmpty()) {
                    System.out.println("  Class renames:");
                    for (var entry : classRedirects.entrySet()) {
                        String oldName = entry.getKey().replace('/', '.');
                        String newName = entry.getValue().replace('/', '.');
                        System.out.println("    " + oldName);
                        System.out.println("      -> " + newName);
                    }
                    System.out.println();
                }

                var methodRedirects = temp.getMethodRedirects();
                if (methodRedirects != null && !methodRedirects.isEmpty()) {
                    System.out.println("  Method changes:");
                    for (var entry : methodRedirects.entrySet()) {
                        System.out.println("    " + entry.getKey());
                        System.out.println("      -> " + entry.getValue());
                    }
                    System.out.println();
                }

                String[] shimClasses = shim.getShimClasses();
                if (shimClasses.length > 0) {
                    System.out.println("  New shim classes available:");
                    for (String cls : shimClasses) {
                        System.out.println("    " + cls);
                    }
                    System.out.println();
                }

                totalMethods += methods;
                totalClasses += classes;
            }
        }

        printSection("Summary");
        System.out.println(chain.size() + " version steps");
        System.out.println(totalClasses + " class renames");
        System.out.println(totalMethods + " method changes");
        System.out.println();
        System.out.println("To update the source:");
        System.out.println("1. Apply the class and method changes above.");
        System.out.println("2. Update the Minecraft version in the mod metadata.");
        System.out.println("3. Rebuild against the target Minecraft version.");
        System.out.println();
    }

    /** Score a mod JAR for compatibility with the target MC version. */
    private static void scoreCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: score <mod.jar|dir> [--mc-jar <path>] [--fabric-api <path>] [--verbose] [--json <out>]");
            System.err.println("       a directory scores every jar in one JVM; --json dumps residual missing members for aggregation");
            System.exit(1);
        }

        Path modPath = Path.of(args[1]);
        if (!Files.exists(modPath)) {
            System.err.println("File not found: " + modPath);
            System.exit(1);
        }

        boolean verbose = false;
        Path mcJarPath = null;
        Path fabricApiPath = null;
        Path jsonOut = null;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--verbose", "-v" -> verbose = true;
                case "--mc-jar" -> {
                    if (i + 1 < args.length) mcJarPath = Path.of(args[++i]);
                }
                case "--fabric-api" -> {
                    if (i + 1 < args.length) fabricApiPath = Path.of(args[++i]);
                }
                case "--json" -> {
                    if (i + 1 < args.length) jsonOut = Path.of(args[++i]);
                }
            }
        }

        if (mcJarPath == null) {
            mcJarPath = Path.of(System.getProperty("user.home"),
                    "Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft/26.1-pre-2/minecraft-26.1-pre-2-client.jar");
            if (!Files.exists(mcJarPath)) {
                Path altPath = Path.of(System.getProperty("user.home"),
                        ".minecraft/versions/26.1/26.1.jar");
                if (Files.exists(altPath)) {
                    mcJarPath = altPath;
                }
            }
        }

        if (fabricApiPath == null) {
            fabricApiPath = Path.of(System.getProperty("user.home"),
                    "Library/Application Support/PrismLauncher/instances/26.1-pre-2-fabric/minecraft/mods/fabric-api-0.143.14+26.1.jar");
        }

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        for (VersionShim shim : shimRegistry.getAllShims()) {
            shim.registerRedirects(transformer);
        }
        com.retromod.polyfill.PolyfillRegistry polyfillRegistry = new com.retromod.polyfill.PolyfillRegistry();
        polyfillRegistry.loadAndRegister(transformer);

        ModScorer scorer = new ModScorer(transformer);

        if (Files.exists(mcJarPath)) {
            System.err.println("Loading MC index from: " + mcJarPath.getFileName());
            scorer.loadMcJar(mcJarPath);
        } else {
            System.err.println("Warning: MC JAR not found at " + mcJarPath);
            System.err.println("  Use --mc-jar <path> to specify the Minecraft client JAR");
        }

        if (fabricApiPath != null && Files.exists(fabricApiPath)) {
            System.err.println("Loading Fabric API index from: " + fabricApiPath.getFileName());
            scorer.loadFabricApiJar(fabricApiPath);
        }

        // Reuse the loaded index for every jar so large corpus scans stay fast.
        if (Files.isDirectory(modPath)) {
            scoreDirectory(scorer, modPath, jsonOut);
            return;
        }

        ModVersionInfo info = detector.detectVersion(modPath);

        System.err.println("Analyzing: " + modPath.getFileName());
        ModScorer.ScoreResult result = scorer.analyze(modPath, info);

        String modName = info != null && info.modId() != null
                ? info.modId() + " " + (info.modVersion() != null ? info.modVersion() : "")
                : modPath.getFileName().toString();
        String sourceLine = info != null && info.targetMcVersion() != null
                ? info.modLoaderType() + " " + info.targetMcVersion()
                : "unknown";

        System.out.println();
        System.out.println("Retromod compatibility score");
        System.out.println("Mod: " + modName.trim());
        System.out.println("Source: " + sourceLine);
        System.out.println("Target: Minecraft " + TARGET_MC_VERSION);
        System.out.println("Overall: " + result.overallScore + "/100");
        System.out.println();
        System.out.printf("Class references: %d/%d resolvable, %d%% (%s)%n",
                result.resolvableClasses, result.totalClasses, result.classScore,
                scoreStatus(result.classScore));
        System.out.printf("Method calls: %d/%d redirectable, %d%% (%s)%n",
                result.resolvableMethods + result.redirectedMethods, result.totalMethods, result.methodScore,
                scoreStatus(result.methodScore));
        System.out.printf("Field accesses: %d/%d resolvable, %d%% (%s)%n",
                result.resolvableFields + result.redirectedFields, result.totalFields, result.fieldScore,
                scoreStatus(result.fieldScore));
        System.out.printf("Mixin targets: %d/%d valid, %d%% (%s)%n",
                result.validMixins, result.totalMixins, result.mixinScore,
                scoreStatus(result.mixinScore));
        System.out.println("Estimate: " + result.getVerdict());
        System.out.println();

        if (verbose) {
            if (!result.missingClasses.isEmpty()) {
                System.out.println("Missing classes (" + result.missingClasses.size() + "):");
                for (String cls : result.missingClasses) {
                    System.out.println("  - " + cls.replace('/', '.'));
                }
                System.out.println();
            }

            if (!result.missingMethods.isEmpty()) {
                System.out.println("Unresolvable method calls (" + result.missingMethods.size() + "):");
                int shown = 0;
                for (String m : result.missingMethods) {
                    System.out.println("  - " + m);
                    if (++shown >= 50) {
                        System.out.println("  ... and " + (result.missingMethods.size() - shown) + " more");
                        break;
                    }
                }
                System.out.println();
            }

            if (!result.missingFields.isEmpty()) {
                System.out.println("Unresolvable field accesses (" + result.missingFields.size() + "):");
                for (String f : result.missingFields) {
                    System.out.println("  - " + f);
                }
                System.out.println();
            }

            if (!result.brokenMixins.isEmpty()) {
                System.out.println("Broken Mixin targets (" + result.brokenMixins.size() + "):");
                for (String m : result.brokenMixins) {
                    System.out.println("  - " + m);
                }
                System.out.println();
            }

            if (!result.missingClasses.isEmpty() || !result.missingMethods.isEmpty()) {
                System.out.println("Suggestions:");
                boolean hasFabricMissing = result.missingClasses.stream()
                        .anyMatch(c -> c.startsWith("net/fabricmc/"));
                boolean hasNbtMissing = result.missingClasses.stream()
                        .anyMatch(c -> c.contains("nbt") || c.contains("Nbt"));
                boolean hasRenderMissing = result.missingMethods.stream()
                        .anyMatch(m -> m.contains("render") || m.contains("Render") || m.contains("GlStateManager"));

                if (hasFabricMissing) {
                    System.out.println("  - Enable Fabric API polyfills (fabric_api category)");
                }
                if (hasNbtMissing) {
                    System.out.println("  - Enable NBT polyfill for removed NBT classes");
                }
                if (hasRenderMissing) {
                    System.out.println("  - Enable rendering polyfill for GlStateManager/RenderType changes");
                }
                if (result.missingClasses.size() > 10) {
                    System.out.println("  - This mod may need manual porting for heavily changed APIs");
                }
                System.out.println();
            }
        }
    }

    /** Score every transformed jar in a directory and optionally write missing references as JSON. */
    private static void scoreDirectory(ModScorer scorer, Path dir, Path jsonOut) throws Exception {
        java.util.List<Path> jars = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(jars::add);
        }
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (Path jar : jars) {
            try {
                ModVersionInfo info = detector.detectVersion(jar);
                ModScorer.ScoreResult r = scorer.analyze(jar, info);
                com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                o.addProperty("jar", jar.getFileName().toString());
                o.addProperty("overallScore", r.overallScore);
                o.add("missingClasses", strArray(r.missingClasses));
                o.add("missingMethods", strArray(r.missingMethods));
                o.add("missingFields", strArray(r.missingFields));
                arr.add(o);
                System.err.println(String.format("scored %-52s %3d/100  (%dC %dM %dF)",
                        jar.getFileName(), r.overallScore,
                        r.missingClasses.size(), r.missingMethods.size(), r.missingFields.size()));
            } catch (Exception e) {
                System.err.println("skip " + jar.getFileName() + ": " + e);
            }
        }
        if (jsonOut != null) {
            Files.writeString(jsonOut,
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(arr));
            System.err.println("wrote " + jsonOut + " (" + arr.size() + " jars)");
        }
    }

    private static com.google.gson.JsonArray strArray(java.util.List<String> list) {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (String s : list) a.add(s);
        return a;
    }

    private static String scoreStatus(int score) {
        if (score >= 75) {
            return "good";
        }
        if (score >= 50) {
            return "needs review";
        }
        return "poor";
    }

    /** Analyze a crash log or game log and report (or, with --apply, apply) auto-fixes. */
    private static void autofixCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: autofix <log-file> [--apply]");
            System.err.println();
            System.err.println("  <log-file>   Path to a crash report or latest.log");
            System.err.println("  --apply      Apply fixes to the transformer (registers redirects)");
            System.err.println();
            System.err.println("Examples:");
            System.err.println("  retromod autofix logs/latest.log");
            System.err.println("  retromod autofix crash-reports/crash-2026-04-07.txt --apply");
            System.exit(1);
        }

        Path logFile = Path.of(args[1]);
        boolean apply = args.length > 2 && "--apply".equals(args[2]);

        if (!Files.exists(logFile)) {
            System.err.println("File not found: " + logFile);
            System.exit(1);
        }

        System.out.println();
        System.out.println("Retromod log analysis");
        System.out.println("Log file: " + logFile);
        System.out.println("Mode: " + (apply ? "apply fixes" : "dry run"));
        System.out.println();

        com.retromod.core.AutoFixEngine engine = new com.retromod.core.AutoFixEngine();

        List<com.retromod.core.AutoFixEngine.AppliedFix> fixes;
        if (apply) {
            // The fix engine needs the same redirect context as a normal transform.
            RetromodTransformer transformer = RetromodTransformer.getInstance();
            for (VersionShim shim : shimRegistry.getAllShims()) {
                try {
                    shim.registerRedirects(transformer);
                } catch (Exception e) {
                    // A broken optional shim should not stop log analysis.
                }
            }
            fixes = engine.analyzeAndFix(logFile, transformer);
        } else {
            fixes = engine.analyzeOnly(logFile);
        }

        if (fixes.isEmpty()) {
            System.out.println("  No actionable errors found in the log.");
            System.out.println();
            System.out.println("  If you expected fixes, check that:");
            System.out.println("  - The log file contains actual error messages");
            System.out.println("  - The errors are from mod compatibility issues (not config errors)");
            System.out.println();
            return;
        }

        System.out.println("Found " + fixes.size() + " actionable error(s):");
        System.out.println();

        // Group related fixes so repeated errors are easier to scan.
        Map<String, List<com.retromod.core.AutoFixEngine.AppliedFix>> byType = new LinkedHashMap<>();
        for (com.retromod.core.AutoFixEngine.AppliedFix fix : fixes) {
            byType.computeIfAbsent(fix.errorType(), k -> new ArrayList<>()).add(fix);
        }

        for (Map.Entry<String, List<com.retromod.core.AutoFixEngine.AppliedFix>> entry : byType.entrySet()) {
            printSection(entry.getKey() + " (" + entry.getValue().size() + " occurrence(s))");

            for (com.retromod.core.AutoFixEngine.AppliedFix fix : entry.getValue()) {
                System.out.println("  Problem: " + fix.description());
                System.out.println("  " + (apply ? "Action:" : "Suggestion:") + " " + fix.action());
                System.out.println();
            }
        }

        if (apply) {
            System.out.println(fixes.size() + " fix(es) applied.");
            System.out.println("Run this to rebuild the affected mods:");
            System.out.println("  retromod batch <mods-folder> --aot");
        } else {
            System.out.println(fixes.size() + " fix(es) suggested.");
            System.out.println("To apply them, run:");
            System.out.println("  retromod autofix " + logFile + " --apply");
        }
        System.out.println();
    }

    private static void printUsage() {
        System.out.println("Retromod CLI " + VERSION);
        System.out.println("Update older Fabric, NeoForge, and Forge mods for newer Minecraft versions.");
        System.out.println("Default target: Minecraft " + TARGET_MC_VERSION);
        System.out.println();
        System.out.println("Usage: retromod <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  analyze <mod.jar>              Inspect metadata and compatibility risk");
        System.out.println("  transform <mod.jar>            Update one mod");
        System.out.println("  aot <mod.jar>                  Prepare one mod ahead of launch");
        System.out.println("  batch <folder>                 Update every mod in a folder");
        System.out.println("  prepare <game-dir>             Prepare a Minecraft instance");
        System.out.println("  legacy <mod.jar>               Use the legacy transform path");
        System.out.println("  embed <mod.jar>                Add required replacement APIs");
        System.out.println("  score <path>                   Score one mod or a mod folder");
        System.out.println("  gaps <folder>                  Report unresolved references");
        System.out.println("  mixin-scan <path>...           Inventory mixin targets");
        System.out.println("  devhelp <mod.jar> [target]     Show source changes a mod may need");
        System.out.println("  autofix <log-file>             Suggest fixes from a crash log");
        System.out.println("  diff <loader> <v1> <v2>        Compare two version points");
        System.out.println("  shims                          List registered shims");
        System.out.println("  overrides <game-dir>           Write Fabric dependency overrides");
        System.out.println("  archive <action>               Manage API archives");
        System.out.println("  help                           Show this help");
        System.out.println("  version                        Show the CLI version");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --target <version>             Choose the target Minecraft version");
        System.out.println("  --target-loader <loader>       Choose an offline loader migration");
        System.out.println("  --output <path>                Choose the output file or folder");
        System.out.println("  --aot                          Prepare bytecode ahead of launch");
        System.out.println("  --verify                       Check unresolved references");
        System.out.println("  --force                        Continue despite a high risk score");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  retromod prepare ~/.minecraft --aot");
        System.out.println("  retromod batch ./mods --aot --verify");
        System.out.println("  retromod transform oldmod.jar --target " + TARGET_MC_VERSION);
        System.out.println("  retromod devhelp mymod.jar " + TARGET_MC_VERSION);
    }

    /**
     * Inventory the mixin injectors across one or more mod JARs (or directories recursed for
     * {@code *.jar}). Reads {@code @Mixin}/injector annotations with ASM and emits the frozen JSON
     * schema the mixin-discovery Python tools consume, plus a human summary and a top-N table.
     */
    private static void mixinScanCommand(String[] args) throws Exception {
        List<Path> inputs = new ArrayList<>();
        Path jsonOut = null;
        int topN = 20;

        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("--json".equals(a)) {
                if (i + 1 >= args.length) { System.err.println("--json needs a path"); System.exit(1); }
                jsonOut = Path.of(args[++i]);
            } else if ("--top".equals(a)) {
                if (i + 1 >= args.length) { System.err.println("--top needs a number"); System.exit(1); }
                try {
                    topN = Integer.parseInt(args[++i].trim());
                } catch (NumberFormatException e) {
                    System.err.println("--top expects an integer, got: " + args[i]);
                    System.exit(1);
                }
            } else if (a.startsWith("--")) {
                // consumed elsewhere (e.g. --target); ignore unknown flags here
            } else {
                inputs.add(Path.of(a));
            }
        }

        if (inputs.isEmpty()) {
            System.err.println("Usage: mixin-scan <dir-or-jar>... [--json <out>] [--top N]");
            System.exit(1);
        }

        MixinScanner.ScanResult result = MixinScanner.scan(inputs);
        MixinScanner.printSummary(result, topN, System.out);

        if (jsonOut != null) {
            String json = MixinScanner.toJson(result);
            if (jsonOut.getParent() != null) {
                Files.createDirectories(jsonOut.getParent());
            }
            Files.writeString(jsonOut, json, java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("Wrote JSON: " + jsonOut + " (" + result.records.size() + " records)");
        }
    }

    /**
     * Produce a cross-mod gap report: for every mod JAR in the folder, transform its classes,
     * verify each against the target MC index, and aggregate unresolved-reference findings ranked
     * by how many mods are affected. Diagnostic only; runs in-memory and modifies nothing on disk.
     */
    private static void gapsCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: gaps <mods-folder> [--mc-jar <path>] [--output <file>]");
            System.err.println("  --mc-jar   Path to target Minecraft JAR (required for verification)");
            System.err.println("  --output   Write the report to this file instead of stdout");
            System.exit(1);
        }

        Path modsFolder = Path.of(args[1]);
        if (!Files.isDirectory(modsFolder)) {
            System.err.println("Not a directory: " + modsFolder);
            System.exit(1);
        }

        Path mcJar = null;
        Path outputPath = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--mc-jar" -> {
                    if (i + 1 >= args.length) { System.err.println("--mc-jar needs a path"); System.exit(1); }
                    mcJar = Path.of(args[++i]);
                }
                case "--output" -> {
                    if (i + 1 >= args.length) { System.err.println("--output needs a path"); System.exit(1); }
                    outputPath = Path.of(args[++i]);
                }
                default -> System.err.println("Ignoring unknown flag: " + args[i]);
            }
        }

        // The transformer reads the verify flag at class-init time, so the property must be
        // set before launch; bail with instructions otherwise.
        if (!RetromodTransformer.isVerificationEnabled()) {
            System.err.println("Reference verification is not enabled.");
            System.err.println("Re-run with: -Dretromod.verifyTransforms=true");
            System.err.println("For Maven, add the same property to the exec command.");
            System.exit(2);
        }

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.setTargetMcVersion(TARGET_MC_VERSION);

        // Wire Fabric intermediary->Mojang mappings; without them a Fabric mod's intermediary
        // names go untouched and show up as "missing", filling the report with noise.
        com.retromod.mapping.IntermediaryToMojangMapper.applyTo(transformer);

        if (mcJar != null) {
            if (!Files.exists(mcJar)) {
                System.err.println("MC JAR not found: " + mcJar);
                System.exit(1);
            }
            transformer.initFuzzyResolver(mcJar);
        } else {
            System.err.println("WARN: no --mc-jar supplied; verification will skip all classes");
            System.err.println("      (the fuzzy resolver has no MC index to check against)");
        }

        com.retromod.core.verify.CrossModGapReport aggregated =
                new com.retromod.core.verify.CrossModGapReport(TARGET_MC_VERSION);

        int modsProcessed = 0;
        int modsFailed = 0;

        try (var stream = Files.list(modsFolder)) {
            for (Path jar : (Iterable<Path>) stream::iterator) {
                if (!jar.toString().endsWith(".jar")) continue;
                // honor mod-author opt-out even though gaps only reads + verifies
                if (com.retromod.util.OptOutCheck.isOptedOut(jar)) {
                    com.retromod.util.OptOutCheck.logSkipped(jar);
                    continue;
                }
                System.out.println("[gaps] scanning " + jar.getFileName());
                try {
                    com.retromod.core.verify.VerificationReport perMod = verifyOneMod(transformer, jar);
                    if (perMod != null) {
                        aggregated.merge(perMod);
                        modsProcessed++;
                    }
                } catch (Exception e) {
                    System.err.println("  failed: " + e.getMessage());
                    modsFailed++;
                }
            }
        }

        System.out.println();
        System.out.printf("Processed %d mod%s (%d failed)%n",
                modsProcessed, modsProcessed == 1 ? "" : "s", modsFailed);
        System.out.println();

        StringBuilder out = new StringBuilder();
        aggregated.writeTo(out);

        if (outputPath != null) {
            Files.writeString(outputPath, out.toString());
            System.out.println("Report written to " + outputPath);
        } else {
            System.out.println(out);
        }
    }

    /**
     * Open one mod JAR, transform + verify each class, return the per-mod report. In-memory only.
     * Reads all classes, transforms, optionally synthesizes bridges and matches patterns, then
     * verifies the final bytecode. Each optional step is gated by its own {@code -Dretromod.*} flag.
     */
    private static com.retromod.core.verify.VerificationReport verifyOneMod(
            RetromodTransformer transformer, Path jarPath) throws Exception {

        ModVersionInfo info = detector.detectVersion(jarPath);
        String modId = info != null ? info.modId() : jarPath.getFileName().toString();

        // First pass: enumerate every class so the verifier doesn't flag mod-internal refs
        // as "missing from MC".
        java.util.Set<String> modOwnClasses = new java.util.HashSet<>();
        java.util.Map<String, byte[]> classBytesByName = new java.util.LinkedHashMap<>();

        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                String internalName = entry.getName().substring(0, entry.getName().length() - 6);
                modOwnClasses.add(internalName);
                try (var in = jar.getInputStream(entry)) {
                    // bounded read against falsified-size entries
                    classBytesByName.put(internalName,
                            com.retromod.util.ZipSecurity.safeReadAllBytes(in));
                }
            }
        }

        com.retromod.core.verify.VerificationReport report =
                new com.retromod.core.verify.VerificationReport(
                        modId, TARGET_MC_VERSION, classBytesByName.size());

        // pattern-matching context shared across all class visits for this mod
        com.retromod.core.pattern.MatchContext matchCtx = null;
        if (RetromodTransformer.isPatternMatchingEnabled()) {
            com.retromod.core.verify.McSymbolIndex idx = transformer.getFuzzyResolver() != null
                    ? new com.retromod.core.verify.FuzzyBackedSymbolIndex(
                            transformer.getFuzzyResolver(), TARGET_MC_VERSION)
                    : com.retromod.core.pattern.MatchContext.empty().mcIndex();
            matchCtx = new com.retromod.core.pattern.MatchContext(
                    modOwnClasses,
                    com.retromod.core.verify.LoaderApiRenames.getInstance(),
                    idx);
        }

        int bridgeCountBefore = transformer.getBridgeSynthesizer().getBridgesSynthesized();

        // Second pass: transform each class, then optional post-processing. Runs in parallel
        // (-Dretromod.parallelism, default = all cores); each per-class pipeline is independent.
        final com.retromod.core.pattern.MatchContext finalMatchCtx = matchCtx;
        com.retromod.core.parallel.RetromodExecutors.parallelForEachEntry(
                classBytesByName,
                (className, bytes) -> {
                    byte[] transformed = transformer.transformClass(bytes, className);
                    transformed = transformer.synthesizeBridges(transformed, modOwnClasses);
                    transformer.verifyClass(transformed, className, modOwnClasses, report);
                    if (finalMatchCtx != null) {
                        for (var m : transformer.matchPatterns(transformed, finalMatchCtx)) {
                            report.addPatternMatch(m);
                        }
                    }
                });

        int bridgesThisMod = transformer.getBridgeSynthesizer().getBridgesSynthesized()
                           - bridgeCountBefore;
        report.setBridgesSynthesized(bridgesThisMod);

        System.out.println("  " + report.summaryLine());
        return report;
    }
}
