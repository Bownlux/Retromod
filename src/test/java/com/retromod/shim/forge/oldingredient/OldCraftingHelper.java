/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.oldingredient;

import com.retromod.shim.forge.ingredienthost.Identifier;
import com.retromod.shim.forge.ingredienthost.Ingredient;

/** Forge 1.20.1's CraftingHelper ingredient methods, with the id type a 26.x host names. */
public final class OldCraftingHelper {
    private OldCraftingHelper() {}

    public static <T extends Ingredient> OldIngredientSerializer<T> register(Identifier id,
            OldIngredientSerializer<T> serializer) {
        throw new UnsupportedOperationException("compile-time shape only");
    }

    public static Identifier getID(OldIngredientSerializer<?> serializer) {
        throw new UnsupportedOperationException("compile-time shape only");
    }
}
