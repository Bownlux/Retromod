/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** NeoForge 1.21.1 mods name classes that later NeoForge releases moved, such as ModelData. */
class NeoForgeMovedClassesTest {

    private static final String OLD = "net/neoforged/neoforge/client/model/data/ModelData";
    private static final String NEW = "net/neoforged/neoforge/model/data/ModelData";
    private final String target = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = target;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Offline, a 26.3 target follows the move")
    void newTargetFollowsTheMove() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();

        NeoForgeMovedClasses.register(t);

        assertEquals(NEW, t.getClassRedirects().get(OLD));
        assertEquals("net/neoforged/neoforge/model/data/ModelData$Builder",
                t.getClassRedirects().get(OLD + "$Builder"), "the builder moves with its outer class");
    }

    @Test
    @DisplayName("Offline, a 1.21.1 target keeps the old names")
    void oldTargetKeepsTheOldNames() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();

        NeoForgeMovedClasses.register(t);

        assertNull(t.getClassRedirects().get(OLD));
    }
}
