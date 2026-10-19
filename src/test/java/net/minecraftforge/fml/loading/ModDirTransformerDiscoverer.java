/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package net.minecraftforge.fml.loading;

import cpw.mods.modlauncher.api.NamedPath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Test stand-in with the two members the bootstrap relies on, as javap shows them in fmlloader
 * 1.20.1-47.4.26 and 26.3-66.0.12: a private static list of found service jars and the public
 * view the mods folder scan reads. Written for this test, not copied from Forge.
 */
public final class ModDirTransformerDiscoverer {

    private static final List<NamedPath> found = new ArrayList<>();

    private ModDirTransformerDiscoverer() {}

    public static List<Path> allExcluded() {
        return found.stream().map(named -> named.paths()[0]).toList();
    }

    /** Test helper: forget everything between cases. */
    public static void reset() {
        found.clear();
    }
}
