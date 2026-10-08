/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Forge's static {@code BrewingRecipeRegistry.addRecipe}, replayed through NeoForge's
 * {@code RegisterBrewingRecipesEvent}.
 *
 * <p>Forge kept one global brewing registry that a mod filled during setup. NeoForge rebuilds the
 * registry for each server and client from that event, so a recipe added once has to be handed to
 * every rebuild. Recipes are kept in call order and the listener is added on the first call. The
 * calls are reflective because Retromod is not compiled against NeoForge.
 */
public final class LegacyBrewingRecipes {

    private static final String NEOFORGE = "net.neoforged.neoforge.common.NeoForge";
    private static final String EVENT_BUS = "net.neoforged.bus.api.IEventBus";
    private static final String EVENT = "net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent";

    private static final List<Object[]> RECIPES = new ArrayList<>();
    private static boolean listening;

    private LegacyBrewingRecipes() {}

    /** {@code addRecipe(IBrewingRecipe)}. Forge returned false only for a rejected recipe. */
    public static boolean addRecipe(Object recipe) {
        remember(new Object[] {recipe});
        return true;
    }

    /** {@code addRecipe(Ingredient input, Ingredient ingredient, ItemStack output)}. */
    public static boolean addRecipe(Object input, Object ingredient, Object output) {
        remember(new Object[] {input, ingredient, output});
        return true;
    }

    private static synchronized void remember(Object[] recipe) {
        RECIPES.add(recipe);
        if (listening) return;
        ClassLoader loader = LegacyBrewingRecipes.class.getClassLoader();
        try {
            Object bus = Class.forName(NEOFORGE, false, loader).getField("EVENT_BUS").get(null);
            Class<?> event = Class.forName(EVENT, false, loader);
            Consumer<Object> replay = LegacyBrewingRecipes::replay;
            Class.forName(EVENT_BUS, false, loader).getMethod("addListener", Class.class, Consumer.class)
                    .invoke(bus, event, replay);
            listening = true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot register Forge brewing recipes with NeoForge", e);
        }
    }

    static void replay(Object event) {
        List<Object[]> recipes;
        synchronized (LegacyBrewingRecipes.class) {
            recipes = new ArrayList<>(RECIPES);
        }
        try {
            Object builder = event.getClass().getMethod("getBuilder").invoke(event);
            for (Object[] recipe : recipes) {
                addRecipeMethod(builder.getClass(), recipe.length).invoke(builder, recipe);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot add Forge brewing recipes to NeoForge's brewing builder", e);
        }
    }

    private static Method addRecipeMethod(Class<?> builder, int parameters) throws NoSuchMethodException {
        for (Method method : builder.getMethods()) {
            if (method.getName().equals("addRecipe") && method.getParameterCount() == parameters) return method;
        }
        throw new NoSuchMethodException(builder.getName() + ".addRecipe with " + parameters + " parameters");
    }

    /** Recipes added so far, for tests. */
    static synchronized int pendingCount() {
        return RECIPES.size();
    }
}
