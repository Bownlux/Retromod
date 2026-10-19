/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.boot;

import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * On Forge and NeoForge 1.20.1 the only way to keep an original out of the mods folder scan is
 * the list behind {@code ModDirTransformerDiscoverer.allExcluded()}. The scan compares raw
 * paths, so the bootstrap must add exactly the paths it was given.
 */
class LoaderHooksTest {

    @AfterEach
    void reset() {
        ModDirTransformerDiscoverer.reset();
    }

    @Test
    @DisplayName("hidden originals appear in allExcluded() exactly as listed")
    void hiddenPathsReachAllExcluded() throws Exception {
        Path first = Path.of("mods", "Old Mod 1.0.jar");
        Path second = Path.of("/game/mods/other.jar");

        LoaderHooks.hideFromModsFolder(LoaderHooks.FORGE_FML_LOADING, List.of(first, second));

        assertEquals(List.of(first, second), ModDirTransformerDiscoverer.allExcluded());
    }

    @Test
    @DisplayName("nothing to hide touches nothing")
    void emptyListIsANoOp() throws Exception {
        LoaderHooks.hideFromModsFolder("no.such.package", List.of());
        assertEquals(List.of(), ModDirTransformerDiscoverer.allExcluded());
    }
}
