/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.common.extensions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.loot.NeoForgeLootContextParams;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Optional;

/** NeoForge 21.1's {@code getBurnTime} reads, answered from the cooking fuel component that 26.3 gives a fuel. */
public final class LegacyBurnTimes {

    private LegacyBurnTimes() {}

    /** {@code ItemStack.getBurnTime(RecipeType)}: 0 for an empty stack, else the item's burn time. */
    public static int getBurnTime(ItemStack stack, RecipeType<?> type) {
        if (stack.isEmpty()) return 0;
        return Math.max(0, getBurnTime(stack.getItem(), stack, type));
    }

    /** {@code Item.getBurnTime(ItemStack, RecipeType)}: a mod item's own answer, else the stack's fuel component. */
    public static int getBurnTime(Item item, ItemStack stack, RecipeType<?> type) {
        return LegacyBurnTimeOverrides.burnTime(item, stack, type, LegacyBurnTimes::componentBurnTime);
    }

    /** A mod override's {@code super.getBurnTime(stack, type)}, which reads what the item would without the override. */
    public static int superGetBurnTime(Item item, ItemStack stack, RecipeType<?> type) {
        return componentBurnTime(stack);
    }

    /** The burn time the stack's cooking fuel component gives in a plain furnace, or 0. */
    static int componentBurnTime(ItemStack stack) {
        CookingFuel fuel = stack.get(DataComponents.COOKING_FUEL);
        if (fuel == null) return 0;
        ResolvableInt time = fuel.burnTime();
        // A number needs no world, so it also answers on a client.
        if (time instanceof ResolvableInt.Constant constant) return constant.value();
        // A named provider is a data pack value the server computes; without a server there is no answer.
        ServerLevel level = runningLevel();
        if (level == null) return 0;
        return time.get(furnaceContext(level, stack), 0);
    }

    /**
     * The loot context a plain furnace gives {@code stack}, as NeoForge 26.3's furnace builds it. 21.1's burn times
     * ignored the block, and 26.3 shortens them only in a fast cooking block, so a furnace keeps the old values.
     */
    static LootContext furnaceContext(ServerLevel level, ItemStack stack) {
        FurnaceBlockEntity furnace = new FurnaceBlockEntity(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.BLOCK_STATE, Blocks.FURNACE.defaultBlockState())
                .withParameter(LootContextParams.BLOCK_ENTITY, furnace)
                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .withParameter(LootContextParams.CONTAINER, furnace)
                .withOptionalParameter(NeoForgeLootContextParams.QUERIED_STACK, stack)
                .create(LootContextParamSets.CONTAINER_PROCESS);
        // A random of its own: the world's is not safe off the server thread, and a read must not advance it.
        return new LootContext.Builder(params).withOptionalRandomSource(RandomSource.create()).create(Optional.empty());
    }

    /** The running server's overworld, which a single player game has too, or null. */
    private static ServerLevel runningLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.overworld();
    }
}
