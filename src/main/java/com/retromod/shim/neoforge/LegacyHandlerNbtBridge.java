/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.neoforge.embedded.LegacyHandlerNbt;
import com.retromod.util.McReflect;
import org.objectweb.asm.tree.ClassNode;

/** Keeps the 1.21.1 NBT save calls on NeoForge's item, fluid, and energy handlers, which 1.21.6 replaced with ValueIO. */
public final class LegacyHandlerNbtBridge {

    static final String SUPPORT = "com/retromod/shim/neoforge/embedded/LegacyHandlerNbt";
    static final String ITEM_HANDLER = "net/neoforged/neoforge/items/ItemStackHandler";
    static final String FLUID_TANK = "net/neoforged/neoforge/fluids/capability/templates/FluidTank";
    static final String ENERGY_STORAGE = "net/neoforged/neoforge/energy/EnergyStorage";
    private static final String PROVIDER = "Lnet/minecraft/core/HolderLookup$Provider;";
    private static final String COMPOUND = "Lnet/minecraft/nbt/CompoundTag;";
    private static final String TAG = "Lnet/minecraft/nbt/Tag;";
    private static final String OBJECT = "Ljava/lang/Object;";

    private LegacyHandlerNbtBridge() {}

    public static void register(RetromodTransformer t) {
        if (hostDroppedNbtMethods()) registerCalls(t);
    }

    /**
     * Whether the host's handlers lost the NBT methods. On 26.3 the handlers themselves are gone and
     * the rebuilt ones Retromod supplies keep the old methods. Offline, the version decides.
     */
    static boolean hostDroppedNbtMethods() {
        if (LegacyTransferApi.hostRemovedHandlers()) return false;
        ClassNode handler = ClassResourceInspector.read(ITEM_HANDLER);
        if (handler != null) return handler.methods.stream().noneMatch(m -> m.name.equals("serializeNBT"));
        return McReflect.isNeoForge() && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.6") >= 0;
    }

    static void registerCalls(RetromodTransformer t) {
        SyntheticEmbedder.registerClassResource(t, SUPPORT, LegacyHandlerNbt.class);
        // Inherited, so a call through the mod's own handler subclass, and its super call, reach the helper too.
        call(t, ITEM_HANDLER, "serializeNBT", "(" + PROVIDER + ")" + COMPOUND, "serializeNBT");
        call(t, ITEM_HANDLER, "deserializeNBT", "(" + PROVIDER + COMPOUND + ")V", "deserializeNBT");
        call(t, FLUID_TANK, "writeToNBT", "(" + PROVIDER + COMPOUND + ")" + COMPOUND, "writeToNBT");
        call(t, FLUID_TANK, "readFromNBT", "(" + PROVIDER + COMPOUND + ")L" + FLUID_TANK + ";", "readFromNBT");
        call(t, ENERGY_STORAGE, "serializeNBT", "(" + PROVIDER + ")" + TAG, "energySerializeNBT");
        call(t, ENERGY_STORAGE, "deserializeNBT", "(" + PROVIDER + TAG + ")V", "energyDeserializeNBT");
    }

    // The helper takes the receiver first and every reference as an object; the transformer casts the result back.
    private static void call(RetromodTransformer t, String owner, String name, String desc, String helper) {
        org.objectweb.asm.Type[] arguments = org.objectweb.asm.Type.getArgumentTypes(desc);
        boolean returns = org.objectweb.asm.Type.getReturnType(desc).getSort() != org.objectweb.asm.Type.VOID;
        String helperDesc = "(" + OBJECT.repeat(arguments.length + 1) + ")" + (returns ? OBJECT : "V");
        t.registerInheritedMethodRedirect(owner, name, desc, SUPPORT, helper, helperDesc);
        t.registerSuperCallHelper(SUPPORT, helper, helperDesc, "super$" + helper);
    }
}
