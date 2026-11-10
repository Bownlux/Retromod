/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Retromod gives mods built before 1.20.5 their loot table ids back. This mod is built for 1.21.1,
 * where the constants are already keys, so it must keep reading keys and the key set.
 */
final class LootTableKeysCase {

    private LootTableKeysCase() {}

    /** A constant is still the key, with the table's id, and all() still holds that key. */
    static boolean constantsStayKeys() {
        ResourceKey<LootTable> pyramid = BuiltInLootTables.DESERT_PYRAMID;
        return pyramid.location().equals(ResourceLocation.withDefaultNamespace("chests/desert_pyramid"))
                && BuiltInLootTables.all().contains(pyramid);
    }
}
