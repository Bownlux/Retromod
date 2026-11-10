/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.oldingredient;

import com.google.gson.JsonElement;
import com.retromod.shim.forge.ingredienthost.Ingredient;

import java.util.stream.Stream;

/** Forge 1.20.1's AbstractIngredient, as an old mod compiled against it. */
public abstract class OldAbstractIngredient extends Ingredient {
    protected OldAbstractIngredient() {}

    protected OldAbstractIngredient(Stream<? extends OldValue> values) {}

    public abstract boolean isSimple();

    public abstract OldIngredientSerializer<? extends Ingredient> getSerializer();

    public abstract JsonElement toJson();
}
