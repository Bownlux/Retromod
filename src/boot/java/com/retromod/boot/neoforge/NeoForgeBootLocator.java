/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.boot.neoforge;

import com.retromod.boot.BootCore;
import com.retromod.boot.BootLog;
import com.retromod.boot.InPlaceBoot;
import com.retromod.boot.LoaderHooks;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;

/** Starts Retromod on NeoForge 20.5 to 26.3 (FML 3 to 12) through the candidate-locator SPI. */
public final class NeoForgeBootLocator implements IModFileCandidateLocator {

    /** Must match {@code Retromod-Boot-Generation} in the jars built for these hosts. */
    public static final String GENERATION = "BC";

    // Above ModsFolderLocator (0) and below the built-in game locators (1000). At equal
    // priority the built-ins run first, so 0 would run after the mods folder scan.
    static final int PRIORITY = 100;

    private final BootLog log = BootLog.create();

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        try {
            // Read the game folders and host versions, then pick the Retromod runtime to load.
            Path gameDir = LoaderHooks.fmlPath(LoaderHooks.NEOFORGE_FML_PATHS, "GAMEDIR");
            Path modsDir = LoaderHooks.fmlPath(LoaderHooks.NEOFORGE_FML_PATHS, "MODSDIR");
            Object versions = versionInfo(context);
            String hostMinecraft = versionField(versions, "mcVersion");
            Optional<BootCore.Prepared> prepared = BootCore.prepare(gameDir, modsDir, GENERATION,
                    hostMinecraft, NeoForgeBootLocator.class, System.getProperties(), log);
            if (prepared.isEmpty()) return;
            hideBootstrapsFromModsScan(context, prepared.get());

            // Add the runtime jar. Only calls that link on every FML from 3 to 12 are used here:
            // methods that changed between FML 9 and 10, such as any taking JarContents, never are.
            Path runtime = prepared.get().runtimeJar();
            Optional<?> added = pipeline.addPath(runtime, ModFileDiscoveryAttributes.DEFAULT,
                    IncompatibleFileReporting.ERROR);
            if (added.isEmpty()) {
                log.warn("NeoForge did not accept the Retromod runtime at " + runtime
                        + ". The loading screen or log lists the reason.");
            } else {
                log.info("Loading Retromod " + prepared.get().source().version() + " from "
                        + BootCore.displayPath(gameDir, runtime));
                // In-place mode runs only once the runtime is in.
                applyInPlace(context, pipeline, prepared.get(), gameDir, modsDir, hostMinecraft,
                        versionField(versions, "neoForgeVersion"));
            }
        } catch (Throwable failure) {
            // A bootstrap failure must never stop the loader. The game starts without Retromod.
            log.error("Retromod could not start: " + LoaderHooks.unwrap(failure)
                    + ". Retromod is inactive for this launch. Please report this with "
                    + "logs/latest.log.");
        }
    }

    /** Hides old mods from the mods folder scan and loads their cached translations instead. */
    private void applyInPlace(ILaunchContext context, IDiscoveryPipeline pipeline,
            BootCore.Prepared prepared, Path gameDir, Path modsDir, String hostMinecraft,
            String loaderBuild) {
        Optional<InPlaceBoot.Decision> decision = InPlaceBoot.run(prepared, gameDir, modsDir,
                hostMinecraft, loaderBuild, InPlaceBoot.NEOFORGE_TOML, "files", log);
        if (decision.isEmpty()) return;
        // Mark each original as located so the mods folder scan skips it and the file in mods/
        // is never changed. addLocated is public API on every FML from 3 to 12.
        try {
            Method addLocated = ILaunchContext.class.getMethod("addLocated", Path.class);
            for (Path original : decision.get().hide()) {
                addLocated.invoke(context, original);
            }
        } catch (Throwable failure) {
            log.warn("Could not keep original mods out of the mod list ("
                    + LoaderHooks.unwrap(failure) + "). Please report this with "
                    + "logs/latest.log.");
        }
        // Add each translated copy. One that fails is logged and the others still load.
        for (Path copy : decision.get().serveFiles()) {
            try {
                Optional<?> added = pipeline.addPath(copy, ModFileDiscoveryAttributes.DEFAULT,
                        IncompatibleFileReporting.WARN_ALWAYS);
                if (added.isEmpty()) {
                    log.warn("NeoForge did not accept Retromod's translation " + copy
                            + ". The loading screen or log lists the reason.");
                }
            } catch (Throwable failure) {
                log.error("Could not load Retromod's translation " + copy + ": "
                        + LoaderHooks.unwrap(failure));
            }
        }
    }

    /** Marks every Retromod bootstrap in {@code mods/} as located so the mods folder scan skips it. */
    private void hideBootstrapsFromModsScan(ILaunchContext context, BootCore.Prepared prepared) {
        // FML does this for service jars, but FML 3 to 9 only marks jars whose module reached a
        // layer. Two Retromod files share the module name retromod.boot, so modlauncher keeps one
        // and the other would load as a mod through its toml copy.
        if (context == null) return;
        try {
            // Looked up by name because the compile stub declares only what the older locators
            // use. Every FML from 3 to 12 has the method.
            var addLocated = ILaunchContext.class.getMethod("addLocated", Path.class);
            // These paths come from Files.list(MODSDIR), the same form the mods folder scan compares.
            for (BootCore.BootJar jar : prepared.allBootstraps()) {
                addLocated.invoke(context, jar.path());
            }
        } catch (Throwable failure) {
            log.warn("Could not keep extra Retromod files out of the mod list: "
                    + LoaderHooks.unwrap(failure) + ". Delete the older Retromod files from "
                    + "mods/.");
        }
    }

    /** FML's {@code VersionInfo}, or null when the host has none. */
    private static Object versionInfo(ILaunchContext context) {
        // Both lookups go by name so this class links on every FML from 3 to 12.
        // FML 10 and newer: ILaunchContext.getVersions().
        if (context != null) {
            try {
                Object versions = ILaunchContext.class.getMethod("getVersions").invoke(context);
                if (versions != null) return versions;
            } catch (Throwable unavailable) {
                // FML 3 to 9
            }
        }
        // FML 3 to 9: the static FMLLoader.versionInfo().
        try {
            Class<?> loader = Class.forName("net.neoforged.fml.loading.FMLLoader", false,
                    NeoForgeBootLocator.class.getClassLoader());
            return loader.getMethod("versionInfo").invoke(null);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** One component of {@code VersionInfo}, such as {@code mcVersion}, or null. */
    private static String versionField(Object versions, String accessor) {
        if (versions == null) return null;
        try {
            Object value = versions.getClass().getMethod(accessor).invoke(versions);
            return value == null ? null : value.toString();
        } catch (Throwable unavailable) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "retromod";
    }
}
