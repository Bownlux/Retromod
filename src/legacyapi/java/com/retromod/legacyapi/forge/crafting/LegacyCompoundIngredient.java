/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.crafting;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.ingredients.AbstractIngredient;
import net.minecraftforge.common.crafting.ingredients.CompoundIngredient;
import net.minecraftforge.common.crafting.ingredients.IIngredientSerializer;

/** Forge 1.20.1's {@code CompoundIngredient}, which a mod could extend, on Forge 26.2, whose own compound it cannot. */
public class LegacyCompoundIngredient extends AbstractIngredient {

    private final List<Ingredient> children;

    /** The old protected constructor, which a mod's subclass such as {@code new CompoundIngredient(list) {}} calls. */
    protected LegacyCompoundIngredient(List<Ingredient> children) {
        this.children = Collections.unmodifiableList(children);
    }

    /** {@code CompoundIngredient.of}: Forge's own compound, which also reaches JSON and the network. */
    public static Ingredient of(Ingredient... children) {
        return CompoundIngredient.of(children);
    }

    public Collection<Ingredient> getChildren() {
        return children;
    }

    /** Matches what any child matches, as the old compound did. */
    @Override
    public boolean test(ItemStack stack) {
        for (Ingredient child : children) {
            if (child.test(stack)) return true;
        }
        return false;
    }

    @Override
    public Stream<Holder<Item>> items() {
        return children.stream().flatMap(Ingredient::items).distinct();
    }

    /** Simple while every child is; a vanilla child always is, a Forge one answers for itself. */
    @Override
    public boolean isSimple() {
        for (Ingredient child : children) {
            if (child instanceof AbstractIngredient custom && !custom.isSimple()) return false;
        }
        return true;
    }

    // Forge registers no serializer for a mod's own subclass. Its compound serializer is the old type's
    // successor, but it writes only Forge's own compound, so a subclass built in code is never written.
    @Override
    public IIngredientSerializer<? extends Ingredient> serializer() {
        return CompoundIngredient.SERIALIZER;
    }
}
