/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.compoundhost;

/** Stands in for Forge 26.2's NBTIngredient; only its serializer is read. */
public class NBTIngredient extends AbstractIngredient {
    public static final IIngredientSerializer<NBTIngredient> SERIALIZER = new IIngredientSerializer<>() {};

    private NBTIngredient() {}

    @Override
    public boolean isSimple() {
        return false;
    }

    @Override
    public IIngredientSerializer<? extends Ingredient> serializer() {
        return SERIALIZER;
    }
}
