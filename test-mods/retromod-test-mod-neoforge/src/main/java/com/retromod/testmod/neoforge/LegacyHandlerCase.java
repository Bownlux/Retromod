/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * #366: NeoForge 26.3 removed the item, fluid, and energy handler APIs that ProjectE and most
 * machine mods build on. Bedrock gets one of each through the old capabilities, and a vanilla chest
 * is reached through the old item capability.
 */
final class LegacyHandlerCase {

    static final ItemStackHandler ITEMS = new ItemStackHandler(3);
    static final FluidTank TANK = new FluidTank(4000);
    static final EnergyStorage ENERGY = new EnergyStorage(1000);

    private LegacyHandlerCase() {}

    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.ItemHandler.BLOCK, (level, pos, state, blockEntity, side) -> ITEMS, Blocks.BEDROCK);
        event.registerBlock(Capabilities.FluidHandler.BLOCK, (level, pos, state, blockEntity, side) -> TANK, Blocks.BEDROCK);
        event.registerBlock(Capabilities.EnergyStorage.BLOCK, (level, pos, state, blockEntity, side) -> ENERGY, Blocks.BEDROCK);
    }

    /** The first step that went wrong, or success. */
    static String run(ServerLevel level) {
        ItemStack rest = ITEMS.insertItem(0, new ItemStack(Items.DIRT, 64), false);
        if (!rest.isEmpty()) return "fail: the handler left " + rest.getCount() + " dirt";
        if (ITEMS.extractItem(0, 10, false).getCount() != 10) return "fail: extractItem did not take 10";
        CompoundTag saved = ITEMS.serializeNBT(level.registryAccess());
        ItemStackHandler loaded = new ItemStackHandler(3);
        loaded.deserializeNBT(level.registryAccess(), saved);
        if (loaded.getStackInSlot(0).getCount() != 54) return "fail: the saved handler read back " + loaded.getStackInSlot(0);

        BlockPos bedrock = new BlockPos(0, 301, 0);
        level.setBlockAndUpdate(bedrock, Blocks.BEDROCK.defaultBlockState());
        if (level.getCapability(Capabilities.ItemHandler.BLOCK, bedrock, Direction.UP) != ITEMS) {
            return "fail: the old item capability did not give back the registered handler";
        }
        String current = currentCapabilitySees(level, bedrock);
        if (current != null) return current;

        BlockPos chest = new BlockPos(2, 301, 0);
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        IItemHandler chestItems = level.getCapability(Capabilities.ItemHandler.BLOCK, chest, null);
        if (chestItems == null) return "fail: the old item capability found no handler on a chest";
        if (!chestItems.insertItem(0, new ItemStack(Items.DIAMOND, 5), false).isEmpty()) return "fail: the chest refused diamonds";
        if (!(level.getBlockEntity(chest) instanceof Container container) || container.getItem(0).getCount() != 5) {
            return "fail: the diamonds did not reach the chest";
        }

        IFluidHandler tank = level.getCapability(Capabilities.FluidHandler.BLOCK, bedrock, Direction.UP);
        if (tank == null || tank.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE) != 1000) {
            return "fail: the tank did not take water";
        }
        if (TANK.drain(400, IFluidHandler.FluidAction.EXECUTE).getAmount() != 400 || TANK.getFluidAmount() != 600) {
            return "fail: the tank drained wrong, holding " + TANK.getFluidAmount();
        }
        IEnergyStorage energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, bedrock, Direction.UP);
        if (energy == null || energy.receiveEnergy(100, false) != 100 || ENERGY.extractEnergy(40, false) != 40
                || ENERGY.getEnergyStored() != 60) {
            return "fail: the energy storage moved energy wrong";
        }
        return "success";
    }

    /** On 26.x the current item capability, read by reflection, must see the same handler. */
    private static String currentCapabilitySees(ServerLevel level, BlockPos pos) {
        try {
            Class<?> item = Class.forName("net.neoforged.neoforge.capabilities.Capabilities$Item");
            Object capability = item.getField("BLOCK").get(null);
            Class<?> blockCapability = Class.forName("net.neoforged.neoforge.capabilities.BlockCapability");
            Object handler = level.getClass().getMethod("getCapability", blockCapability, BlockPos.class, Object.class)
                    .invoke(level, capability, pos, Direction.UP);
            if (handler == null) return "fail: the current item capability does not see the old handler";
            int size = (int) handler.getClass().getMethod("size").invoke(handler);
            return size == 3 ? null : "fail: the current item capability sees " + size + " slots";
        } catch (ClassNotFoundException olderHost) {
            return null;
        } catch (ReflectiveOperationException e) {
            return "fail: " + e;
        }
    }
}
