/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.crafting;

import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.ingredients.AbstractIngredient;
import net.minecraftforge.common.crafting.ingredients.IIngredientSerializer;
import net.minecraftforge.common.crafting.ingredients.NBTIngredient;

/** Forge 1.20.1's {@code StrictNBTIngredient}: one stack's item with exactly that stack's data, on Forge 26.2. */
public class LegacyStrictNBTIngredient extends AbstractIngredient {

    private final ItemStack stack;

    protected LegacyStrictNBTIngredient(ItemStack stack) {
        this.stack = stack;
    }

    public static LegacyStrictNBTIngredient of(ItemStack stack) {
        return new LegacyStrictNBTIngredient(stack);
    }

    /** The same item with the same data; an item's NBT became its components, so all of them must match. */
    @Override
    public boolean test(ItemStack other) {
        return other != null && ItemStack.isSameItemSameComponents(stack, other);
    }

    @Override
    public Stream<Holder<Item>> items() {
        return Stream.of(stack.typeHolder());
    }

    @Override
    public boolean isSimple() {
        return false;
    }

    // Forge's NBT ingredient compares custom data only, so this one stays in code and is never written.
    @Override
    public IIngredientSerializer<? extends Ingredient> serializer() {
        return NBTIngredient.SERIALIZER;
    }
}
