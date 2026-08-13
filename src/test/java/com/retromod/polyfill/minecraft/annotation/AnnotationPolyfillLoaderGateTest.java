/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft.annotation;

import com.retromod.core.RetromodTransformer;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AnnotationPolyfillLoaderGateTest {

    private static final String FORGE_DIST = "net/minecraftforge/api/distmarker/Dist";
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void setUp() {
        McReflect.setForceNeoForge(false);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        McReflect.setForceNeoForge(false);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("#204: Forge target keeps Forge dist markers")
    void forgeTargetKeepsForgeDistMarkers() {
        new AnnotationPolyfill().registerPolyfills(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(FORGE_DIST));
    }

    @Test
    @DisplayName("NeoForge target relocates Forge dist markers")
    void neoForgeTargetRelocatesForgeDistMarkers() {
        McReflect.setForceNeoForge(true);
        new AnnotationPolyfill().registerPolyfills(transformer);
        assertEquals("net/neoforged/api/distmarker/Dist",
                transformer.getClassRedirects().get(FORGE_DIST));
    }
}
