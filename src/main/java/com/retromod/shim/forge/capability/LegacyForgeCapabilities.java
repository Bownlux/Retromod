/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.capability;

/**
 * Stand-in for Forge's {@code ForgeCapabilities}. Each constant carries NeoForge's matching block,
 * entity and item capabilities, so a migrated mod asking a vanilla or NeoForge-native block entity
 * for {@code ITEM_HANDLER} gets the same handler a NeoForge mod would.
 */
public final class LegacyForgeCapabilities {

    public static final LegacyCapability<Object> ITEM_HANDLER = neo("IItemHandler", "ItemHandler", true, true);
    // Forge kept the item-stack fluid handler as a separate capability.
    public static final LegacyCapability<Object> FLUID_HANDLER = neo("IFluidHandler", "FluidHandler", true, false);
    public static final LegacyCapability<Object> FLUID_HANDLER_ITEM = neo("IFluidHandlerItem", "FluidHandler", false, true);
    public static final LegacyCapability<Object> ENERGY = neo("IEnergyStorage", "EnergyStorage", true, true);

    private LegacyForgeCapabilities() {}

    private static LegacyCapability<Object> neo(String name, String group,
            boolean worldHolders, boolean itemStacks) {
        Object block = null;
        Object entity = null;
        Object item = null;
        try {
            Class<?> holder = Class.forName("net.neoforged.neoforge.capabilities.Capabilities$" + group,
                    true, LegacyForgeCapabilities.class.getClassLoader());
            if (worldHolders) {
                block = holder.getField("BLOCK").get(null);
                entity = holder.getField("ENTITY").get(null);
            }
            if (itemStacks) item = holder.getField("ITEM").get(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            // Not a NeoForge host: the capability stays a plain token answered by mod providers.
        }
        return new LegacyCapability<>(name, block, entity, item);
    }
}
