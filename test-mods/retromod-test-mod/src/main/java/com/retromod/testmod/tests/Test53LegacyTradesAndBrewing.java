/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.fabricmc.fabric.api.object.builder.v1.trade.TradeOfferHelper;
import net.fabricmc.fabric.api.registry.FabricBrewingRecipeRegistry;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.fabricmc.fabric.api.registry.OxidizableBlocksRegistry;
import net.fabricmc.fabric.api.registry.VillagerInteractionRegistries;
import net.minecraft.block.Blocks;
import net.minecraft.block.Oxidizable;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;
import net.minecraft.recipe.Ingredient;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.VillagerProfession;

/** Old trade, brewing, oxidation, fire and villager registries, called the way a 1.20.1 mod calls them. */
public final class Test53LegacyTradesAndBrewing implements Test {

    @Override
    public String description() {
        return "old trade, brewing, oxidizable, flammable and villager registries at startup";
    }

    @Override
    public TestResult run() {
        // 26.1 renamed the oxidation, fire and villager methods; the old names must reach the new ones.
        OxidizableBlocksRegistry.registerOxidizableBlockPair(Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE);
        OxidizableBlocksRegistry.registerWaxableBlockPair(Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE);
        VillagerInteractionRegistries.registerCollectable(Items.BONE);
        VillagerInteractionRegistries.registerFood(Items.APPLE, 2);
        FlammableBlockRegistry.getDefaultInstance().add(Blocks.BONE_BLOCK, 5, 20);

        // Trades and brewing became data in 26.x; Retromod writes them into the world when the server starts.
        TradeOfferHelper.registerVillagerOffers(VillagerProfession.FARMER, 1, factories -> factories.add(
                (entity, random) -> new TradeOffer(new ItemStack(Items.BONE, 10), new ItemStack(Items.EMERALD), 12, 2, 0.05f)));
        TradeOfferHelper.registerWanderingTraderOffers(1, factories -> factories.add(
                (entity, random) -> new TradeOffer(new ItemStack(Items.EMERALD, 2), new ItemStack(Items.BONE_BLOCK), 8, 1, 0.05f)));
        FabricBrewingRecipeRegistry.registerPotionRecipe(Potions.AWKWARD, Ingredient.ofItems(Items.BONE), Potions.LUCK);

        if (!Oxidizable.getIncreasedOxidationBlock(Blocks.MOSSY_COBBLESTONE).map(Blocks.COBBLESTONE::equals).orElse(false)) {
            return TestResult.fail("mossy cobblestone does not weather into cobblestone");
        }
        FlammableBlockRegistry.Entry fire = FlammableBlockRegistry.getDefaultInstance().get(Blocks.BONE_BLOCK);
        if (fire.getBurnChance() != 5 || fire.getSpreadChance() != 20) {
            return TestResult.fail("bone block burns with " + fire.getBurnChance() + "/" + fire.getSpreadChance());
        }
        return TestResult.success();
    }
}
