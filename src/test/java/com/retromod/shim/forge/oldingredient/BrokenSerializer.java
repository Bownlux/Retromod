/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.oldingredient;

import com.google.gson.JsonObject;
import com.retromod.shim.forge.ingredienthost.FriendlyByteBuf;

/** An old serializer whose read reaches a removed API, as Farmer's Delight's does through Ingredient.ItemValue. */
public final class BrokenSerializer implements OldIngredientSerializer<ItemIngredient> {
    @Override
    public ItemIngredient parse(FriendlyByteBuf buffer) {
        throw new UnsupportedOperationException("not read from the network here");
    }

    @Override
    public ItemIngredient parse(JsonObject json) {
        throw new NoClassDefFoundError("Ingredient.ItemValue");
    }

    @Override
    public void write(FriendlyByteBuf buffer, ItemIngredient ingredient) {
    }
}
