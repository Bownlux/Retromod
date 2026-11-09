/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyBrewingRecipes;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** Sends Forge's removed static brewing registry to {@link LegacyBrewingRecipes} on NeoForge and on Forge 26.2. */
public final class LegacyBrewingRegistryBridge {

    static final String FORGE_REGISTRY = "net/minecraftforge/common/brewing/BrewingRecipeRegistry";
    static final String FORGE_RECIPE = "net/minecraftforge/common/brewing/IBrewingRecipe";
    static final String FORGE_EVENT = "net/minecraftforge/event/brewing/BrewingRecipeRegisterEvent";
    static final String EVENT = "net/neoforged/neoforge/event/brewing/RegisterBrewingRecipesEvent";
    static final String HELPER = "com/retromod/shim/forge/embedded/LegacyBrewingRecipes";
    // Forge 66 removed the recipe interface with 26.3's brewing recipe files; LegacyBrewingBridge owns that host.
    static final String FIRST_WITHOUT_RECIPE = "26.3";

    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String INGREDIENT = "Lnet/minecraft/world/item/crafting/Ingredient;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";

    private LegacyBrewingRegistryBridge() {}

    public static void register(RetromodTransformer transformer) {
        registerIfHostHasEvent(transformer, ClassResourceInspector::exists);
    }

    static boolean registerIfHostHasEvent(RetromodTransformer transformer, Predicate<String> hostHasClass) {
        if (hostHasClass.test(FORGE_REGISTRY) || !hostHasClass.test(EVENT)) return false;
        // NeoForge answers lookups only from a level's own registry, so only the two adds are bridged there.
        registerAdds(transformer);
        return true;
    }

    /** Forge 26.2 builds brewing from an event and keeps the recipe classes, so the whole registry comes back. */
    public static boolean registerForge(RetromodTransformer transformer) {
        return registerForge(transformer, ClassResourceInspector::read);
    }

    static boolean registerForge(RetromodTransformer transformer, Function<String, ClassNode> host) {
        if (!ForgeHostShape.matches(List.of(FORGE_EVENT, FORGE_RECIPE), List.of(FORGE_REGISTRY),
                ForgeHostShape.FIRST_CHECKED_VERSION, host)
                || ForgeHostShape.matches(List.of(), List.of(FORGE_RECIPE), FIRST_WITHOUT_RECIPE, host)) {
            return false;
        }
        registerAdds(transformer);
        // The lookups read the running game's brewing, which holds Minecraft's recipe and every mod's.
        String oneStack = "(" + STACK + ")Z";
        transformer.registerMethodRedirect(FORGE_REGISTRY, "getRecipes", "()Ljava/util/List;",
                HELPER, "getRecipes", "()Ljava/util/List;");
        transformer.registerMethodRedirect(FORGE_REGISTRY, "isValidInput", oneStack,
                HELPER, "isValidInput", "(" + OBJECT + ")Z");
        transformer.registerMethodRedirect(FORGE_REGISTRY, "isValidIngredient", oneStack,
                HELPER, "isValidIngredient", "(" + OBJECT + ")Z");
        transformer.registerMethodRedirect(FORGE_REGISTRY, "getOutput", "(" + STACK + STACK + ")" + STACK,
                HELPER, "getOutput", "(" + OBJECT + OBJECT + ")" + OBJECT);
        transformer.registerMethodRedirect(FORGE_REGISTRY, "hasOutput", "(" + STACK + STACK + ")Z",
                HELPER, "hasOutput", "(" + OBJECT + OBJECT + ")Z");
        return true;
    }

    private static void registerAdds(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyBrewingRecipes.class);
        transformer.registerMethodRedirect(FORGE_REGISTRY, "addRecipe", "(L" + FORGE_RECIPE + ";)Z",
                HELPER, "addRecipe", "(Ljava/lang/Object;)Z");
        transformer.registerMethodRedirect(FORGE_REGISTRY, "addRecipe", "(" + INGREDIENT + INGREDIENT + STACK + ")Z",
                HELPER, "addRecipe", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z");
    }
}
