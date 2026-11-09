/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricModTransformerNativeVersionTest {

    private final FabricModTransformer transformer = new FabricModTransformer("26.2");

    @Test
    void acceptsOnlyPredicatesThatIdentifyTheTarget() {
        assertTrue(transformer.isNativeVersionMod("26.2"));
        assertTrue(transformer.isNativeVersionMod("=26.2"));
        assertTrue(transformer.isNativeVersionMod(">=26.2"));
        assertTrue(transformer.isNativeVersionMod("~26.2"));
        assertTrue(transformer.isNativeVersionMod("^26.2"));
        assertTrue(transformer.isNativeVersionMod("26.*"));

        assertFalse(transformer.isNativeVersionMod("<26.2"));
        assertFalse(transformer.isNativeVersionMod("<=26.2"));
        assertFalse(transformer.isNativeVersionMod(">26.2"));
        assertFalse(transformer.isNativeVersionMod(">=1.21.11"));
        assertFalse(transformer.isNativeVersionMod(null));
    }

    @Test
    void prereleaseMarkerDoesNotHideTheTarget() {
        // Fabric's trailing '-' admits prereleases; ">=26.2-" still starts at 26.2.
        assertTrue(transformer.isNativeVersionMod(">=26.2-"));
        assertTrue(transformer.isNativeVersionMod("~26.2-"));
        assertTrue(new FabricModTransformer("26.3").isNativeVersionMod(">=26.3-"),
                "the newest host must pass its own prerelease-marked range through");

        assertFalse(transformer.isNativeVersionMod(">=26.1-"),
                "a lower bound below the host still does not prove the mod was built for it");
    }

    @Test
    void anyListedRangeThatStartsAtTheTargetIsNative() {
        assertTrue(transformer.anyRangeIsNative(List.of("1.21.11", "26.2")),
                "Fabric accepts a mod when any element of its minecraft array matches");

        assertFalse(transformer.anyRangeIsNative(List.of("1.21", "1.21.1")));
        assertFalse(transformer.anyRangeIsNative(List.of()),
                "a mod with no minecraft dependency is unknown, so it is translated");
    }
}
