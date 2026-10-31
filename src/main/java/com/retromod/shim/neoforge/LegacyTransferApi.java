/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.util.McReflect;

import java.util.List;

/** Rebuilds NeoForge's item, fluid, and energy handler APIs, which 26.3 removed, inside each translated jar that uses them. */
public final class LegacyTransferApi {

    static final String REBUILT = RebuiltApiClasses.ROOT + "neoforge/";
    private static final String NEOFORGE = "net/neoforged/neoforge/";
    static final String CALLS = REBUILT + "capabilities/CapabilityCalls";

    /** The removed classes, by their path under both roots; nested classes come with their outer class. */
    static final List<String> CLASSES = List.of(
            "items/IItemHandler", "items/IItemHandlerModifiable", "items/ItemStackHandler",
            "items/ComponentItemHandler", "items/ItemHandlerHelper", "items/SlotItemHandler",
            "items/ItemHandlerCopySlot",
            "items/wrapper/CombinedInvWrapper", "items/wrapper/EmptyItemHandler",
            "items/wrapper/EntityArmorInvWrapper", "items/wrapper/EntityEquipmentInvWrapper",
            "items/wrapper/EntityHandsInvWrapper", "items/wrapper/ForwardingItemHandler",
            "items/wrapper/InvWrapper", "items/wrapper/PlayerArmorInvWrapper", "items/wrapper/PlayerInvWrapper",
            "items/wrapper/PlayerMainInvWrapper", "items/wrapper/PlayerOffhandInvWrapper",
            "items/wrapper/RangedWrapper", "items/wrapper/RecipeWrapper", "items/wrapper/SidedInvWrapper",
            "fluids/IFluidTank", "fluids/FluidActionResult", "fluids/FluidUtil",
            "fluids/capability/IFluidHandler", "fluids/capability/IFluidHandler$FluidAction",
            "fluids/capability/IFluidHandlerItem",
            "fluids/capability/templates/EmptyFluidHandler", "fluids/capability/templates/VoidFluidHandler",
            "fluids/capability/templates/FluidTank",
            "fluids/capability/templates/FluidHandlerItemStack",
            "fluids/capability/templates/FluidHandlerItemStack$Consumable",
            "fluids/capability/templates/FluidHandlerItemStack$SwapEmpty",
            "fluids/capability/templates/FluidHandlerItemStackSimple",
            "fluids/capability/templates/FluidHandlerItemStackSimple$Consumable",
            "fluids/capability/templates/FluidHandlerItemStackSimple$SwapEmpty",
            "energy/IEnergyStorage", "energy/EnergyStorage", "energy/EmptyEnergyStorage",
            "energy/ComponentEnergyStorage");

    /** NeoForge 1.21's capability holders, which 26.1.2 replaced with resource handler ones. */
    static final List<String> CAPABILITY_HOLDERS = List.of(
            "capabilities/Capabilities$ItemHandler", "capabilities/Capabilities$FluidHandler",
            "capabilities/Capabilities$EnergyStorage");

    private LegacyTransferApi() {}

    /** Whether the host lost the handler APIs: read from the host when it can be, else from the target. */
    public static boolean hostRemovedHandlers() {
        if (ClassResourceInspector.read(NEOFORGE + "capabilities/Capabilities") != null) {
            return !ClassResourceInspector.exists(NEOFORGE + "items/IItemHandler");
        }
        return McReflect.isNeoForge()
                && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
    }

    /** Registers the rebuilt classes and points the removed names at them, on a host that lost them. */
    public static void register(RetromodTransformer t) {
        if (hostRemovedHandlers()) registerClasses(t);
    }

    static void registerClasses(RetromodTransformer t) {
        for (String path : CLASSES) {
            RebuiltApiClasses.registerTree(t, REBUILT + path);
            t.registerClassRedirect(NEOFORGE + path, REBUILT + path);
        }
        // 1.21 kept the copy slot base in the items package; it lives on in world.inventory.
        t.registerClassRedirect(NEOFORGE + "items/StackCopySlot", NEOFORGE + "world/inventory/StackCopySlot");
    }

    /** The old capabilities, for mods built before 26.1.2, each linked to the current one through {@link #CALLS}. */
    public static void registerCapabilities(RetromodTransformer t) {
        registerClasses(t);
        for (String holder : CAPABILITY_HOLDERS) {
            RebuiltApiClasses.registerTree(t, REBUILT + holder);
            t.registerClassRedirect(NEOFORGE + holder, REBUILT + holder);
        }
        RebuiltApiClasses.registerTree(t, CALLS);
        LegacyHandlerCapabilityBridge.registerCallRedirects(t, CALLS, true);
    }
}
