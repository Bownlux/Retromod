/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.registries;

import com.google.common.collect.Table;
import com.retromod.shim.common.embedded.LegacyLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.neoforged.neoforge.registries.GameData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * NeoForge's {@code FlowerPotBlock.addPlant(flower, fullPot)}, which NeoForge 26.3 replaced with a table built from the pots.
 * A full registry clear and rebake drops an entry added here unless its full pot was built with this pot as its empty pot.
 */
public final class LegacyFlowerPots {

    private record Pending(FlowerPotBlock emptyPot, Identifier flower, Supplier<? extends Block> fullPot) {}

    // Plants whose pot could not be resolved yet, tried again on the next call.
    private static final List<Pending> PENDING = new ArrayList<>();

    private LegacyFlowerPots() {}

    /** Puts the full pot under this empty pot and the flower's block, where 26.3 looks a full pot up. */
    public static void addPlant(FlowerPotBlock emptyPot, Identifier flower, Supplier<? extends Block> fullPot) {
        List<Pending> waiting;
        synchronized (PENDING) {
            PENDING.add(new Pending(emptyPot, flower, fullPot));
            waiting = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        for (Pending plant : waiting) {
            if (!tryAdd(plant)) {
                synchronized (PENDING) {
                    PENDING.add(plant);
                }
            }
        }
    }

    /** Adds one plant, or returns false when its blocks cannot be read yet, so it waits for the next call. */
    private static boolean tryAdd(Pending plant) {
        Block flowerBlock;
        Block full;
        try {
            // The old map resolved the pot lazily; an unregistered pot supplier throws here instead.
            flowerBlock = BuiltInRegistries.BLOCK.getValue(plant.flower());
            full = plant.fullPot().get();
        } catch (RuntimeException notYet) {
            LegacyLog.debug("Waiting to add the flower pot for " + plant.flower() + ": " + notYet);
            return false;
        }
        // An id that names no block reads as air; the old map only ever missed it, so nothing is added.
        if (flowerBlock == null || flowerBlock == Blocks.AIR || full == null) return true;
        Table<Block, Block, Block> table = GameData.getFlowerPotBlockTable();
        // NeoForge fills the table at bake on another thread; one write at a time keeps it consistent.
        synchronized (table) {
            table.put(plant.emptyPot(), flowerBlock, full);
        }
        return true;
    }
}
