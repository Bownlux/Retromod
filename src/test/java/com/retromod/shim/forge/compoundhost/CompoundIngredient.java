/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.compoundhost;

import java.util.List;
import java.util.stream.Stream;

/** Stands in for Forge 26.2's CompoundIngredient, whose constructor is private. */
public class CompoundIngredient extends AbstractIngredient {
    public static final IIngredientSerializer<CompoundIngredient> SERIALIZER = new IIngredientSerializer<>() {};
    private final List<Ingredient> children;

    private CompoundIngredient(List<Ingredient> children) {
        this.children = children;
    }

    public static Ingredient of(Ingredient... children) {
        return new CompoundIngredient(List.of(children));
    }

    @Override
    public Stream<Holder<Item>> items() {
        return children.stream().flatMap(Ingredient::items).distinct();
    }

    @Override
    public boolean test(ItemStack stack) {
        return children.stream().anyMatch(child -> child.test(stack));
    }

    @Override
    public boolean isSimple() {
        return true;
    }

    @Override
    public IIngredientSerializer<? extends Ingredient> serializer() {
        return SERIALIZER;
    }
}
