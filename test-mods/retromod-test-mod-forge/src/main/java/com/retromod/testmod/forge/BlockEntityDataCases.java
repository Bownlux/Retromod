/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Forge's persistent data on block entities and the 1.20.1 block entity save, which Forge 26.2 moved to ValueIO. */
final class BlockEntityDataCases {

    private BlockEntityDataCases() {}

    /** A chest saved the 1.20.1 way keeps its counter and its persistent data under ForgeData, and loads both back. */
    static String savesAndLoads() {
        MarkedChest chest = new MarkedChest();
        chest.count = 3;
        chest.getPersistentData().putInt("Marked", 5);
        CompoundTag saved = chest.saveWithoutMetadata();
        if (saved.getInt("Count") != 3) return "fail: the old saveAdditional did not run: " + saved;
        if (saved.getCompound("ForgeData").getInt("Marked") != 5) return "fail: no ForgeData in " + saved;
        MarkedChest loaded = new MarkedChest();
        loaded.load(saved);
        if (loaded.count != 3) return "fail: the old load did not run";
        return loaded.getPersistentData().getInt("Marked") == 5 ? "success" : "fail: ForgeData was not read back";
    }

    /** Lootr reads a vanilla chest's persistent data before it swaps the chest; each block entity keeps its own. */
    static boolean vanillaChestKeepsItsData() {
        ChestBlockEntity first = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        ChestBlockEntity second = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        first.getPersistentData().putString("Owner", "retromod");
        return first.getPersistentData() == first.getPersistentData()
                && "retromod".equals(first.getPersistentData().getString("Owner"))
                && second.getPersistentData().isEmpty();
    }

    /** A loot chest with a counter of its own, saved the way a 1.20.1 block entity is. */
    static final class MarkedChest extends ChestBlockEntity {
        int count;

        MarkedChest() {
            super(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        }

        @Override
        public void load(CompoundTag tag) {
            super.load(tag);
            count = tag.getInt("Count");
        }

        @Override
        protected void saveAdditional(CompoundTag tag) {
            super.saveAdditional(tag);
            tag.putInt("Count", count);
        }
    }
}
