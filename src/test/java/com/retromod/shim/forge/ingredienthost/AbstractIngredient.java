/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.ingredienthost;

/** Stands in for Forge 26.2's AbstractIngredient: the serializer comes from {@code serializer()}. */
public abstract class AbstractIngredient extends Ingredient {
    protected AbstractIngredient() {}

    public abstract boolean isSimple();

    public abstract IIngredientSerializer serializer();
}
