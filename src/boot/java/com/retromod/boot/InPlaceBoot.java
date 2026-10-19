/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.boot;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Runs the in-place discovery pass every loader shares and passes its decision to Retromod's entry point. */
public final class InPlaceBoot {

    static final String MODE_PROPERTY = "retromod.inplace.mode";
    static final String STATUS_PROPERTY = "retromod.inplace.status";
    private static final Set<String> MODES = Set.of("in-place", "rewrite", "unavailable");

    /** The toml FML 47 (Forge, NeoForge 1.20.1) reads. */
    public static final String FORGE_TOML = "META-INF/mods.toml";

    /** The toml NeoForge 20.5 and newer read. */
    public static final String NEOFORGE_TOML = "META-INF/neoforge.mods.toml";

    /** Originals to hide from the mods scan, and translated copies to serve in a folder or singly. */
    public record Decision(String mode, List<Path> hide, Path serveFolder, List<Path> serveFiles) {}

    private InPlaceBoot() {}

    /** Runs the pass for the runtime the bootstrap prepared. */
    public static Optional<Decision> run(BootCore.Prepared prepared, Path gameDir, Path modsDir,
            String hostMinecraft, String loaderBuild, String hostToml, String serveStyle,
            BootLog log) {
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
