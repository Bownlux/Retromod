/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121.tests;

import com.retromod.testmod121.Test;
import com.retromod.testmod121.TestResult;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MarkerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** A block entity's and an entity's 1.21.1 NBT overrides, which 1.21.6 replaced with ValueOutput and ValueInput ones. */
public final class Test58LegacySaveOverrides implements Test {

    private final MinecraftServer server;

    public Test58LegacySaveOverrides(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String description() {
        return "1.21.1 block entity and entity NBT overrides still save and load their data";
    }

    @Override
    public TestResult run() {
        RegistryWrapper.WrapperLookup registries = server.getRegistryManager();
        // The host's save only calls the new method, which has to run the old override.
        CountingChest chest = new CountingChest(BlockPos.ORIGIN, Blocks.CHEST.getDefaultState());
        chest.count = 7;
        NbtCompound saved = chest.createNbt(registries);
        if (saved.getInt("RetromodCount") != 7) return TestResult.fail("the block entity save skipped the old override");
        CountingChest copy = new CountingChest(BlockPos.ORIGIN, Blocks.CHEST.getDefaultState());
        copy.read(saved, registries);
        if (copy.count != 7) return TestResult.fail("the block entity load read " + copy.count);

        // The old entity save and load calls reach the host's, which run the old overrides.
        ServerWorld world = server.getOverworld();
        CountingMarker marker = new CountingMarker(EntityType.MARKER, world);
        marker.anger = 5;
        NbtCompound entityTag = marker.writeNbt(new NbtCompound());
        if (entityTag.getInt("RetromodAnger") != 5) return TestResult.fail("the entity save skipped the old override");
        CountingMarker restored = new CountingMarker(EntityType.MARKER, world);
        restored.readNbt(entityTag);
        if (restored.anger != 5) return TestResult.fail("the entity load read " + restored.anger);
        return TestResult.success();
    }

    /** A chest that also saves a counter. */
    static final class CountingChest extends ChestBlockEntity {
        int count;

        CountingChest(BlockPos pos, BlockState state) {
            super(pos, state);
        }

        @Override
        protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
            super.writeNbt(nbt, registries);
            nbt.putInt("RetromodCount", count);
        }

        @Override
        protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
            super.readNbt(nbt, registries);
            count = nbt.getInt("RetromodCount");
        }
    }

    /** A marker that also saves a number. */
    static final class CountingMarker extends MarkerEntity {
        int anger;

        CountingMarker(EntityType<?> type, World world) {
            super(type, world);
        }

        @Override
        protected void writeCustomDataToNbt(NbtCompound nbt) {
            super.writeCustomDataToNbt(nbt);
            nbt.putInt("RetromodAnger", anger);
        }

        @Override
        protected void readCustomDataFromNbt(NbtCompound nbt) {
            super.readCustomDataFromNbt(nbt);
            anger = nbt.getInt("RetromodAnger");
        }
    }
}
