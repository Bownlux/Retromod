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

    /** Shaped like Forge 26.2's {@code PotionBrewing.Builder}, which adds through {@code add} rather than {@code addRecipe}. */
    public static final class ForgeBuilder {
        public final List<Object> added = new ArrayList<>();

        public ForgeBuilder add(Object recipe) {
            added.add(recipe);
            return this;
        }
    }

    /** Shaped like Forge 26.2's {@code BrewingRecipeRegisterEvent}, which takes the recipes itself. */
    public static final class ForgeEvent {
        public final ForgeBuilder builder = new ForgeBuilder();

        public ForgeBuilder getBuilder() {
            return builder;
        }

        public void addRecipe(Object recipe) {
            builder.add(recipe);
        }

        public void addRecipe(Object input, Object ingredient, Object output) {
            builder.add(List.of("brewed", input, ingredient, output));
        }
    }

    @Test
    @DisplayName("Forge 26.2's event takes the recipes in the order the mod added them, both shapes")
    void recipesReplayIntoForgesEvent() {
        // No loader is on the test classpath, so each add keeps the recipe and reports the missing event.
        assertThrows(IllegalStateException.class, () -> LegacyBrewingRecipes.addRecipe("first"));
        assertThrows(IllegalStateException.class, () -> LegacyBrewingRecipes.addRecipe("input", "ingredient", "output"));

        ForgeEvent event = new ForgeEvent();
        LegacyBrewingRecipes.replay(event);
        List<Object> added = event.builder.added;
        assertEquals(List.of("first", List.of("brewed", "input", "ingredient", "output")),
                added.subList(added.size() - 2, added.size()));
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
