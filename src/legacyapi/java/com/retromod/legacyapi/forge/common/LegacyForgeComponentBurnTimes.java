/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.common;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
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
import net.minecraftforge.common.extensions.IForgeItemStack;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.server.ServerLifecycleHooks;

/** Forge 1.20.1's {@code ForgeHooks.getBurnTime(stack, type)} on Forge 26.3, which moved fuel into an item component. */
public final class LegacyForgeComponentBurnTimes {

    private LegacyForgeComponentBurnTimes() {}

    /** The item's own burn time, else its cooking fuel component's, then Forge's burn time event, as the old hook ran them. */
    public static int getBurnTime(ItemStack stack, RecipeType<?> type) {
        if (stack.isEmpty()) return 0;
        // Forge 26.3's stack read asks the item, whose default answers -1 for "no own value".
        int own = ((IForgeItemStack) (Object) stack).getBurnTime(type);
        return ForgeEventFactory.getItemBurnTime(stack, own != -1 ? own : componentBurnTime(stack), type);
    }

    /** The burn time the stack's cooking fuel component gives in a plain furnace, or 0. */
    static int componentBurnTime(ItemStack stack) {
        CookingFuel fuel = stack.get(DataComponents.COOKING_FUEL);
        if (fuel == null) return 0;
        ResolvableInt time = fuel.burnTime();
        // A number needs no world, so it also answers on a client.
        if (time instanceof ResolvableInt.Constant constant) return constant.value();
        // A named provider is a data pack value the server computes; without a server there is no answer.
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return 0;
        ServerLevel level = server.overworld();
        FurnaceBlockEntity furnace = new FurnaceBlockEntity(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.BLOCK_STATE, Blocks.FURNACE.defaultBlockState())
                .withParameter(LootContextParams.BLOCK_ENTITY, furnace)
                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                .withParameter(LootContextParams.CONTAINER, furnace)
                .create(LootContextParamSets.CONTAINER_PROCESS);
        // A random of its own: the world's is not safe off the server thread, and a read must not advance it.
        return time.get(new LootContext.Builder(params).withOptionalRandomSource(RandomSource.create()).create(Optional.empty()), 0);
    }
}
