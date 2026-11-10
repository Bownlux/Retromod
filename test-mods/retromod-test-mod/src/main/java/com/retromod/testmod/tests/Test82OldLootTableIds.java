/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.loot.LootTables;
import net.minecraft.util.Identifier;

/** Loot table constants and getters read as identifiers, as a 1.20.1 loot table event compares them with the event's id. */
public final class Test82OldLootTableIds implements Test {

    @Override
    public String description() {
        return "LootTables constants, getAll(), EMPTY and the block and entity loot table ids read as identifiers";
    }

    @Override
    public TestResult run() {
        Identifier pyramid = LootTables.DESERT_PYRAMID_CHEST;
        Map<Identifier, String> byTable = new HashMap<>();
        byTable.put(pyramid, "pyramid");
        if (!pyramid.equals(new Identifier("minecraft", "chests/desert_pyramid")) || pyramid != LootTables.DESERT_PYRAMID_CHEST) {
            return TestResult.fail("DESERT_PYRAMID_CHEST read as " + pyramid);
        }
        if (!"pyramid".equals(byTable.get(new Identifier("minecraft", "chests/desert_pyramid")))) {
            return TestResult.fail("the id does not work as a map key");
        }
        if (!LootTables.getAll().contains(new Identifier("minecraft", "chests/simple_dungeon"))) {
            return TestResult.fail("getAll() lacks chests/simple_dungeon");
        }
        // 1.21.2 removed EMPTY; a block without a table still answers it.
        if (!LootTables.EMPTY.equals(Blocks.AIR.getLootTableId())) {
            return TestResult.fail("air's table is " + Blocks.AIR.getLootTableId() + ", EMPTY is " + LootTables.EMPTY);
        }
        Identifier zombie = EntityType.ZOMBIE.getLootTableId();
        return new Identifier("minecraft", "entities/zombie").equals(zombie)
                ? TestResult.success() : TestResult.fail("the zombie's table is " + zombie);
    }
}
