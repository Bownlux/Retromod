/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.brewing;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

/** One old builder mix, answering as the old recipe interface did, so the brewing store can write it as recipe files. */
public final class LegacyBrewingMix {

    private final Holder<Potion> from;
    private final Holder<Potion> to;
    private final Item fromBottle;
    private final Item ingredient;
    private final Item toBottle;
    // A start mix names water or awkward, which are read once the game's potions exist.
    private final String startFrom;
    private final boolean startToNamed;

    private LegacyBrewingMix(Holder<Potion> from, Item ingredient, Holder<Potion> to, Item fromBottle, Item toBottle,
            String startFrom, boolean startToNamed) {
        this.from = from;
        this.ingredient = ingredient;
        this.to = to;
        this.fromBottle = fromBottle;
        this.toBottle = toBottle;
        this.startFrom = startFrom;
        this.startToNamed = startToNamed;
    }

    static LegacyBrewingMix potion(Holder<Potion> from, Item ingredient, Holder<Potion> to) {
        return new LegacyBrewingMix(from, ingredient, to, null, null, null, false);
    }

    /** {@code awkward} false: water to mundane; true: awkward to {@code potion}. */
    static LegacyBrewingMix start(Item ingredient, Holder<Potion> potion, boolean awkward) {
        return new LegacyBrewingMix(null, ingredient, awkward ? potion : null, null, null,
                awkward ? "AWKWARD" : "WATER", awkward);
    }

    static LegacyBrewingMix container(Item from, Item ingredient, Item to) {
        return new LegacyBrewingMix(null, ingredient, null, from, to, null, false);
    }

    public boolean isInput(ItemStack stack) {
        Holder<Potion> potion = potionOf(stack);
        if (potion == null) return false;
        if (fromBottle != null) return stack.getItem() == fromBottle;
        Holder<Potion> wanted = startFrom != null ? vanilla(startFrom) : from;
        return wanted != null && potion.value() == wanted.value();
    }

    public boolean isIngredient(ItemStack stack) {
        return stack.getItem() == ingredient;
    }

    public ItemStack getOutput(ItemStack input, ItemStack reagent) {
        if (!isInput(input) || !isIngredient(reagent)) return ItemStack.EMPTY;
        if (toBottle != null) return PotionContents.createItemStack(toBottle, potionOf(input));
        Holder<Potion> result = startFrom == null || startToNamed ? to : vanilla("MUNDANE");
        return result == null ? ItemStack.EMPTY : PotionContents.createItemStack(input.getItem(), result);
    }

    private static Holder<Potion> potionOf(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents == null ? null : contents.potion().orElse(null);
    }

    // Read by name, so loading this class never starts the game's potion list early.
    @SuppressWarnings("unchecked")
    private static Holder<Potion> vanilla(String field) {
        try {
            return (Holder<Potion>) Class.forName("net.minecraft.world.item.alchemy.Potions", true,
                    LegacyBrewingMix.class.getClassLoader()).getField(field).get(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
