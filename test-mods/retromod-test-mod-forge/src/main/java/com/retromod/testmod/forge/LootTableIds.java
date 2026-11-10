/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

import java.util.HashMap;
import java.util.Map;

/**
 * 1.20.5 turned loot table ids into registry keys. Sophisticated Backpacks keys its entity loot
 * mapping by BuiltInLootTables constants, and its config check died with NoSuchFieldError on
 * Forge 26.2. Kept out of the mod class, so a member a host lacks fails only its own check.
 */
final class LootTableIds {

    private LootTableIds() {}

    /** A constant reads as the same id every time, works as a map key, and is in all(). */
    static boolean constantsAreIds() {
        ResourceLocation pyramid = BuiltInLootTables.DESERT_PYRAMID;
        Map<ResourceLocation, String> byTable = new HashMap<>();
        byTable.put(pyramid, "pyramid");
        return pyramid.equals(new ResourceLocation("minecraft", "chests/desert_pyramid"))
                && pyramid == BuiltInLootTables.DESERT_PYRAMID
                && "pyramid".equals(byTable.get(new ResourceLocation("minecraft", "chests/desert_pyramid")))
                && BuiltInLootTables.all().contains(new ResourceLocation("minecraft", "chests/simple_dungeon"));
    }

    /** EMPTY, which 1.21.2 removed, matches a block without a table; an entity type names its own table. */
    static boolean emptyAndGetters() {
        return new ResourceLocation("minecraft", "empty").equals(BuiltInLootTables.EMPTY)
                && BuiltInLootTables.EMPTY.equals(Blocks.AIR.getLootTable())
                && new ResourceLocation("minecraft", "entities/zombie").equals(EntityType.ZOMBIE.getDefaultLootTable());
    }
}
