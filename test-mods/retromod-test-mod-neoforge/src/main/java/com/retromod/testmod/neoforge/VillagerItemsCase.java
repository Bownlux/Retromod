/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.google.common.collect.ImmutableSet;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.Set;

/** Farmer's Delight 1.21.1 adds its crops to the items villagers pick up; 1.21.2 turned that set into a tag. */
final class VillagerItemsCase {

    private VillagerItemsCase() {}

    /** Reads the set, writes it back with one more item, and finds the item in it. */
    static boolean addsAnItem() {
        Set<Item> wanted = new HashSet<>(Villager.WANTED_ITEMS);
        wanted.add(Items.STICK);
        Villager.WANTED_ITEMS = ImmutableSet.copyOf(wanted);
        return Villager.WANTED_ITEMS.contains(Items.STICK);
    }
}
