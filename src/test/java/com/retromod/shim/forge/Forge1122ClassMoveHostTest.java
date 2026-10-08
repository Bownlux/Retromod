/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #299 and #304: a 1.12.2 Forge mod on a NeoForge 1.21.1 host kept the 1.13 name
 * {@code net/minecraft/item/ItemGroup}, because the class-move table that finishes the chain was
 * loaded only on 1.20.x and 26.x hosts. Every target in the table also exists on 1.21.1.
 */
class Forge1122ClassMoveHostTest {

    private static final String TABS = "net/minecraft/creativetab/CreativeTabs";
    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "host {0} loads a table: {1}")
    @CsvSource({"1.20.1, true", "1.21, true", "1.21.1, true", "1.21.4, false"})
    @DisplayName("the 1.20.x table of 1.12.2 class moves also loads on 1.21 and 1.21.1")
    void tableLoadsOnCheckedHosts(String host, boolean expected) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();

        new Forge_1_12_2_to_1_13_2().loadDirectClassMoves(transformer);

        assertEquals(expected, transformer.getClassRedirects().containsKey(TABS),
                "CreativeTabs redirect on " + host);
    }
}
