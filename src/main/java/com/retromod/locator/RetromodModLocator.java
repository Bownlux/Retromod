/*
 * Retromod - NeoForge mod-file locator for the Retromod-owned mods subfolder.
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.locator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * Feeds jars from {@code mods/Retromod/} into NeoForge mod discovery, so Retromod and its
 * transformed {@code *-retromod.jar} outputs can live outside the top-level {@code mods/} folder.
 *
 * <p>CurseForge rejects modpack exports containing jars it doesn't host, but allows bundling
 * arbitrary files as pack "overrides". A pack author drops such jars in {@code mods/Retromod/}
 * (an accepted override directory) and this locator hands them to the loader at discovery time,
 * following the <a href="https://github.com/Sinytra/Connector">Sinytra Connector</a> model. (#78)
 *
 * <p>Registered via {@code META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator}.
 * FML's early-service discovery walks {@code mods/}, finds the declaring jar, and runs
 * {@link #findCandidates} during mod discovery before the module layer is built, so the jars we add
 * load like any other mod. The top-level {@code ModsFolderLocator} scans {@code mods/} non-recursively
 * and never sees the {@code Retromod/} subfolder, so this locator is what loads it.
 *
 * <p>NeoForge only, across loader 4.x (MC 1.21.x) through 11.x (MC 26.x). The SPI spans that range, but
 * {@code ILaunchContext.gameDirectory()} only exists on loader 11.x, so the game directory is resolved
 * version-safely ({@link #resolveGameDir}). Jars placed here should already be loader-correct (AOT-transformed
 * via {@code retromod batch}, or a native NeoForge mod like Retromod); this locator only discovers, never transforms.
 *
 * <p>Self-contained (JDK + SLF4J + the SPI) so the same class can ship in a minimal CurseForge
 * "Retromod Loader" stub jar without the transform engine.
 */
public final class RetromodModLocator implements IModFileCandidateLocator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Locator");

    /** Subfolder of {@code mods/} this locator scans. */
    public static final String SUBFOLDER = "Retromod";

    /**
     * Optional override: an absolute path to scan instead of {@code <gameDir>/mods/Retromod}.
     * Mainly for testing / unusual layouts.
     */
    public static final String OVERRIDE_PROPERTY = "retromod.modfolder";

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        final Path folder;
        try {
            folder = resolveModFolder(resolveGameDir(context));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not find the Retromod mods folder, so no transformed mods "
                    + "will be added", e);
            return;
        }

        // A read-only game directory should not stop the rest of mod discovery.
        if (!Files.isDirectory(folder)) {
            try {
                Files.createDirectories(folder);
                LOGGER.info("Created {}. Put transformed mod jars in this folder.", folder);
            } catch (IOException e) {
                LOGGER.debug("Could not create the transformed mod folder {}: {}",
                        folder, e.toString());
            }
            return;
        }

        List<Path> jars = listJars(folder);
        if (jars.isEmpty()) {
            LOGGER.debug("No transformed mod jars found in {}", folder);
            return;
        }

        int added = 0;
        for (Path jar : jars) {
            try {
                pipeline.addPath(jar, ModFileDiscoveryAttributes.DEFAULT,
                        IncompatibleFileReporting.WARN_ON_KNOWN_INCOMPATIBILITY);
                added++;
            } catch (RuntimeException e) {
                LOGGER.warn("Could not add {} to mod discovery: {}",
                        jar.getFileName(), e.toString());
            }
        }
        LOGGER.info("Added {} transformed mod {} from {} to mod discovery",
                added, added == 1 ? "jar" : "jars", folder);
    }

    @Override
    public String toString() {
        return "retromod folder locator (" + SUBFOLDER + ")";
    }

    // Package-private helpers keep these paths testable without a running game.

    /**
     * Folder to scan: the {@code retromod.modfolder} system property (absolute path) if set,
     * otherwise {@code <gameDir>/mods/Retromod}. {@code gameDirectory} may be null only when the
     * override is set; otherwise a null here throws and the caller logs and skips.
     */
    static Path resolveModFolder(Path gameDirectory) {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        return gameDirectory.resolve("mods").resolve(SUBFOLDER);
    }

    /**
     * Resolve the game directory across NeoForge loader versions. {@code ILaunchContext.gameDirectory()}
     * only exists on loader 11.x (MC 26.x); a direct call on loader 4.x (MC 1.21.x) throws
     * {@code NoSuchMethodError} during discovery and crashes the server before any mod loads. So we
     * reflect it off the {@code ILaunchContext} interface (not the impl, which may be a lambda/hidden
     * class) and fall back to {@code FMLPaths.GAMEDIR}, present on every loader version. Returns null
     * if neither resolves, leaving the caller to skip discovery instead of crashing.
     */
    private static Path resolveGameDir(ILaunchContext context) {
        try {
            Object dir = ILaunchContext.class.getMethod("gameDirectory").invoke(context);
            if (dir instanceof Path p) return p;
        } catch (ReflectiveOperationException | RuntimeException absentOnLoader4x) {
            // Older loader versions expose the game directory through FMLPaths instead.
        }
        try {
            Class<?> fmlPaths = Class.forName("net.neoforged.fml.loading.FMLPaths");
            Object gamedir = fmlPaths.getField("GAMEDIR").get(null);
            Object dir = fmlPaths.getMethod("get").invoke(gamedir);
            if (dir instanceof Path p) return p;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not find the NeoForge game directory: {}", e.toString());
        }
        return null;
    }

    /**
     * List regular {@code *.jar} files directly in {@code folder} (non-recursive),
     * sorted for deterministic load order. Never throws; returns empty on error.
     */
    static List<Path> listJars(Path folder) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> entries = Files.list(folder)) {
            entries.filter(RetromodModLocator::isJar)
                   .sorted()
                   .forEach(out::add);
        } catch (IOException e) {
            LOGGER.warn("Could not list transformed mods in {}: {}", folder, e.toString());
        }
        return out;
    }

    private static boolean isJar(Path p) {
        return Files.isRegularFile(p)
                && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }
}
