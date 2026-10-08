/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Replaying recorded Forge brewing recipes into a NeoForge-shaped brewing builder. */
class LegacyBrewingRecipesReplayTest {

    /** Shaped like {@code PotionBrewing.Builder}'s two NeoForge recipe methods. */
    public static final class Builder {
        public final List<Object> added = new ArrayList<>();

        public void addRecipe(Object recipe) {
            added.add(recipe);
        }

        public void addRecipe(Object input, Object ingredient, Object output) {
            added.add(List.of(input, ingredient, output));
        }
    }

    /** Shaped like {@code RegisterBrewingRecipesEvent}. */
    public static final class Event {
        public final Builder builder = new Builder();

        public Builder getBuilder() {
            return builder;
        }
    }

    @Test
    @DisplayName("Every rebuild receives the recipes in the order the mod added them")
    void recipesReplayIntoEachBuilder() {
        int before = LegacyBrewingRecipes.pendingCount();
        // Without NeoForge on the classpath the listener cannot attach, and the failure must say so.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> LegacyBrewingRecipes.addRecipe("recipe"));
        assertTrue(failure.getMessage().contains("NeoForge"), failure.getMessage());
        assertEquals(before + 1, LegacyBrewingRecipes.pendingCount(), "the recipe is kept even so");

        Event server = new Event();
        LegacyBrewingRecipes.replay(server);
        Event client = new Event();
        LegacyBrewingRecipes.replay(client);
        assertEquals("recipe", server.builder.added.get(server.builder.added.size() - 1));
        assertEquals(server.builder.added, client.builder.added, "NeoForge rebuilds brewing per side and server start");
    }
}
