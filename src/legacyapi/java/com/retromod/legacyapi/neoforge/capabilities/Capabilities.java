/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.capabilities;

import com.retromod.legacyapi.neoforge.capabilities.LegacyCapabilities.Kind;
import com.retromod.legacyapi.neoforge.energy.IEnergyStorage;
import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandler;
import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandlerItem;
import com.retromod.legacyapi.neoforge.items.IItemHandler;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.capabilities.ItemCapability;

/** NeoForge 1.21's item, fluid, and energy capabilities, each linked to the 26.3 one that holds the same thing. */
public final class Capabilities {

    private Capabilities() {}

    // Typed Object, because NeoForge refuses a second capability under the same id with another
    // type, and each mod has its own copy of the old handler types.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Class<T> any() {
        return (Class) Object.class;
    }

    public static final class ItemHandler {
        public static final BlockCapability<IItemHandler, Direction> BLOCK = LegacyCapabilities.link(
                BlockCapability.<IItemHandler>createSided(LegacyCapabilities.id("legacy_item_handler"), any()),
                net.neoforged.neoforge.capabilities.Capabilities.Item.BLOCK, Kind.ITEM);
        public static final EntityCapability<IItemHandler, Void> ENTITY = LegacyCapabilities.link(
                EntityCapability.<IItemHandler>createVoid(LegacyCapabilities.id("legacy_item_handler"), any()),
                net.neoforged.neoforge.capabilities.Capabilities.Item.ENTITY, Kind.ITEM);
        public static final EntityCapability<IItemHandler, Direction> ENTITY_AUTOMATION = LegacyCapabilities.link(
                EntityCapability.<IItemHandler>createSided(LegacyCapabilities.id("legacy_item_handler_automation"), any()),
                net.neoforged.neoforge.capabilities.Capabilities.Item.ENTITY_AUTOMATION, Kind.ITEM);
        public static final ItemCapability<IItemHandler, Void> ITEM = LegacyCapabilities.link(
                ItemCapability.<IItemHandler>createVoid(LegacyCapabilities.id("legacy_item_handler"), any()),
                net.neoforged.neoforge.capabilities.Capabilities.Item.ITEM, Kind.ITEM);

        private ItemHandler() {}
    }

    public static final class FluidHandler {
        public static final BlockCapability<IFluidHandler, Direction> BLOCK = LegacyCapabilities.link(
                BlockCapability.<IFluidHandler>createSided(LegacyCapabilities.id("legacy_fluid_handler"), any()),
                net.neoforged.neoforge.capabilities.Capabilities.Fluid.BLOCK, Kind.FLUID);
        public static final EntityCapability<IFluidHandler, Direction> ENTITY = LegacyCapabilities.link(
                EntityCapability.<IFluidHandler>createSided(LegacyCapabilities.id("legacy_fluid_handler"), any()),
                net.neoforged.neoforge.capabilities.Capabilities.Fluid.ENTITY, Kind.FLUID);
        public static final ItemCapability<IFluidHandlerItem, Void> ITEM = LegacyCapabilities.link(
                ItemCapability.<IFluidHandlerItem>createVoid(LegacyCapabilities.id("legacy_fluid_handler"), any()),
                net.neoforged.neoforge.capabilities.Capabilities.Fluid.ITEM, Kind.FLUID_ITEM);

        private FluidHandler() {}
    }

    public static final class EnergyStorage {
        public static final BlockCapability<IEnergyStorage, Direction> BLOCK = LegacyCapabilities.link(
                BlockCapability.<IEnergyStorage>createSided(LegacyCapabilities.id("legacy_energy_storage"), any()),
                net.neoforged.neoforge.capabilities.Capabilities.Energy.BLOCK, Kind.ENERGY);
        public static final EntityCapability<IEnergyStorage, Direction> ENTITY = LegacyCapabilities.link(
                EntityCapability.<IEnergyStorage>createSided(LegacyCapabilities.id("legacy_energy_storage"), any()),
                net.neoforged.neoforge.capabilities.Capabilities.Energy.ENTITY, Kind.ENERGY);
        public static final ItemCapability<IEnergyStorage, Void> ITEM = LegacyCapabilities.link(
                ItemCapability.<IEnergyStorage>createVoid(LegacyCapabilities.id("legacy_energy_storage"), any()),
                net.neoforged.neoforge.capabilities.Capabilities.Energy.ITEM, Kind.ENERGY);

        private EnergyStorage() {}
    }
}
