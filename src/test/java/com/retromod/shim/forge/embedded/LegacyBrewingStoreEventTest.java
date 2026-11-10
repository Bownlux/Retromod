/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The old brewing event is posted on every server start, so its recipes are written once, not once per start. */
class LegacyBrewingStoreEventTest {

    @Test
    @DisplayName("a second post replaces the first post's recipes and keeps the registry's")
    void eventRecipesDoNotPileUpAcrossPosts() {
        Object registry = new Object();
        Object firstPost = new Object();
        Object secondPost = new Object();
        LegacyBrewingStore.addRecipe(registry);

        LegacyBrewingStore.beginEvent();
        LegacyBrewingStore.addEventRecipe(firstPost);
        LegacyBrewingStore.beginEvent();
        LegacyBrewingStore.addEventRecipe(secondPost);

        List<Object> recorded = LegacyBrewingStore.recorded();
        assertTrue(recorded.contains(registry), "a registry add is made once and kept");
        assertFalse(recorded.contains(firstPost), "a client's second world must not write the first world's recipes again");
        assertEquals(1, recorded.stream().filter(recipe -> recipe == secondPost).count());
    }
}
