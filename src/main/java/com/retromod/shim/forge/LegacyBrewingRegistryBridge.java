/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyBrewingRecipes;

import java.util.function.Predicate;

/**
 * Bridges Forge's static brewing registry onto NeoForge, which builds brewing recipes per server
 * from {@code RegisterBrewingRecipesEvent}.
 *
 * <p>The recipe classes keep their methods and move by name through {@link ForgeNeoForgeApiBridge}.
 * The two static {@code addRecipe} calls become calls to {@link LegacyBrewingRecipes}, which keeps
 * each recipe and adds it to every brewing rebuild. The registry's static lookups, such as
 * {@code getOutput} and {@code isValidInput}, are not bridged, because NeoForge only answers them
 * from a level's own registry.
 */
public final class LegacyBrewingRegistryBridge {

    static final String FORGE_REGISTRY = "net/minecraftforge/common/brewing/BrewingRecipeRegistry";
    static final String FORGE_RECIPE = "net/minecraftforge/common/brewing/IBrewingRecipe";
    static final String EVENT = "net/neoforged/neoforge/event/brewing/RegisterBrewingRecipesEvent";
    static final String HELPER = "com/retromod/shim/forge/embedded/LegacyBrewingRecipes";

    private static final String INGREDIENT = "Lnet/minecraft/world/item/crafting/Ingredient;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";

    private LegacyBrewingRegistryBridge() {}

    public static void register(RetromodTransformer transformer) {
        registerIfHostHasEvent(transformer, ClassResourceInspector::exists);
    }

    static boolean registerIfHostHasEvent(RetromodTransformer transformer, Predicate<String> hostHasClass) {
        if (hostHasClass.test(FORGE_REGISTRY) || !hostHasClass.test(EVENT)) return false;
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyBrewingRecipes.class);
        transformer.registerMethodRedirect(FORGE_REGISTRY, "addRecipe", "(L" + FORGE_RECIPE + ";)Z",
                HELPER, "addRecipe", "(Ljava/lang/Object;)Z");
        transformer.registerMethodRedirect(FORGE_REGISTRY, "addRecipe", "(" + INGREDIENT + INGREDIENT + STACK + ")Z",
                HELPER, "addRecipe", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z");
        return true;
    }
}
