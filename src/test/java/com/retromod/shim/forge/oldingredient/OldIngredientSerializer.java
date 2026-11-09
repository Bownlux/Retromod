/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.oldingredient;

import com.google.gson.JsonObject;
import com.retromod.shim.forge.ingredienthost.FriendlyByteBuf;
import com.retromod.shim.forge.ingredienthost.Ingredient;

/** Forge 1.20.1's IIngredientSerializer, as an old mod compiled against it. */
public interface OldIngredientSerializer<T extends Ingredient> {
    T parse(FriendlyByteBuf buffer);

    T parse(JsonObject json);

    void write(FriendlyByteBuf buffer, T ingredient);
}
