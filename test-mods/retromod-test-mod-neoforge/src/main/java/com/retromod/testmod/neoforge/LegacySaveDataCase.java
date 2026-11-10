/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 1.21.6 moved block entity and entity saving to ValueOutput and ValueInput. A 1.21.1 override of
 * saveAdditional(CompoundTag, Provider) or addAdditionalSaveData(CompoundTag) was never called
 * again, so Farmer's Delight and Cataclysm lost their data on every save.
 */
final class LegacySaveDataCase {

    private LegacySaveDataCase() {}

    /** The first step that went wrong, or success. */
    static String run(ServerLevel level) {
        HolderLookup.Provider registries = level.registryAccess();
        // The host's save only calls the new method, which has to run the old override.
        CountingChest chest = new CountingChest(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        chest.count = 7;
        CompoundTag saved = chest.saveWithoutMetadata(registries);
        if (saved.getInt("RetromodCount") != 7) return "fail: the block entity save skipped the old override";
        CountingChest copy = new CountingChest(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        copy.loadWithComponents(saved, registries);
        if (copy.count != 7) return "fail: the block entity load read " + copy.count;

        // The old entity save and load calls reach the host's, which run the old overrides.
        CountingMarker marker = new CountingMarker(EntityType.MARKER, level);
        marker.anger = 5;
        CompoundTag entityTag = marker.saveWithoutId(new CompoundTag());
        if (entityTag.getInt("RetromodAnger") != 5) return "fail: the entity save skipped the old override";
        CountingMarker restored = new CountingMarker(EntityType.MARKER, level);
        restored.load(entityTag);
        if (restored.anger != 5) return "fail: the entity load read " + restored.anger;
        return "success";
    }

    /** A chest that also saves a counter. */
    static final class CountingChest extends ChestBlockEntity {
        int count;

        CountingChest(BlockPos pos, BlockState state) {
            super(pos, state);
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            tag.putInt("RetromodCount", count);
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            count = tag.getInt("RetromodCount");
        }
    }

    /** A marker that also saves a number. */
    static final class CountingMarker extends Marker {
        int anger;

        CountingMarker(EntityType<?> type, Level level) {
            super(type, level);
        }

        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {
            super.addAdditionalSaveData(tag);
            tag.putInt("RetromodAnger", anger);
        }

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {
            super.readAdditionalSaveData(tag);
            anger = tag.getInt("RetromodAnger");
        }
    }
}
