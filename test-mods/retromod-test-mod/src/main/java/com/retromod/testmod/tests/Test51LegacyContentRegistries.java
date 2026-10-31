/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.fabricmc.fabric.api.registry.CompostingChanceRegistry;
import net.fabricmc.fabric.api.registry.FlattenableBlockRegistry;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.fabricmc.fabric.api.registry.StrippableBlockRegistry;
import net.fabricmc.fabric.api.registry.TillableBlockRegistry;
import net.minecraft.block.Blocks;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Items;

/** Old content registries, called the way a 1.20.1 mod calls them at startup. */
public final class Test51LegacyContentRegistries implements Test {

    /** The fuel time Test52 looks for on the host. */
    public static final int BONE_BURN_TIME = 300;

    @Override
    public String description() {
        return "old strippable, tillable, flattenable, compost and fuel registries at startup";
    }

    @Override
    public TestResult run() {
        // 26.3 removed these registries and turned their jobs into block transformers and item
        // components, so each call must reach Retromod's stand-in there and the host elsewhere.
        StrippableBlockRegistry.register(Blocks.BONE_BLOCK, Blocks.BASALT);
        FlattenableBlockRegistry.register(Blocks.CLAY, Blocks.DIRT_PATH.getDefaultState());
        TillableBlockRegistry.register(Blocks.CLAY, HoeItem::canTillFarmland, Blocks.FARMLAND.getDefaultState());
        TillableBlockRegistry.register(Blocks.PACKED_MUD, HoeItem::canTillFarmland,
                HoeItem.createTillAction(Blocks.MUD.getDefaultState()));
        CompostingChanceRegistry.INSTANCE.add(Items.ROTTEN_FLESH, 0.3f);
        FuelRegistry.INSTANCE.add(Items.BONE, BONE_BURN_TIME);

        Integer burnTime = FuelRegistry.INSTANCE.get(Items.BONE);
        if (burnTime == null || burnTime != BONE_BURN_TIME) {
            return TestResult.fail("FuelRegistry.get answered " + burnTime + " for the fuel it was given");
        }
        return TestResult.success();
    }
}
