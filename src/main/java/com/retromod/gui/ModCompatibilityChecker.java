/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.core.FabricModTransformer;
import com.retromod.core.ModVersionDetector;
import com.retromod.core.QuiltModTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.aot.AotCompiler;
import com.retromod.shim.ShimRegistry;
import com.retromod.embedder.ModVersionInfo;
import com.retromod.util.ArchivePublication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Mod compatibility checker, used internally by the GUI.
 * 
 * Handles transformation for all mod loaders:
 * - Fabric: Uses FabricModTransformer (updates fabric.mod.json in JAR)
 * - Forge/NeoForge: Uses AotCompiler (bytecode transformation)
 */
public class ModCompatibilityChecker {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private final Path modsFolder;
    private final Path backupsFolder;
    private final ModVersionDetector detector;
    private final String targetVersion;
    private final ShimRegistry shimRegistry;
    
    public record IncompatibleMod(
        Path jarPath,
        String modName,
        String sourceVersion,
        String loaderType,
        String reason
    ) {}
    
    public ModCompatibilityChecker(Path gameDir) {
        this.modsFolder = gameDir.resolve("mods");
        this.backupsFolder = modsFolder.resolve("retromod-backups");
        this.detector = new ModVersionDetector();
        this.targetVersion = RetromodVersion.TARGET_MC_VERSION;
        this.shimRegistry = loadShimRegistry();
    }
    
    /**
     * Analyze a single mod JAR.
     */
    public IncompatibleMod analyzeJar(Path jarPath) {
        try {
            ModVersionInfo info = detector.detectVersion(jarPath);
            boolean sourceVersionUnknown = info != null
                    && isUnknownSourceVersion(info.targetMcVersion());
            if (info != null
                    && (info.needsTransformation(targetVersion)
                            || sourceVersionUnknown)) {
                String sourceVersion = info.targetMcVersion();
                String loader = info.modLoaderType();
                
                String reason;
                if (sourceVersionUnknown) {
                    reason = "Source Minecraft version is not declared";
                } else if (!sourceVersion.startsWith("1.21")) {
                    reason = "Version: " + sourceVersion + " to " + targetVersion;
                } else if ("forge".equals(loader)) {
                    reason = "Forge → NeoForge migration needed";
                } else {
                    reason = "Version mismatch";
                }
                
                return new IncompatibleMod(
                    jarPath,
                    info.modId() != null ? info.modId() : jarPath.getFileName().toString(),
                    sourceVersion,
                    loader,
                    reason
                );
            }
        } catch (Exception e) {
            LOGGER.debug("Could not analyze {}: {}", jarPath.getFileName(), e.getMessage());
        }
        return null;
    }

    private static boolean isUnknownSourceVersion(String sourceVersion) {
        return sourceVersion == null || sourceVersion.isBlank()
                || sourceVersion.contains("$")
                || !sourceVersion.matches(".*\\d+\\.\\d+.*");
    }
    
    /**
     * Transform a mod JAR and copy to mods folder.
     * 
     * Uses the appropriate transformer based on mod loader:
     * - Fabric: FabricModTransformer (updates fabric.mod.json directly)
     * - Quilt: QuiltModTransformer (uses Fabric bytecode repairs and updates quilt.mod.json)
     * - Forge/NeoForge: AotCompiler (bytecode transformation)
     * 
     * @param sourceJar The original mod JAR (from user's selection)
     * @return Path to the transformed JAR in the mods folder
     */
    public Path transformAndInstall(Path sourceJar) throws IOException {
        LOGGER.info("Transforming: {}", sourceJar.getFileName());
        
        // Create mods folder if needed
        Files.createDirectories(modsFolder);
        Files.createDirectories(backupsFolder);
        
        // Detect mod type
        ModVersionInfo info = detector.detectVersion(sourceJar);
        String loaderType = info != null ? info.modLoaderType() : "unknown";

        if (info != null) {
            for (VersionShim shim : shimRegistry.findApiShimsForLoader(
                    loaderType, targetVersion)) {
                try {
                    shim.registerRedirects(com.retromod.core.RetromodTransformer.getInstance());
                } catch (RuntimeException error) {
                    LOGGER.debug("Could not register optional API shim {}: {}",
                            shim.getShimName(), error.getMessage());
                }
            }
        }
        
        Path transformed;
        
        if ("fabric".equals(loaderType)) {
            // Use FabricModTransformer, which updates fabric.mod.json in the JAR
            // so no fabric_loader_dependencies.json is needed!
            LOGGER.info("Using Fabric transformer (will update fabric.mod.json)");
            FabricModTransformer fabricTransformer = new FabricModTransformer(targetVersion);
            transformed = fabricTransformer.transformMod(sourceJar, modsFolder);
        } else if ("quilt".equals(loaderType)) {
            LOGGER.info("Using Quilt transformer (will update quilt.mod.json)");
            QuiltModTransformer quiltTransformer = new QuiltModTransformer(targetVersion);
            transformed = quiltTransformer.transformMod(sourceJar, modsFolder);
        } else {
            // Use AotCompiler for Forge/NeoForge
            LOGGER.info("Using AOT compiler for {}", loaderType);
            AotCompiler compiler = new AotCompiler(shimRegistry, targetVersion);
            
            Path tempTransformed = compiler.compileModAot(sourceJar, true);
            
            // Copy to mods folder with -retromod suffix
            String originalName = sourceJar.getFileName().toString();
            String newName = originalName.replace(".jar", "-retromod.jar");
            transformed = modsFolder.resolve(newName);
            
            ArchivePublication.copyReplacing(tempTransformed, transformed);
        }
        
        LOGGER.info("Installed transformed mod: {}", transformed.getFileName());
        return transformed;
    }

    /** Loads packaged providers while allowing lite builds to omit optional implementations. */
    static ShimRegistry loadShimRegistry() {
        ShimRegistry registry = new ShimRegistry();
        var providers = ServiceLoader.load(VersionShim.class).iterator();
        while (true) {
            try {
                if (!providers.hasNext()) break;
                registry.register(providers.next());
            } catch (ServiceConfigurationError error) {
                LOGGER.debug("Could not load an optional version shim: {}", error.getMessage());
            }
        }
        return registry;
    }
    
    /**
     * Get the mods folder path.
     */
    public Path getModsFolder() {
        return modsFolder;
    }
}
