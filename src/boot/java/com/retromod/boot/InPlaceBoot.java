/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.boot;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** Runs the in-place discovery pass every loader shares and passes its decision to Retromod's entry point. */
public final class InPlaceBoot {

    static final String MODE_PROPERTY = "retromod.inplace.mode";
    static final String STATUS_PROPERTY = "retromod.inplace.status";
    static final String DISCOVERY_PROPERTY = "retromod.inplace.discovery";
    static final String DISCOVERY_NONE = "none";
    /** Must match {@code InPlaceStatus.DIST_PROPERTY}. */
    static final String DIST_PROPERTY = "retromod.inplace.dist";
    private static final Set<String> DISTS = Set.of("CLIENT", "DEDICATED_SERVER");
    private static final Set<String> MODES = Set.of("in-place", "rewrite", "unavailable");

    /** The toml FML 47 (Forge, NeoForge 1.20.1) reads. */
    public static final String FORGE_TOML = "META-INF/mods.toml";

    /** The toml NeoForge 20.5 and newer read. */
    public static final String NEOFORGE_TOML = "META-INF/neoforge.mods.toml";

    /** Originals to hide from the mods scan, and translated copies to serve in a folder or singly. */
    public record Decision(String mode, List<Path> hide, Path serveFolder, List<Path> serveFiles) {}

    private InPlaceBoot() {}

    /** Tells the entry point that this loader runs no discovery pass, so mods/ stays unchanged. */
    public static void markNoDiscoveryPass(Properties properties) {
        properties.setProperty(DISCOVERY_PROPERTY, DISCOVERY_NONE);
    }

    /** Runs the pass for the runtime the bootstrap prepared, on a loader that does not report its side. */
    public static Optional<Decision> run(BootCore.Prepared prepared, Path gameDir, Path modsDir,
            String hostMinecraft, String loaderBuild, String hostToml, String serveStyle,
            BootLog log) {
        return run(prepared, gameDir, modsDir, hostMinecraft, loaderBuild, hostToml, serveStyle, null, log);
    }

    /** Runs the pass for the runtime the bootstrap prepared; {@code dist} is the side the loader reports, or null. */
    public static Optional<Decision> run(BootCore.Prepared prepared, Path gameDir, Path modsDir,
            String hostMinecraft, String loaderBuild, String hostToml, String serveStyle,
            String dist, BootLog log) {
        // The side goes into the pass's copy of the properties and on to the entry point, so the
        // cache keeps a dedicated server's copies apart from a client's.
        if (dist != null && DISTS.contains(dist)) {
            System.setProperty(DIST_PROPERTY, dist);
        } else {
            System.clearProperty(DIST_PROPERTY);
        }
        // Without a version from the loader, use the one this Retromod file was built for.
        String minecraft = hostMinecraft != null && !hostMinecraft.isBlank()
                ? hostMinecraft : prepared.source().targetMinecraft();
        Map<String, Object> request = BootEngine.request(gameDir, modsDir,
                prepared.source().loader(), minecraft, loaderBuild, hostToml, serveStyle);
        Optional<Map<String, Object>> result = BootEngine.run(prepared.runtimeJar(), request, log);
        if (result.isEmpty()) return Optional.empty();
        // The entry point runs later, in the game class loader, so it learns the outcome from two
        // system properties: the mode and the status file path. Neither is set when the pass could
        // not run, and the entry point then decides from the config file alone.
        Object mode = result.get().get("mode");
        if (!(mode instanceof String text) || !MODES.contains(text)) return Optional.empty();
        System.setProperty(MODE_PROPERTY, text);
        if (result.get().get("statusFile") instanceof Path status) {
            System.setProperty(STATUS_PROPERTY, status.toString());
        } else {
            System.clearProperty(STATUS_PROPERTY);
        }
        Path serveFolder = result.get().get("serveFolder") instanceof Path folder ? folder : null;
        return Optional.of(new Decision(text, BootEngine.paths(result.get(), "hide"), serveFolder,
                BootEngine.paths(result.get(), "serveFiles")));
    }
}
