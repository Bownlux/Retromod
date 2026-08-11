/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudRenderCallbackBridgeTest {

    @Test
    @DisplayName("relocated callback types receive distinct valid HUD element ids")
    void relocatedCallbacksUseDistinctElementIds() {
        String first = HudRenderCallbackBridge.elementPath(RelocatedCallbackOne.class);
        String second = HudRenderCallbackBridge.elementPath(RelocatedCallbackTwo.class);

        assertNotEquals(first, second,
                "two transformed mods must not compete for one HudElementRegistry id");
        assertEquals(first, HudRenderCallbackBridge.elementPath(RelocatedCallbackOne.class),
                "the id must remain stable for the same relocated callback type");
        assertTrue(first.matches("[a-z0-9/._-]+"),
                "the first id must satisfy Minecraft resource path rules: " + first);
        assertTrue(second.matches("[a-z0-9/._-]+"),
                "the second id must satisfy Minecraft resource path rules: " + second);
    }

    @Test
    @DisplayName("missing callback identity retains the legacy fallback HUD element id")
    void missingCallbackUsesStableFallback() {
        assertEquals("legacy_hud_render", HudRenderCallbackBridge.elementPath(null));
    }

    private interface RelocatedCallbackOne {}
    private interface RelocatedCallbackTwo {}
}
