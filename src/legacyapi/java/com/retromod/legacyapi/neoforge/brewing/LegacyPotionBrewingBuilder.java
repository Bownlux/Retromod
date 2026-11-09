/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.brewing;

import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.forge.embedded.LegacyBrewingStore;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.crafting.Ingredient;

/** The old {@code PotionBrewing.Builder}: it records each mix of this post for the brewing recipe files 26.3 reads. */
public class LegacyPotionBrewingBuilder {

    /** {@code addRecipe(IBrewingRecipe)}: a recipe that answers in code. */
    public void addRecipe(Object recipe) {
        LegacyBrewingStore.addEventRecipe(recipe);
    }

    public void addRecipe(Ingredient input, Ingredient ingredient, ItemStack output) {
        LegacyBrewingStore.addEventRecipe(input, ingredient, output);
    }

    /** One potion into another in every bottle. */
    public void addMix(Holder<Potion> from, Item ingredient, Holder<Potion> to) {
        LegacyBrewingStore.addEventRecipe(LegacyBrewingMix.potion(from, ingredient, to));
    }

    /** Water gives mundane and awkward gives the potion, as vanilla's start mix did. */
    public void addStartMix(Item ingredient, Holder<Potion> potion) {
        LegacyBrewingStore.addEventRecipe(LegacyBrewingMix.start(ingredient, potion, false));
        LegacyBrewingStore.addEventRecipe(LegacyBrewingMix.start(ingredient, potion, true));
    }

    /** One bottle into another, keeping its potion. */
    public void addContainerRecipe(Item from, Item ingredient, Item to) {
        LegacyBrewingStore.addEventRecipe(LegacyBrewingMix.container(from, ingredient, to));
    }

    /** A new bottle type; 26.3's recipes name their bottles, so mixes still apply to the vanilla three. */
    public void addContainer(Item container) {
        LegacyLog.info("Legacy brewing: the old addContainer(" + container + ") has no 26.3 recipe form, so mixes"
                + " added with addMix apply to the potion, splash potion, and lingering potion only");
    }
}
