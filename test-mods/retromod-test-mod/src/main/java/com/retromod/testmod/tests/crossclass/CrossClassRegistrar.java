/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests.crossclass;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * The loader half: the only class that names the ids, through a helper that registers under the
 * mod's namespace, as Longwings' {@code LongwingsBlocksFabric} does. The item goes first, so it is
 * the item's statement that initializes the block class.
 */
public final class CrossClassRegistrar {

    private static boolean registered;

    private CrossClassRegistrar() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        registerItem("cross_class_item", CrossClassItems.CROSS_CLASS_ITEM);
        registerBlock("cross_class_block", CrossClassBlocks.CROSS_CLASS_BLOCK);
    }

    private static Block registerBlock(String name, Block block) {
        return Registry.register(Registries.BLOCK, new Identifier("retromod_test_mod", name), block);
    }

    private static Item registerItem(String name, Item item) {
        return Registry.register(Registries.ITEM, new Identifier("retromod_test_mod", name), item);
    }
}
