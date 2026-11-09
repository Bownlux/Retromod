/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.neoforge.LegacyTransferApi;
import com.retromod.util.McReflect;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Points a Forge mod's item, fluid and energy handler classes at NeoForge's, or at Retromod's rebuilt ones where NeoForge removed them. */
public final class ForgeTransferApiMoves {

    private static final String FORGE = "net/minecraftforge/";
    private static final String NEO = "net/neoforged/neoforge/";
    private static final String REBUILT = RebuiltApiClasses.ROOT + "neoforge/";

    /** Forge's handler classes that NeoForge kept, by their path under each loader's root. */
    static final List<String> PATHS = List.of(
            "items/IItemHandler", "items/IItemHandlerModifiable", "items/ItemStackHandler", "items/ItemHandlerHelper",
            "items/SlotItemHandler",
            "items/wrapper/CombinedInvWrapper", "items/wrapper/EmptyHandler", "items/wrapper/EntityArmorInvWrapper",
            "items/wrapper/EntityEquipmentInvWrapper", "items/wrapper/EntityHandsInvWrapper", "items/wrapper/InvWrapper",
            "items/wrapper/PlayerArmorInvWrapper", "items/wrapper/PlayerInvWrapper", "items/wrapper/PlayerMainInvWrapper",
            "items/wrapper/PlayerOffhandInvWrapper", "items/wrapper/RangedWrapper", "items/wrapper/SidedInvWrapper",
            "fluids/IFluidTank", "fluids/FluidActionResult", "fluids/FluidUtil",
            "fluids/capability/IFluidHandler", "fluids/capability/IFluidHandler$FluidAction", "fluids/capability/IFluidHandlerItem",
            "fluids/capability/templates/EmptyFluidHandler", "fluids/capability/templates/VoidFluidHandler",
            "fluids/capability/templates/FluidTank", "fluids/capability/templates/FluidHandlerItemStack",
            "fluids/capability/templates/FluidHandlerItemStack$Consumable",
            "fluids/capability/templates/FluidHandlerItemStack$SwapEmpty",
            "fluids/capability/templates/FluidHandlerItemStackSimple",
            "fluids/capability/templates/FluidHandlerItemStackSimple$Consumable",
            "fluids/capability/templates/FluidHandlerItemStackSimple$SwapEmpty",
            "energy/IEnergyStorage", "energy/EnergyStorage", "energy/EmptyEnergyStorage");

    /** Forge's fluid types, which NeoForge keeps on every version, 26.3 included. */
    static final List<String> KEPT = List.of("fluids/FluidStack", "fluids/FluidType", "fluids/FluidType$Properties");

    /** The one NeoForge renamed. */
    private static final Map<String, String> RENAMED = Map.of("items/wrapper/EmptyHandler", "items/wrapper/EmptyItemHandler");

    static final String RECIPE_WRAPPER = FORGE + "items/wrapper/RecipeWrapper";
    /** Forge's RecipeWrapper was a Container; NeoForge's is a recipe input, so the old shape is rebuilt. */
    public static final String OLD_RECIPE_WRAPPER = RebuiltApiClasses.ROOT + "forge/items/wrapper/RecipeWrapper";
    /** The Forge-only helper and save calls of those classes. */
    public static final String HANDLERS = RebuiltApiClasses.ROOT + "forge/items/LegacyForgeItemHandlers";
    /** Forge's FluidStack members that NeoForge's lacks. */
    static final String FLUID_STACKS = RebuiltApiClasses.ROOT + "forge/fluids/LegacyForgeFluidStacks";

    private ForgeTransferApiMoves() {}

    /** Registers the moves on a NeoForge target; returns how many classes moved. */
    public static int register(RetromodTransformer t) {
        if (!McReflect.isNeoForge()) return 0;
        boolean rebuilt = LegacyTransferApi.hostRemovedHandlers();
        // On 26.3 the rebuilt classes come in, and NeoForge's names lead to them too.
        if (rebuilt) LegacyTransferApi.register(t);
        int moved = 0;
        for (String path : PATHS) {
            String target = target(path);
            if (!rebuilt && !hostHas(target)) continue;
            t.registerClassRedirect(FORGE + path, target);
            moved++;
        }
        for (String path : KEPT) {
            if (!hostHas(NEO + path)) continue;
            t.registerClassRedirect(FORGE + path, NEO + path);
            moved++;
        }
        RebuiltApiClasses.registerTree(t, OLD_RECIPE_WRAPPER, rebuilt ? toRebuiltHandlers() : null);
        t.registerClassRedirect(RECIPE_WRAPPER, OLD_RECIPE_WRAPPER);
        registerHelperCalls(t);
        return moved + 1;
    }

    /** Where a Forge path's class lives on this target: NeoForge's class, or the rebuilt one once NeoForge removed it. */
    public static String target(String path) {
        String neoPath = RENAMED.getOrDefault(path, path);
        return (LegacyTransferApi.hostRemovedHandlers() ? REBUILT : NEO) + neoPath;
    }

    // A readable host answers for itself; offline, NeoForge 1.21 to 26.2 ship every one of them.
    private static boolean hostHas(String internalName) {
        if (ClassResourceInspector.read(NEO + "capabilities/Capabilities") == null) return true;
        return ClassResourceInspector.exists(internalName);
    }

    /** The FluidStack calls a Forge mod makes that NeoForge's FluidStack answers under other names, or not at all. */
    private static void registerFluidStackCalls(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, FLUID_STACKS);
        String compound = "Lnet/minecraft/nbt/CompoundTag;";
        String object = "Ljava/lang/Object;";
        String neoStack = "L" + NEO + "fluids/FluidStack;";
        String name = "()Lnet/minecraft/network/chat/Component;";
        for (String owner : List.of(FORGE + "fluids/FluidStack", NEO + "fluids/FluidStack")) {
            String stack = "L" + owner + ";";
            // NeoForge 1.21.1 still has these; from 1.21.11 they are gone.
            for (String compare : List.of("isFluidEqual", "containsFluid", "isFluidStackIdentical")) {
                if (!fluidStackLacks(compare, "(" + neoStack + ")Z")) continue;
                t.registerMethodRedirect(owner, compare, "(" + stack + ")Z", FLUID_STACKS, compare, "(" + object + object + ")Z");
            }
            if (fluidStackLacks("getDisplayName", name)) {
                t.registerMethodRedirect(owner, "getDisplayName", name, FLUID_STACKS, "getDisplayName", "(" + object + ")" + object);
            }
            // Forge's own members, which no NeoForge has.
            t.registerMethodRedirect(owner, "getRawFluid", "()Lnet/minecraft/world/level/material/Fluid;", FLUID_STACKS,
                    "getRawFluid", "(" + object + ")" + object);
            t.registerMethodRedirect(owner, "writeToNBT", "(" + compound + ")" + compound, FLUID_STACKS, "writeToNBT",
                    "(" + object + compound + ")" + compound);
            t.registerMethodRedirect(owner, "loadFluidStackFromNBT", "(" + compound + ")" + stack, FLUID_STACKS,
                    "loadFluidStackFromNBT", "(" + compound + ")" + object);
        }
    }

    /** Whether NeoForge's FluidStack lacks the member: read from the host, else from the target (verified on 21.1 and 21.11). */
    private static boolean fluidStackLacks(String name, String desc) {
        org.objectweb.asm.tree.ClassNode stack = ClassResourceInspector.read(NEO + "fluids/FluidStack");
        if (stack != null) return stack.methods.stream().noneMatch(m -> m.name.equals(name) && m.desc.equals(desc));
        return com.retromod.core.RetromodVersion.compareMcVersions(com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "1.21.11") >= 0;
    }

    /** NeoForge's handler names as the rebuilt ones, for the rebuilt wrapper on a host that removed them. */
    private static Remapper toRebuiltHandlers() {
        Map<String, String> names = new LinkedHashMap<>();
        for (String type : List.of("items/IItemHandler", "items/IItemHandlerModifiable")) names.put(NEO + type, REBUILT + type);
        return new SimpleRemapper(names);
    }

    /** Forge's ItemHandlerHelper methods NeoForge dropped, and the no-argument saves of Forge's ItemStackHandler. */
    private static void registerHelperCalls(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, HANDLERS);
        String stack = "Lnet/minecraft/world/item/ItemStack;";
        String compound = "Lnet/minecraft/nbt/CompoundTag;";
        // The class move runs first in each pass, so the calls are matched under every name the owner can have.
        for (String owner : List.of(FORGE + "items/ItemHandlerHelper", target("items/ItemHandlerHelper"))) {
            t.registerMethodRedirect(owner, "canItemStacksStack", "(" + stack + stack + ")Z", HANDLERS, "canItemStacksStack",
                    "(" + stack + stack + ")Z");
            t.registerMethodRedirect(owner, "canItemStacksStackRelaxed", "(" + stack + stack + ")Z", HANDLERS,
                    "canItemStacksStackRelaxed", "(" + stack + stack + ")Z");
            t.registerMethodRedirect(owner, "copyStackWithSize", "(" + stack + "I)" + stack, HANDLERS, "copyStackWithSize",
                    "(" + stack + "I)" + stack);
        }
        registerFluidStackCalls(t);
        for (String owner : List.of(FORGE + "items/ItemStackHandler", target("items/ItemStackHandler"))) {
            t.registerInheritedMethodRedirect(owner, "serializeNBT", "()" + compound, HANDLERS, "serializeNBT",
                    "(Ljava/lang/Object;)" + compound);
            t.registerInheritedMethodRedirect(owner, "deserializeNBT", "(" + compound + ")V", HANDLERS, "deserializeNBT",
                    "(Ljava/lang/Object;" + compound + ")V");
        }
    }
}
