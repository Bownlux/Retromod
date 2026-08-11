/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft.mixin;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Keeps the removed options-screen placeholder away from hosts where the real class exists. */
class MixinTargetPolyfillGateTest {

    private static final String OLD_SCREEN = "net/minecraft/class_5500";
    private static final String PLACEHOLDER =
            "com/retromod/polyfill/minecraft/mixin/embedded/ChatOptionsScreenStub";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedVersion;

    @BeforeEach
    void setUp() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedVersion;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("SimpleOptionsSubScreen remains live on intermediary-named hosts")
    void doesNotReplaceLivePre26Class() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";

        MixinTargetPolyfill provider = new MixinTargetPolyfill();
        provider.registerPolyfills(transformer);

        assertFalse(transformer.getClassRedirects().containsKey(OLD_SCREEN));
        assertEquals(0, provider.getRemovedClasses().length);
    }

    @Test
    @DisplayName("SimpleOptionsSubScreen placeholder is active on unobfuscated hosts")
    void replacesRemovedClassOn26x() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";

        MixinTargetPolyfill provider = new MixinTargetPolyfill();
        provider.registerPolyfills(transformer);

        assertEquals(PLACEHOLDER, transformer.getClassRedirects().get(OLD_SCREEN));
        assertEquals(1, provider.getRemovedClasses().length);
    }
}
