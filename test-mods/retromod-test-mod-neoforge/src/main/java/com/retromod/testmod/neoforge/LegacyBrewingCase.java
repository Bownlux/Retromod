/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

/** #366: 26.3 replaced PotionBrewing with brewing recipes, and ProjectE reads the old table to price potions. */
final class LegacyBrewingCase {

    private LegacyBrewingCase() {}

    /** The first step that went wrong, or success. */
    static String run(MinecraftServer server) {
        PotionBrewing brewing = server.potionBrewing();
        ItemStack water = PotionContents.createItemStack(Items.POTION, Potions.WATER);
        ItemStack wart = new ItemStack(Items.NETHER_WART);
        if (!brewing.isIngredient(wart) || !brewing.hasMix(water, wart)) return "fail: nether wart does not brew water";
        ItemStack awkward = brewing.mix(wart, water);
        PotionContents contents = awkward.get(DataComponents.POTION_CONTENTS);
        if (awkward.getItem() != Items.POTION || contents == null || !contents.is(Potions.AWKWARD)) {
            return "fail: water and nether wart brewed " + awkward;
        }
        if (!brewing.isBrewablePotion(Potions.AWKWARD)) return "fail: awkward is not brewable";
        if (!brewing.hasContainerMix(awkward, new ItemStack(Items.GUNPOWDER))) return "fail: gunpowder makes no splash potion";
        if (brewing.hasMix(water, new ItemStack(Items.DIRT))) return "fail: dirt brews water";
        return "success";
    }
}
