/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.Test;

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
}
