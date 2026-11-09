/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.oldingredient;

import com.retromod.shim.forge.ingredienthost.Ingredient;

/** An old ingredient that extends Ingredient itself and names its serializer, as Farmer's Delight's does. */
public class DirectIngredient extends Ingredient {
    public OldIngredientSerializer<? extends Ingredient> getSerializer() {
        return ItemIngredient.FIRST;
    }
}
