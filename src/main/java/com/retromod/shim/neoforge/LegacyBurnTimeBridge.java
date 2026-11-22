/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

/** Rebuilds NeoForge 21.1's {@code getBurnTime(RecipeType)} reads on hosts that dropped them. */
public final class LegacyBurnTimeBridge {

    static final String STACK = "net/minecraft/world/item/ItemStack";
    static final String ITEM = "net/minecraft/world/item/Item";
    static final String STACK_EXTENSION = "net/neoforged/neoforge/common/extensions/IItemStackExtension";
    static final String ITEM_EXTENSION = "net/neoforged/neoforge/common/extensions/IItemExtension";
    static final String RECIPE_TYPE = "Lnet/minecraft/world/item/crafting/RecipeType;";
    static final String STACK_DESC = "(" + RECIPE_TYPE + ")I";
    static final String ITEM_DESC = "(L" + STACK + ";" + RECIPE_TYPE + ")I";
    static final String FUEL_VALUES = "net/minecraft/world/level/block/entity/FuelValues";
    static final String COMPONENTS = "net/minecraft/core/component/DataComponents";
    static final String ROOT = RebuiltApiClasses.ROOT + "neoforge/common/extensions/";
    /** 26.3 moved fuel into the cooking fuel item component. */
    public static final String COMPONENT_HELPER = ROOT + "LegacyBurnTimes";
    /** NeoForge 21.2 to 26.2 kept the read but made it take the world's fuel values. */
    public static final String FUEL_VALUES_HELPER = ROOT + "LegacyFuelValueBurnTimes";

    private LegacyBurnTimeBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    /** Registers the redirects for the host whose classes {@code host} reads. */
    public static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        String helper = helperFor(host);
        if (helper == null) return;
        RebuiltApiClasses.registerTree(t, helper);
        // Mods call the stack read on ItemStack or on the extension, and the item read on Item or the extension.
        t.registerMethodRedirect(STACK, "getBurnTime", STACK_DESC, helper, "getBurnTime", "(L" + STACK + ";" + STACK_DESC.substring(1), true);
        t.registerMethodRedirect(STACK_EXTENSION, "getBurnTime", STACK_DESC, helper, "getBurnTime", "(L" + STACK + ";" + STACK_DESC.substring(1), true);
        String itemHelperDesc = "(L" + ITEM + ";" + ITEM_DESC.substring(1);
        t.registerInheritedMethodRedirect(ITEM, "getBurnTime", ITEM_DESC, helper, "getBurnTime", itemHelperDesc);
        t.registerMethodRedirect(ITEM_EXTENSION, "getBurnTime", ITEM_DESC, helper, "getBurnTime", itemHelperDesc, true);
        // The helper runs a mod item's own override, so that override's super call needs the plain answer.
        t.registerSuperCallHelper(helper, "getBurnTime", itemHelperDesc, "superGetBurnTime");
        // Those hosts' furnaces call the fuel values form, so an old item override gets one that answers through it.
        if (helper.equals(FUEL_VALUES_HELPER)) t.enablePostProcess(com.retromod.shim.common.LegacyBurnTimeOverrideAdapter.FLAG);
    }

    /** The helper this host needs, or null when the host still has the 21.1 read or is not NeoForge. */
    public static String helperFor(Function<String, ClassNode> host) {
        ClassNode extension = host.apply(STACK_EXTENSION);
        if (extension == null) return offlineHelper();
        if (declares(extension, "getBurnTime", STACK_DESC)) return null;
        if (host.apply(FUEL_VALUES) != null) return FUEL_VALUES_HELPER;
        return hasField(host.apply(COMPONENTS), "COOKING_FUEL") ? COMPONENT_HELPER : null;
    }

    /** Without NeoForge's classes to read, the target decides; NeoForge 21.2 took the read away. */
    private static String offlineHelper() {
        if (targetAtLeast("26.3")) return COMPONENT_HELPER;
        return targetAtLeast("1.21.2") ? FUEL_VALUES_HELPER : null;
    }

    private static boolean declares(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean hasField(ClassNode owner, String name) {
        if (owner == null) return false;
        for (FieldNode field : owner.fields) if (field.name.equals(name)) return true;
        return false;
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }
}
