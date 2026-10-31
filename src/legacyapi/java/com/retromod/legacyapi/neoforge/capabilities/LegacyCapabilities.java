/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.capabilities;

import com.retromod.legacyapi.neoforge.bridge.EnergyHandlerStorage;
import com.retromod.legacyapi.neoforge.bridge.EnergyStorageHandler;
import com.retromod.legacyapi.neoforge.bridge.FluidHandlerResource;
import com.retromod.legacyapi.neoforge.bridge.ItemHandlerResource;
import com.retromod.legacyapi.neoforge.bridge.ResourceFluidHandler;
import com.retromod.legacyapi.neoforge.bridge.ResourceFluidHandlerItem;
import com.retromod.legacyapi.neoforge.bridge.ResourceItemHandler;
import com.retromod.legacyapi.neoforge.energy.IEnergyStorage;
import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandler;
import com.retromod.legacyapi.neoforge.fluids.capability.IFluidHandlerItem;
import com.retromod.legacyapi.neoforge.items.IItemHandler;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.capabilities.BaseCapability;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Links each old capability to the current one it stands for, and converts handlers between the two. */
public final class LegacyCapabilities {

    /** What an old capability holds. */
    public enum Kind { ITEM, FLUID, FLUID_ITEM, ENERGY }

    /** The current capability behind an old one, and what both hold. */
    public record Target(BaseCapability<?, ?> current, Kind kind) {}

    // Keyed by the capability object, which NeoForge shares among all mods that create it.
    private static final Map<BaseCapability<?, ?>, Target> TARGETS = new ConcurrentHashMap<>();
    // One adapter per old handler, so every lookup in a transaction undoes through the same one.
    private static final Map<Object, Object> ADAPTERS = Collections.synchronizedMap(new WeakHashMap<>());

    private LegacyCapabilities() {}

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("retromod", path);
    }

    /** Records that {@code legacy} stands for {@code current}. */
    static <C extends BaseCapability<?, ?>> C link(C legacy, BaseCapability<?, ?> current, Kind kind) {
        TARGETS.put(legacy, new Target(current, kind));
        return legacy;
    }

    /** The current capability behind {@code capability}, or null when it is not an old one. */
    public static Target target(Object capability) {
        return capability instanceof BaseCapability<?, ?> base ? TARGETS.get(base) : null;
    }

    /** Whether {@code handler} already has the old type the capability promises. */
    public static boolean isOld(Kind kind, Object handler) {
        return switch (kind) {
            case ITEM -> handler instanceof IItemHandler;
            case FLUID -> handler instanceof IFluidHandler;
            case FLUID_ITEM -> handler instanceof IFluidHandlerItem;
            case ENERGY -> handler instanceof IEnergyStorage;
        };
    }

    /** An old handler as the current type; one that wraps a current handler gives that one back. */
    public static Object toCurrent(Kind kind, Object handler) {
        if (handler == null) return null;
        return switch (kind) {
            case ITEM -> handler instanceof ResourceItemHandler wrapped ? wrapped.handler()
                    : handler instanceof IItemHandler items ? ADAPTERS.computeIfAbsent(items, ignored -> new ItemHandlerResource(items)) : null;
            case FLUID, FLUID_ITEM -> handler instanceof ResourceFluidHandler wrapped ? wrapped.handler()
                    : handler instanceof IFluidHandler fluids ? ADAPTERS.computeIfAbsent(fluids, ignored -> new FluidHandlerResource(fluids)) : null;
            case ENERGY -> handler instanceof EnergyHandlerStorage wrapped ? wrapped.handler()
                    : handler instanceof IEnergyStorage energy ? ADAPTERS.computeIfAbsent(energy, ignored -> new EnergyStorageHandler(energy)) : null;
        };
    }

    /** A current handler as the old type; one that wraps an old handler gives that one back. */
    @SuppressWarnings("unchecked")
    public static Object toOld(Kind kind, Object handler, ItemAccess access) {
        if (handler == null) return null;
        return switch (kind) {
            case ITEM -> handler instanceof ItemHandlerResource adapter ? adapter.handler()
                    : new ResourceItemHandler((ResourceHandler<ItemResource>) handler);
            case FLUID -> handler instanceof FluidHandlerResource adapter ? adapter.handler()
                    : new ResourceFluidHandler((ResourceHandler<FluidResource>) handler);
            case FLUID_ITEM -> handler instanceof FluidHandlerResource adapter && adapter.handler() instanceof IFluidHandlerItem item ? item
                    : access == null ? null : new ResourceFluidHandlerItem((ResourceHandler<FluidResource>) handler, access);
            case ENERGY -> handler instanceof EnergyStorageHandler adapter ? adapter.storage()
                    : new EnergyHandlerStorage((EnergyHandler) handler);
        };
    }
}
