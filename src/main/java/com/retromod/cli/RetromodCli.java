/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.cli;

import com.retromod.core.*;
import com.retromod.embedder.*;
import com.retromod.aot.AotCompiler;
import com.retromod.archive.ApiArchiveManager;
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
    
    private static final String VERSION = "1.2.0-snapshot.8";
    // Overridable per-invocation via `--target <mc-version>`.
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
            // might not have access
        }

        String command = args[0].toLowerCase();

        // Keep RetromodVersion in sync with the CLI's default target too - version-gated shims
        // (the #87 registry-id bridge, KNOWN_INTERFACES_26X, item-defs) read RetromodVersion, and
        // syncing only inside the --target branch left default runs on the stale "1.21.4"
        // (review finding).
        com.retromod.core.RetromodVersion.TARGET_MC_VERSION = TARGET_MC_VERSION;

        // `--target <mc-version>` overrides the default target.
        for (int i = 1; i < args.length - 1; i++) {
            if ("--target".equals(args[i])) {
                String v = args[i + 1].trim();
                if (!v.isEmpty()) {
                    TARGET_MC_VERSION = v;
                    // Keep RetromodVersion.TARGET_MC_VERSION (used by version-gated shims, e.g. the
                    // #87 registry-id bridge) consistent with the CLI target on the offline path; at
                    // runtime it's auto-detected. The CLI's own gating uses this class's field, so
                    // this only fixes shims that read RetromodVersion directly.
                    com.retromod.core.RetromodVersion.TARGET_MC_VERSION = v;
                    System.out.println("Target MC version: " + TARGET_MC_VERSION);
                }
                break;
            }
        }

        // `--target-loader neoforge` forces the offline Forge -> NeoForge migration path: the shim
        // redirects and the mods.toml -> neoforge.mods.toml promotion normally gate on a live
        // NeoForge runtime (McReflect.isNeoForge()), which the CLI has none of. This lets us drive
        // and verify the full migration offline. Off unless explicitly requested.
        for (int i = 1; i < args.length - 1; i++) {
            if ("--target-loader".equals(args[i])) {
                String v = args[i + 1].trim();
                if ("neoforge".equalsIgnoreCase(v)) {
                    com.retromod.util.McReflect.setForceNeoForge(true);
                    System.out.println("Target loader: NeoForge (offline Forge -> NeoForge migration forced)");
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
            System.err.println("Error: " + e.getMessage());
            if (System.getenv("RETROMOD_DEBUG") != null) {
                e.printStackTrace();
            }
            System.exit(1);
        } finally {
            archiveManager.shutdown();
        }
    }
    
    private static void registerAllShims() {
        // Activate the 1.12.2 @Mod(modid=...) modernization for the whole CLI session (#120).
        // The runtime activates it when the 1.12.2 shim chain registers; the CLI's per-command
        // flows can call transformJar BEFORE that point, so a 1.12.2 mod's @Mod would keep the
        // old shape and modern Forge would read a null modid ("mods.toml missing metadata for
        // modid null"). The pass is self-gating on the old annotation shape, so activating it
        // globally here is harmless for every other mod.
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

        // NeoForge shims: complete 1.21 to 26.1 chain (step by step)
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
        
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║              Retromod Mod Analysis                   ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("File: " + modPath.getFileName());
        System.out.println("Size: " + Files.size(modPath) / 1024 + " KB");
        System.out.println();
        
        ModVersionInfo info = detector.detectVersion(modPath);
        
        if (info == null) {
            System.out.println("⚠  Could not detect mod metadata.");
            System.out.println("   This may not be a valid Fabric/NeoForge mod.");
            return;
        }
        
        System.out.println("┌─ Mod Information ────────────────────────────────────┐");
        System.out.printf("│ Mod ID:          %-36s │%n", info.modId());
        System.out.printf("│ Mod Version:     %-36s │%n", info.modVersion());
        System.out.printf("│ Target MC:       %-36s │%n", info.targetMcVersion());
        System.out.printf("│ Mod Loader:      %-36s │%n", info.modLoaderType());
        System.out.printf("│ Loader Version:  %-36s │%n", 
            info.modLoaderVersion() != null ? info.modLoaderVersion() : "unknown");
        System.out.println("└──────────────────────────────────────────────────────┘");
        
        System.out.println();
        System.out.println("┌─ Packages (" + info.modPackages().size() + ") ───────────────────────────────────┐");
        int pkgCount = 0;
        for (String pkg : info.modPackages()) {
            if (pkgCount++ < 10) {
                System.out.printf("│   %s%n", pkg.replace('/', '.'));
            }
        }
        if (info.modPackages().size() > 10) {
            System.out.printf("│   ... and %d more%n", info.modPackages().size() - 10);
        }
        System.out.println("└──────────────────────────────────────────────────────┘");

        System.out.println();
        System.out.println("┌─ Compatibility Analysis ────────────────────────────┐");
        
        if (info.needsTransformation(TARGET_MC_VERSION)) {
            System.out.println("│ ⚠  TRANSFORMATION REQUIRED                          │");
            System.out.printf("│    Built for: %-10s  Current: %-10s       │%n",
                info.targetMcVersion(), TARGET_MC_VERSION);
            
            List<VersionShim> chain = shimRegistry.findShimChain(
                info.modLoaderType(),
                info.targetMcVersion(),
                TARGET_MC_VERSION
            );
            
            if (chain.isEmpty()) {
                System.out.println("│                                                      │");
                System.out.println("│ ❌ No shim chain available for this transition!     │");
                System.out.println("│    The mod cannot be automatically transformed.      │");
            } else {
                System.out.println("│                                                      │");
                System.out.println("│ ✓  Shim chain available:                            │");
                for (VersionShim shim : chain) {
                    System.out.printf("│    → %s%n", shim.getShimName());
                }
            }
        } else {
            System.out.println("│ ✓  COMPATIBLE - No transformation needed            │");
        }
        
        System.out.println("└──────────────────────────────────────────────────────┘");

        System.out.println();
        System.out.println("┌─ Complexity Analysis ───────────────────────────────┐");

        com.retromod.gui.ModComplexityAnalyzer complexityAnalyzer =
            new com.retromod.gui.ModComplexityAnalyzer();
        com.retromod.gui.ModComplexityAnalyzer.ComplexityReport complexityReport =
            complexityAnalyzer.analyze(modPath);

        System.out.printf("│ Complexity Score: %-36d │%n", complexityReport.score());

        if (complexityReport.isUnlikelyToWork()) {
            System.out.println("│                                                      │");
            System.out.println("│ ⚠  WARNING: This mod is UNLIKELY TO WORK            │");
            System.out.println("│    after transformation. Risk factors:                │");
            for (String factor : complexityReport.riskFactors()) {
                String truncated = factor.length() > 50 ? factor.substring(0, 47) + "..." : factor;
                System.out.printf("│    • %-50s │%n", truncated);
            }
            System.out.println("│                                                      │");
            System.out.println("│    You can still try with: aot --force <mod.jar>     │");
        } else {
            System.out.println("│ ✓  Mod appears suitable for transformation          │");
            if (!complexityReport.riskFactors().isEmpty()) {
                System.out.println("│    Minor risk factors:                                │");
                for (String factor : complexityReport.riskFactors()) {
                    String truncated = factor.length() > 50 ? factor.substring(0, 47) + "..." : factor;
                    System.out.printf("│    • %-50s │%n", truncated);
                }
            }
        }

        System.out.println("└──────────────────────────────────────────────────────┘");
        System.out.println();
    }

    /** AOT compile a mod (the preferred transformation method). */
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

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║           Retromod AOT Compilation                   ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        // complexity check before transforming
        com.retromod.gui.ModComplexityAnalyzer complexityAnalyzer =
            new com.retromod.gui.ModComplexityAnalyzer();
        com.retromod.gui.ModComplexityAnalyzer.ComplexityReport complexityReport =
            complexityAnalyzer.analyze(modPath);

        if (complexityReport.isUnlikelyToWork() && !forceTranslate) {
            System.out.println("⚠  WARNING: This mod is UNLIKELY TO WORK after transformation.");
            System.out.println();
            System.out.println("Risk factors:");
            for (String factor : complexityReport.riskFactors()) {
                System.out.println("  • " + factor);
            }
            System.out.println();
            System.out.println("Complexity score: " + complexityReport.score() + "/100");
            System.out.println();
            System.out.println("The mod uses features that Retromod cannot fully transform.");
            System.out.println("It will likely crash or behave incorrectly.");
            System.out.println();
            System.out.println("To try anyway, use:  aot --force " + modPath.getFileName());
            System.out.println();
            return;
        }

        if (complexityReport.isUnlikelyToWork() && forceTranslate) {
            System.out.println("⚠  Force mode: proceeding despite high complexity score ("
                + complexityReport.score() + ")");
            System.out.println();
        }

        AotCompiler compiler = new AotCompiler(shimRegistry, TARGET_MC_VERSION);

        System.out.println("Input:  " + modPath.getFileName());

        // Register the Forge->NeoForge deleted-class bridges (#52) before compiling so the AOT
        // transform rewrites references to them; embedIntoJar (below) places them per-mod.
        try {
            com.retromod.shim.forge.ForgeNeoForgeSynthetics.registerAll(
                    RetromodTransformer.getInstance());
        } catch (Exception e) {
            // AOT compilation continues without the bridges
        }

        long startTime = System.currentTimeMillis();
        Path result = compiler.compileModAot(modPath);
        long duration = System.currentTimeMillis() - startTime;

        // Embed referenced synthetics only when a new jar was produced; never touch the input jar.
        if (!result.equals(modPath)) {
            try {
                com.retromod.core.SyntheticEmbedder.embedIntoJar(
                        result, modPath.getFileName().toString(), RetromodTransformer.getInstance());
            } catch (Exception e) {
                // the AOT jar is otherwise complete
            }
        }

        System.out.println("Output: " + result.getFileName());
        System.out.printf("Time:   %d ms%n", duration);
        System.out.println();
        
        if (result.equals(modPath)) {
            System.out.println("✓ Mod is already compatible or was skipped.");
        } else {
            System.out.println("✓ AOT compilation complete!");
            System.out.println();
            System.out.println("The compiled mod includes:");
            System.out.println("  • Pre-transformed bytecode (fast loading)");
            System.out.println("  • Embedded API shims for removed functionality");
            System.out.println("  • JIT fallback markers for obfuscated code");
        }
        System.out.println();
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
        
        System.out.println("Transforming: " + modPath.getFileName());
        System.out.println("Output: " + outputPath.getFileName());
        
        ModVersionInfo info = detector.detectVersion(modPath);
        if (info == null) {
            System.err.println("Could not analyze mod.");
            System.exit(1);
        }

        String sourceMcVersion = info.targetMcVersion();
        if (sourceMcVersion == null || sourceMcVersion.isEmpty()) {
            System.err.println("Warning: Could not determine source MC version. Trying all shims...");
            RetromodTransformer transformer = RetromodTransformer.getInstance();
            for (VersionShim shim : shimRegistry.getAllShims()) {
                shim.registerRedirects(transformer);
            }
            transformJar(modPath, outputPath, transformer, info);
            // Embed referenced deleted-class synthetics: the all-shims pass registers redirects
            // onto them (e.g. the EventBus 7 bridge), so their classes must be copied in too or
            // the rewritten references dangle (NoClassDefFoundError). Matches the version-detected
            // path below.
            com.retromod.core.SyntheticEmbedder.embedIntoJar(
                    outputPath, modPath.getFileName().toString(), transformer);
            System.out.println("✓ Transformation complete (all shims applied): " + outputPath);
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

        // API shims are keyed on Fabric API release numbers, not MC versions, so the MC
        // version-graph BFS never reaches them. Mirror what RetromodPreLaunch does at runtime.
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
                // one bad API shim shouldn't kill the rest
            }
        }

        // Apply the vanilla 26.1 class-move table when targeting 26.1+ (the runtime entry points
        // do this via IntermediaryToMojangMapper). Register the moves directly rather than via
        // applyClassMovesOnly(), which host-gates on the auto-detected TARGET_MC_VERSION.
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
                // the chain + API shims still apply without the moves
            }

            // ResourceLocation/Identifier ctor -> factory, matching an in-game boot (CLI == runtime).
            com.retromod.mapping.IntermediaryToMojangMapper.registerIdentifierCtorRedirects(transformer);

            // Fabric intermediary->Mojang member mappings; without these a distributed Fabric
            // mod keeps its intermediary names and registers nothing. Fabric-only: NeoForge/Forge
            // mods are already Mojang-named, and applying these clobbers their Mojang fields.
            if ("fabric".equalsIgnoreCase(info.modLoaderType())) {
                try {
                    int memberMappings = com.retromod.mapping.IntermediaryToMojangMapper
                            .applyTo(transformer);
                    if (memberMappings > 0) {
                        System.out.println("Applied intermediary->Mojang member mappings ("
                                + memberMappings + ").");
                    }
                } catch (Exception e) {
                    // class moves already applied above
                }
            }

            // Register the NeoForge deleted-class bridges so embedIntoJar (below) can place them
            // per-mod. NeoForge OR Forge: a cross-loader mod shipping both tomls is detected as
            // "forge" yet runs on NeoForge; the embed is reference-gated.
            String synLoaderT = info.modLoaderType();
            if ("neoforge".equalsIgnoreCase(synLoaderT) || "forge".equalsIgnoreCase(synLoaderT)) {
                try {
                    com.retromod.shim.forge.ForgeNeoForgeSynthetics.registerAll(transformer);
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        if (chain.isEmpty() && apiApplied == 0 && classMovesApplied == 0) {
            System.out.println("No transformation needed or no shim available.");
            return;
        }
        if (apiApplied > 0) {
            System.out.println("Applied " + apiApplied + " API shim(s) alongside the version chain.");
        }
        if (classMovesApplied > 0) {
            System.out.println("Applied " + classMovesApplied + " vanilla 26.1 class move(s).");
        }

        transformJar(modPath, outputPath, transformer, info);
        // embed referenced deleted-class synthetics per-mod
        com.retromod.core.SyntheticEmbedder.embedIntoJar(
                outputPath, modPath.getFileName().toString(), transformer);
        System.out.println("✓ Transformation complete: " + outputPath);
        verifyIfRequested(outputPath, modPath.getFileName().toString(), args);
    }
    
    /** Embed removed APIs into a mod JAR. */
    private static void embedCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: embed <mod.jar>");
            System.exit(1);
        }
        
        Path modPath = Path.of(args[1]);
        
        System.out.println("Embedding APIs into: " + modPath.getFileName());
        
        ModVersionInfo info = detector.detectVersion(modPath);
        if (info == null) {
            System.err.println("Could not analyze mod.");
            System.exit(1);
        }
        
        ApiEmbedder embedder = new ApiEmbedder();
        embedder.embedRequiredShims(modPath, info);
        
        System.out.println("✓ API embedding complete");
    }
    
    /**
     * Register the auxiliary redirects an in-game boot applies on top of the version chain:
     * loader-matched API shims, the vanilla 26.1 class-move table, and the Fabric
     * intermediary->Mojang member mappings. Keep in sync with the inline block in
     * {@link #transformCommand}.
     *
     * @return a one-line summary of what was applied, or null if nothing extra applied
     */
    // Package-private for RetromodCliAuxRedirectsTest (loader-gating regression).
    static String registerAuxiliaryRedirects(
            RetromodTransformer transformer, ModVersionInfo info, List<VersionShim> chain) {
        java.util.Set<VersionShim> chainSet = new java.util.HashSet<>(chain);
        int apiApplied = 0;
        // shimRegistry is null only in unit tests exercising the gating directly.
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
                // one bad API shim shouldn't kill the rest
            }
        }

        int classMovesApplied = 0;
        int memberMappings = 0;
        if (com.retromod.core.RetromodVersion.isUnobfuscatedTarget(TARGET_MC_VERSION)) {
            // class relocations apply to every loader; NeoForge/Forge mods reference these
            // by their Mojang names too
            try {
                var moves = com.retromod.mapping.IntermediaryToMojangMapper
                        .getInstance().getClassMoves();
                for (var e : moves.entrySet()) {
                    transformer.registerClassRedirect(e.getKey(), e.getValue());
                    classMovesApplied++;
                }
            } catch (Exception e) {
                // the chain + API shims still apply without the moves
            }
            // ResourceLocation/Identifier ctor -> factory, matching an in-game boot so CLI output
            // equals the runtime's (both reach the shared helper). All loaders need it: NeoForge/Forge
            // mods construct ResourceLocation too, and the class move rewrites it to Identifier.
            com.retromod.mapping.IntermediaryToMojangMapper.registerIdentifierCtorRedirects(transformer);
            // Member mappings are Fabric-only: NeoForge/Forge mods are already Mojang-named,
            // and applying these clobbers their Mojang field refs.
            if ("fabric".equalsIgnoreCase(info.modLoaderType())) {
                try {
                    memberMappings = com.retromod.mapping.IntermediaryToMojangMapper.applyTo(transformer);
                } catch (Exception e) {
                    // class moves already applied above
                }
            }
            // Register the NeoForge deleted-class bridges so SyntheticEmbedder can place them
            // per-mod after transformJar. NeoForge OR Forge: a cross-loader mod shipping both
            // tomls is detected as "forge" yet loads on NeoForge.
            String synLoader = info.modLoaderType();
            if ("neoforge".equalsIgnoreCase(synLoader) || "forge".equalsIgnoreCase(synLoader)) {
                try {
                    com.retromod.shim.forge.ForgeNeoForgeSynthetics.registerAll(transformer);
                } catch (Exception e) {
                    // ignore
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
        
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║           Retromod Batch Processing                  ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Input:  " + modsFolder);
        System.out.println("Output: " + outputFolder);
        System.out.println("Mode:   " + (useAot ? "AOT Compilation" : "JIT Transform"));
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
                    System.out.println("SKIPPED (not a mod)");
                    skipped++;
                    continue;
                }
                
                // For 26.1+, all mods get metadata patching whether or not bytecode needs it.
                boolean needs26Patch = TARGET_MC_VERSION.startsWith("26.");
                boolean needsBytecodeTransform = info.needsTransformation(TARGET_MC_VERSION);
                // For a 26.x target, force the full bytecode transform on a mod whose own MC
                // version we can't read (null / unparseable / literal ${placeholder} metadata):
                // can't confirm it's 26.x-native, and a pre-26 mod needs the class moves /
                // polyfills / bridges the metadata-only branch skips.
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
                        // copy the AOT result; post-process for metadata below
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
                        // Same class moves + API shims + member mappings the single-mod
                        // `transform` layers on the chain.
                        registerAuxiliaryRedirects(transformer, info, chain);
                        transformJar(modFile.toPath(), outputPath, transformer, info);
                    }
                    status = "OK";
                } else if (!needs26Patch) {
                    Files.copy(modFile.toPath(), outputPath,
                        StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("COPIED (already compatible)");
                    skipped++;
                    continue;
                } else {
                    // copy first, then patch metadata
                    Files.copy(modFile.toPath(), outputPath,
                        StandardCopyOption.REPLACE_EXISTING);
                    status = "PATCHED (version constraints)";
                }

                if (needs26Patch) {
                    patchModMetadata(outputPath);
                    // Embed referenced deleted-class synthetics per-mod, whether the mod was
                    // transformed/AOT-compiled or only metadata-patched: a "compatible by version"
                    // mod can still reference a class 26.x deleted. Reference-gated.
                    String embLoader = info.modLoaderType();
                    if ("neoforge".equalsIgnoreCase(embLoader) || "forge".equalsIgnoreCase(embLoader)) {
                        try {
                            RetromodTransformer rt = RetromodTransformer.getInstance();
                            com.retromod.shim.forge.ForgeNeoForgeSynthetics.registerAll(rt);
                            com.retromod.core.SyntheticEmbedder.embedIntoJar(
                                    outputPath, modFile.getName(), rt);
                        } catch (Exception e) {
                            // the mod is otherwise complete
                        }
                    }
                }

                long elapsed = System.currentTimeMillis() - start;
                totalTime += elapsed;
                System.out.printf("%s (%d ms)%n", status, elapsed);
                processed++;
                
            } catch (Exception e) {
                System.out.println("FAILED: " + e.getMessage());
                failed++;
            }
        }
        
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════");
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
        
        System.out.println();
        System.out.println("API Differences: " + loader + " " + v1 + " → " + v2);
        System.out.println("════════════════════════════════════════════════════════");
        System.out.println();

        List<VersionShim> chain = shimRegistry.findShimChain(loader, v1, v2);

        if (chain.isEmpty()) {
            System.out.println("No shim data available for this transition.");
            return;
        }
        
        for (VersionShim shim : chain) {
            System.out.println("┌─ " + shim.getShimName() + " ─────────────────────────────");
            
            RetromodTransformer temp = RetromodTransformer.getInstance();
            shim.registerRedirects(temp);
            
            System.out.println("│ Method redirects: " + temp.getMethodRedirectCount());
            System.out.println("│ Class redirects:  " + temp.getClassRedirectCount());
            
            String[] shimClasses = shim.getShimClasses();
            if (shimClasses.length > 0) {
                System.out.println("│ Embedded shims:");
                for (String cls : shimClasses) {
                    System.out.println("│   • " + cls);
                }
            }
            System.out.println("└──────────────────────────────────────────────────────");
            System.out.println();
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
                    System.out.println("✓ Download complete");
                } else {
                    System.out.println("Skipped - no download performed.");
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
                System.out.println("✓ Preload complete (or skipped on user decline)");
            }
            case "clear" -> {
                archiveManager.clearCache();
                System.out.println("✓ Cache cleared");
            }
            default -> System.err.println("Unknown action: " + action);
        }
    }

    /** Consent prompt for a single archive download; --yes skips it for scripted/CI use. */
    private static boolean promptForDownloadConsent(String loader, String version, boolean autoYes) {
        System.out.println();
        System.out.println("────────────────────────────────────────────────────");
        System.out.println("  Retromod is about to download a file from the");
        System.out.println("  internet. This is the only network operation");
        System.out.println("  Retromod will perform; it does not initiate any");
        System.out.println("  other downloads without explicit consent.");
        System.out.println();
        System.out.println("  Loader:  " + loader);
        System.out.println("  MC ver:  " + version);
        System.out.println("  Source:  Maven (fabricmc.net / neoforged.net /");
        System.out.println("           minecraftforge.net depending on loader)");
        System.out.println("────────────────────────────────────────────────────");

        if (autoYes) {
            System.out.println("  --yes flag passed: proceeding without prompt.");
            return true;
        }

        System.out.print("Proceed with download? [y/N] ");
        try {
            String line = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in)).readLine();
            return line != null && (line.equalsIgnoreCase("y") || line.equalsIgnoreCase("yes"));
        } catch (java.io.IOException e) {
            System.err.println("Could not read response - treating as 'no'.");
            return false;
        }
    }

    /** Consent prompt for the bulk preload action; --yes skips it for scripted/CI use. */
    private static boolean promptForPreloadConsent(boolean autoYes) {
        System.out.println();
        System.out.println("────────────────────────────────────────────────────");
        System.out.println("  Retromod is about to bulk-download API archives");
        System.out.println("  for every known MC version (~22 JARs total,");
        System.out.println("  several MB each). All come from official Maven");
        System.out.println("  repositories (fabricmc.net / neoforged.net).");
        System.out.println("────────────────────────────────────────────────────");

        if (autoYes) {
            System.out.println("  --yes flag passed: proceeding without prompt.");
            return true;
        }

        System.out.print("Proceed with preload? [y/N] ");
        try {
            String line = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in)).readLine();
            return line != null && (line.equalsIgnoreCase("y") || line.equalsIgnoreCase("yes"));
        } catch (java.io.IOException e) {
            System.err.println("Could not read response - treating as 'no'.");
            return false;
        }
    }
    
    /** List all registered shims. */
    private static void shimsCommand(String[] args) {
        System.out.println();
        System.out.println("Registered Version Shims");
        System.out.println("════════════════════════════════════════════════════════");
        System.out.println();
        
        List<VersionShim> allShims = shimRegistry.getAllShims();
        
        String currentLoader = "";
        for (VersionShim shim : allShims) {
            if (!shim.getModLoaderType().equals(currentLoader)) {
                currentLoader = shim.getModLoaderType();
                System.out.println("┌─ " + currentLoader.toUpperCase() + " ────────────────────────────────────────");
            }
            System.out.printf("│  %s → %s  (%s)%n",
                shim.getSourceVersion(),
                shim.getTargetVersion(),
                shim.getShimName());
        }
        System.out.println("└──────────────────────────────────────────────────────");
        System.out.println();
        System.out.println("Total: " + allShims.size() + " shims registered");
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
                        // bounded read against falsified-size entries
                        byte[] data = com.retromod.util.ZipSecurity.safeReadAllBytes(is);

                        if (entry.getName().endsWith(".class")) {
                            if (shouldTransformClass(entry.getName(), info)) {
                                data = transformer.transformClass(data, entry.getName());
                            }
                            // neutralize blocklisted mixins; always runs since a mixin can need
                            // it even when its bytecode otherwise needs no transformation
                            data = mixinStripper.stripBlocklistedHandlers(data);
                            // Forge 26.2 only (self-gating): strip @Mod.EventBusSubscriber
                            // where EventBus 7's auto-subscriber would throw (#85)
                            data = com.retromod.shim.forge.ForgeEventBusSynthetics
                                    .stripLenientAutoSubscriber(data);
                            // 1.12.2 only (self-gating): modernize @Mod(modid=...) and wire
                            // the @Mod.EventHandler lifecycle (#103/#108/#117)
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
            System.out.println("✓ Verification passed");
        } else {
            System.out.println("✗ Verification found " + result.issueCount() + " issue(s)");
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
    
    /** Transform a legacy mod (1.8-1.20.x) to run on modern 26.1. */
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
        
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("         Retromod LEGACY Transformation");
        System.out.println("   Transform mods from MC 1.8+ to run on 26.1");
        System.out.println("=================================================================");
        System.out.println();

        LegacyModSupport legacySupport = new LegacyModSupport(
            modPath.getParent(), TARGET_MC_VERSION
        );

        System.out.println("Analyzing mod: " + modPath.getFileName());
        LegacyModAnalysis analysis = legacySupport.analyzeMod(modPath);
        
        System.out.println();
        System.out.println("Analysis Results:");
        System.out.println("  Mod Loader:      " + analysis.modLoader);
        System.out.println("  Target MC:       " + analysis.targetMcVersion);
        System.out.println("  Source Epoch:    " + analysis.sourceEpoch.name);
        System.out.println("  Java Version:    " + analysis.sourceJavaVersion);
        System.out.println("  Class File Ver:  " + analysis.classFileVersion);
        System.out.println("  Complexity:      " + analysis.complexity);
        System.out.println("  Virtual Loader:  " + (analysis.needsVirtualLoader ? "Required" : "Not needed"));
        System.out.println("  Transformations: " + analysis.epochTransitions.size());
        System.out.println();
        
        if (analysis.epochTransitions.isEmpty()) {
            System.out.println("Mod appears to be compatible - no transformation needed.");
            return;
        }
        
        System.out.println("Transformation Plan:");
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
        System.out.println("=================================================================");
        System.out.println("                    Transformation Complete");
        System.out.println("=================================================================");
        System.out.println();
        System.out.println("Output: " + result);
        System.out.println();
        System.out.println("Notes:");
        System.out.println("  - The transformed mod may require additional testing");
        System.out.println("  - Some features may not work if APIs were removed entirely");
        System.out.println("  - Check the log output above for any warnings");
        System.out.println();
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
            System.err.println("Error: Not a valid directory: " + minecraftDir);
            return;
        }
        
        Path modsDir = minecraftDir.resolve("mods");
        Path configDir = minecraftDir.resolve("config");
        
        if (!Files.isDirectory(modsDir)) {
            System.err.println("Error: No mods folder found in: " + minecraftDir);
            return;
        }
        
        Files.createDirectories(configDir);
        
        System.out.println("=================================================================");
        System.out.println("       Retromod Dependency Override Generator");
        System.out.println("=================================================================");
        System.out.println();
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
        
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("Generated dependency overrides for " + modIds.size() + " mods");
        System.out.println("File: " + overridesFile);
        System.out.println("=================================================================");
        System.out.println();
        System.out.println("IMPORTANT:");
        System.out.println("  This bypasses Fabric's version check so mods can LOAD.");
        System.out.println("  However, mods may still CRASH if APIs are incompatible.");
        System.out.println("  Use 'retromod prepare' to also transform the mods.");
        System.out.println();
    }
    
    /** Full preparation: generate overrides + transform all mods. */
    private static void prepareCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: retromod prepare <minecraft-dir> [--aot]");
            System.err.println("  minecraft-dir: Path to .minecraft folder");
            System.err.println("  --aot: Use AOT compilation (recommended)");
            return;
        }
        
        Path minecraftDir = Paths.get(args[1]);
        boolean useAot = Arrays.asList(args).contains("--aot");
        
        System.out.println("=================================================================");
        System.out.println("         Retromod Full Preparation");
        System.out.println("=================================================================");
        System.out.println();
        System.out.println("Minecraft directory: " + minecraftDir);
        System.out.println("Mode: " + (useAot ? "AOT Compilation" : "JIT Transform"));
        System.out.println();
        
        System.out.println("Step 1: Generating dependency overrides...");
        overridesCommand(new String[]{"overrides", args[1], TARGET_MC_VERSION});

        System.out.println("Step 2: Transforming mods...");
        Path modsDir = minecraftDir.resolve("mods");
        
        if (useAot) {
            batchCommand(new String[]{"batch", modsDir.toString(), "--aot"});
        } else {
            batchCommand(new String[]{"batch", modsDir.toString()});
        }
        
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("                    Preparation Complete");
        System.out.println("=================================================================");
        System.out.println();
        System.out.println("Your mods folder has been prepared for " + TARGET_MC_VERSION);
        System.out.println();
        System.out.println("Note: Some mods may still have runtime issues if they use");
        System.out.println("      APIs that have changed significantly.");
        System.out.println();
    }
    
    /**
     * Help mod developers update their own mod to a newer MC version: scan the JAR and emit a
     * migration guide of API changes with find-and-replace suggestions for their source.
     */
    private static void devhelpCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: devhelp <mod.jar> [--to <version>]");
            System.err.println();
            System.err.println("Scans your mod and tells you exactly what needs to change");
            System.err.println("to update it to a newer Minecraft version.");
            System.err.println();
            System.err.println("Examples:");
            System.err.println("  retromod devhelp mymod-1.21.4.jar");
            System.err.println("  retromod devhelp mymod-1.21.4.jar --to 26.1");
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
        System.out.println("=================================================================");
        System.out.println("  Retromod Developer Migration Helper");
        System.out.println("=================================================================");
        System.out.println();

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
            System.out.println("  Your mod already targets " + targetVersion + ". Nothing to do!");
            return;
        }

        List<VersionShim> chain = shimRegistry.findShimChain(
            info.modLoaderType(), sourceVersion, targetVersion);

        if (chain.isEmpty()) {
            System.out.println("  No migration data available for " + sourceVersion + " -> " + targetVersion);
            return;
        }

        System.out.println("=================================================================");
        System.out.println("  MIGRATION GUIDE: " + sourceVersion + " -> " + targetVersion);
        System.out.println("=================================================================");
        System.out.println();

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

        System.out.println("=================================================================");
        System.out.println("  SUMMARY");
        System.out.println("=================================================================");
        System.out.println();
        System.out.println("  " + chain.size() + " version steps");
        System.out.println("  " + totalClasses + " class renames");
        System.out.println("  " + totalMethods + " method changes");
        System.out.println();
        System.out.println("  To update your mod:");
        System.out.println("  1. Apply the class/method renames above to your source code");
        System.out.println("  2. Update your fabric.mod.json / mods.toml version targets");
        System.out.println("  3. Rebuild against the new Minecraft version");
        System.out.println();
        System.out.println("  Or just use Retromod and skip the manual work :)");
        System.out.println();
    }

    /** Score a mod JAR for compatibility with the target MC version. */
    private static void scoreCommand(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: score <mod.jar> [--mc-jar <path>] [--fabric-api <path>] [--verbose]");
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

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--verbose", "-v" -> verbose = true;
                case "--mc-jar" -> {
                    if (i + 1 < args.length) mcJarPath = Path.of(args[++i]);
                }
                case "--fabric-api" -> {
                    if (i + 1 < args.length) fabricApiPath = Path.of(args[++i]);
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

        ModVersionInfo info = detector.detectVersion(modPath);

        System.err.println("Analyzing: " + modPath.getFileName());
        ModScorer.ScoreResult result = scorer.analyze(modPath, info);

        String modName = info != null && info.modId() != null
                ? info.modId() + " " + (info.modVersion() != null ? info.modVersion() : "")
                : modPath.getFileName().toString();
        String sourceLine = info != null && info.targetMcVersion() != null
                ? info.modLoaderType() + " " + info.targetMcVersion()
                : "unknown";

        int W = 60; // box width
        System.out.println();
        printBoxTop(W);
        printBoxLine(W, "  Retromod Compatibility Score");
        printBoxSep(W);
        printBoxLine(W, "  Mod: " + modName.trim());
        printBoxLine(W, "  Source: " + sourceLine);
        printBoxLine(W, "  Target: MC " + TARGET_MC_VERSION);
        printBoxSep(W);
        printBoxLine(W, "  Overall Score: " + result.overallScore + "/100");
        printBoxLine(W, "");

        String clsIcon = result.classScore >= 75 ? "OK" : (result.classScore >= 50 ? "!!" : "XX");
        String mtdIcon = result.methodScore >= 75 ? "OK" : (result.methodScore >= 50 ? "!!" : "XX");
        String fldIcon = result.fieldScore >= 75 ? "OK" : (result.fieldScore >= 50 ? "!!" : "XX");
        String mixIcon = result.mixinScore >= 75 ? "OK" : (result.mixinScore >= 50 ? "!!" : "XX");

        printBoxLine(W, String.format("  [%s] Class references:  %d/%d resolvable (%d%%)",
                clsIcon, result.resolvableClasses, result.totalClasses, result.classScore));
        printBoxLine(W, String.format("  [%s] Method calls:      %d/%d redirectable (%d%%)",
                mtdIcon, result.resolvableMethods + result.redirectedMethods, result.totalMethods, result.methodScore));
        printBoxLine(W, String.format("  [%s] Field accesses:    %d/%d resolvable (%d%%)",
                fldIcon, result.resolvableFields + result.redirectedFields, result.totalFields, result.fieldScore));
        printBoxLine(W, String.format("  [%s] Mixin targets:     %d/%d valid (%d%%)",
                mixIcon, result.validMixins, result.totalMixins, result.mixinScore));
        printBoxLine(W, "");
        printBoxLine(W, "  Estimated: " + result.getVerdict());
        printBoxBottom(W);
        System.out.println();

        if (verbose) {
            if (!result.missingClasses.isEmpty()) {
                System.out.println("Missing Classes (" + result.missingClasses.size() + "):");
                for (String cls : result.missingClasses) {
                    System.out.println("  - " + cls.replace('/', '.'));
                }
                System.out.println();
            }

            if (!result.missingMethods.isEmpty()) {
                System.out.println("Unresolvable Method Calls (" + result.missingMethods.size() + "):");
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
                System.out.println("Unresolvable Field Accesses (" + result.missingFields.size() + "):");
                for (String f : result.missingFields) {
                    System.out.println("  - " + f);
                }
                System.out.println();
            }

            if (!result.brokenMixins.isEmpty()) {
                System.out.println("Broken Mixin Targets (" + result.brokenMixins.size() + "):");
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

    private static void printBoxTop(int w) {
        System.out.print("+");
        System.out.print("=".repeat(w));
        System.out.println("+");
    }

    private static void printBoxSep(int w) {
        System.out.print("+");
        System.out.print("-".repeat(w));
        System.out.println("+");
    }

    private static void printBoxBottom(int w) {
        System.out.print("+");
        System.out.print("=".repeat(w));
        System.out.println("+");
    }

    private static void printBoxLine(int w, String text) {
        if (text.length() > w - 2) text = text.substring(0, w - 2);
        System.out.printf("| %-" + (w - 2) + "s |%n", text);
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
        System.out.println("=================================================================");
        System.out.println("           Retromod Auto-Fix Analysis");
        System.out.println("=================================================================");
        System.out.println();
        System.out.println("Log file: " + logFile);
        System.out.println("Mode:     " + (apply ? "APPLY (registering fixes)" : "ANALYZE (dry run)"));
        System.out.println();

        com.retromod.core.AutoFixEngine engine = new com.retromod.core.AutoFixEngine();

        List<com.retromod.core.AutoFixEngine.AppliedFix> fixes;
        if (apply) {
            // register all shims first so the transformer has context
            RetromodTransformer transformer = RetromodTransformer.getInstance();
            for (VersionShim shim : shimRegistry.getAllShims()) {
                try {
                    shim.registerRedirects(transformer);
                } catch (Exception e) {
                    // ignore shim errors in CLI mode
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

        // group fixes by error type for cleaner output
        Map<String, List<com.retromod.core.AutoFixEngine.AppliedFix>> byType = new LinkedHashMap<>();
        for (com.retromod.core.AutoFixEngine.AppliedFix fix : fixes) {
            byType.computeIfAbsent(fix.errorType(), k -> new ArrayList<>()).add(fix);
        }

        for (Map.Entry<String, List<com.retromod.core.AutoFixEngine.AppliedFix>> entry : byType.entrySet()) {
            System.out.println("-----------------------------------------------------------------");
            System.out.println("  " + entry.getKey() + " (" + entry.getValue().size() + " occurrence(s))");
            System.out.println("-----------------------------------------------------------------");

            for (com.retromod.core.AutoFixEngine.AppliedFix fix : entry.getValue()) {
                System.out.println("  Error:  " + fix.description());
                System.out.println("  " + (apply ? "Action:" : "Suggestion:") + " " + fix.action());
                System.out.println();
            }
        }

        System.out.println("=================================================================");
        if (apply) {
            System.out.println("  " + fixes.size() + " fix(es) applied.");
            System.out.println("  Retransform your mods to incorporate the fixes:");
            System.out.println("    retromod batch <mods-folder> --aot");
        } else {
            System.out.println("  " + fixes.size() + " fix(es) suggested.");
            System.out.println("  To apply fixes, re-run with --apply:");
            System.out.println("    retromod autofix " + logFile + " --apply");
        }
        System.out.println("=================================================================");
        System.out.println();
    }

    private static void printUsage() {
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("           Retromod CLI v" + VERSION);
        System.out.println("   Backwards Compatibility Layer for Minecraft Mods");
        System.out.println("");
        System.out.println("   Supports: Fabric, Forge, NeoForge");
        System.out.println("   Versions: 1.8 -> 26.1");
        System.out.println("=================================================================");
        System.out.println();
        System.out.println("Usage: retromod <command> [options]");
        System.out.println();
        System.out.println("Modern Commands (1.21.x):");
        System.out.println("  analyze <mod.jar>              Analyze a mod's compatibility");
        System.out.println("  aot <mod.jar>                  AOT compile (recommended)");
        System.out.println("  transform <mod.jar>            JIT-style transformation");
        System.out.println("  embed <mod.jar>                Embed removed APIs only");
        System.out.println("  batch <folder> [--aot]         Process all mods in folder");
        System.out.println();
        System.out.println("Preparation Commands (IMPORTANT for cross-version):");
        System.out.println("  prepare <mc-dir> [--aot]       Full prep: overrides + transform");
        System.out.println("  overrides <mc-dir>             Generate dependency overrides only");
        System.out.println();
        System.out.println("Legacy Commands (1.8-1.20.x -> 26.1):");
        System.out.println("  legacy <mod.jar>               Transform legacy mod to 26.1");
        System.out.println();
        System.out.println("Developer Commands:");
        System.out.println("  devhelp <mod.jar> [target]     Show what to change when updating your mod");
        System.out.println("  migrate <mod.jar> [target]     Alias for devhelp");
        System.out.println();
        System.out.println("Analysis Commands:");
        System.out.println("  score <mod.jar> [options]      Score mod compatibility with 26.1");
        System.out.println("  autofix <log-file> [--apply]   Analyze crash log, suggest/apply fixes");
        System.out.println();
        System.out.println("Utility Commands:");
        System.out.println("  diff <loader> <v1> <v2>        Show API differences");
        System.out.println("  archive <action>               Manage API archives");
        System.out.println("  shims                          List registered shims");
        System.out.println("  help                           Show this help");
        System.out.println("  version                        Show version");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --output <path>                Specify output file/folder");
        System.out.println("  --aot                          Use AOT compilation in batch");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # RECOMMENDED: Full preparation for running old mods");
        System.out.println("  retromod prepare ~/.minecraft --aot");
        System.out.println();
        System.out.println("  # Transform a 1.21.8 Fabric mod to 26.1");
        System.out.println("  retromod aot mymod-1.21.8.jar");
        System.out.println();
        System.out.println("  # Transform a legacy 1.12.2 Forge mod to 26.1");
        System.out.println("  retromod legacy oldmod-1.12.2.jar");
        System.out.println();
        System.out.println("  # Batch process all mods");
        System.out.println("  retromod batch ./mods --aot");
        System.out.println();
        System.out.println("  # See what API changes you need for updating your own mod");
        System.out.println("  retromod devhelp mymod-1.21.4.jar 26.1");
        System.out.println();
        System.out.println("  # Cross-mod gap report - what's missing across a whole mods folder");
        System.out.println("  retromod gaps ./mods --mc-jar minecraft-26.1.jar");
        System.out.println();
        System.out.println("Target Minecraft version: " + TARGET_MC_VERSION);
        System.out.println();
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
            System.err.println("NOTE: verification is not enabled.");
            System.err.println("Re-run with: -Dretromod.verifyTransforms=true");
            System.err.println("  (e.g. mvn exec:java -Dexec.mainClass=... -Dretromod.verifyTransforms=true ...)");
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
                    System.err.println("  FAILED: " + e.getMessage());
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
