/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.util.Map;

/** Answers the {@code ItemStack}, {@code ContainerHelper} and {@code NbtUtils} NBT members 1.21.5 and 1.21.6 removed from Retromod's rebuilt copies. */
public final class LegacyItemNbtApi {

    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String CONTAINER_HELPER = "net/minecraft/world/ContainerHelper";
    static final String STACK_MEMBERS = RebuiltApiClasses.ROOT + "minecraft/world/item/ItemStackMembers";
    static final String CONTAINER_MEMBERS = RebuiltApiClasses.ROOT + "minecraft/world/ContainerHelperMembers";
    static final String NBT_UTILS = "net/minecraft/nbt/NbtUtils";
    static final String NBT_UTILS_MEMBERS = RebuiltApiClasses.ROOT + "minecraft/nbt/NbtUtilsMembers";

    private static final String L_STACK = "L" + ITEM_STACK + ";";
    private static final String L_PROVIDER = "Lnet/minecraft/core/HolderLookup$Provider;";
    private static final String L_TAG = "Lnet/minecraft/nbt/Tag;";
    private static final String L_COMPOUND = "Lnet/minecraft/nbt/CompoundTag;";
    private static final String L_LIST = "Lnet/minecraft/core/NonNullList;";

    static final String PARSE_OPTIONAL = "(" + L_PROVIDER + L_COMPOUND + ")" + L_STACK;
    static final String SAVE_OPTIONAL = "(" + L_PROVIDER + ")" + L_TAG;
    static final String PARSE = "(" + L_PROVIDER + L_TAG + ")Ljava/util/Optional;";
    static final String SAVE = "(" + L_PROVIDER + ")" + L_TAG;
    static final String SAVE_INTO = "(" + L_PROVIDER + L_TAG + ")" + L_TAG;
    static final String LOAD_ALL = "(" + L_COMPOUND + L_LIST + L_PROVIDER + ")V";
    static final String SAVE_ALL = "(" + L_COMPOUND + L_LIST + L_PROVIDER + ")" + L_COMPOUND;
    static final String SAVE_ALL_FLAG = "(" + L_COMPOUND + L_LIST + "Z" + L_PROVIDER + ")" + L_COMPOUND;

    // A Fabric 1.21.5 to 1.21.11 host runs intermediary names, the same ones in each of those
    // versions (checked against their intermediary tables). The rebuilt classes are copied into
    // those names under their own, and the old members a mod calls keep the ids they had before.
    // The one-argument constructor: NeoForge 1.21.11 ships ASM 9.8, which has no api-level one.
    static final SimpleRemapper INTERMEDIARY = new SimpleRemapper(Map.ofEntries(
            Map.entry(ITEM_STACK, "net/minecraft/class_1799"),
            Map.entry(CONTAINER_HELPER, "net/minecraft/class_1262"),
            // The outer class is named too, by the Provider's InnerClasses entry.
            Map.entry("net/minecraft/core/HolderLookup", "net/minecraft/class_7225"),
            Map.entry("net/minecraft/core/HolderLookup$Provider", "net/minecraft/class_7225$class_7874"),
            Map.entry("net/minecraft/core/NonNullList", "net/minecraft/class_2371"),
            Map.entry("net/minecraft/resources/RegistryOps", "net/minecraft/class_6903"),
            Map.entry("net/minecraft/nbt/Tag", "net/minecraft/class_2520"),
            Map.entry("net/minecraft/nbt/CompoundTag", "net/minecraft/class_2487"),
            Map.entry("net/minecraft/nbt/ListTag", "net/minecraft/class_2499"),
            Map.entry("net/minecraft/nbt/NbtOps", "net/minecraft/class_2509"),
            Map.entry(STACK_MEMBERS, RebuiltApiClasses.ROOT + "intermediary/minecraft/world/item/ItemStackMembers"),
            Map.entry(CONTAINER_MEMBERS, RebuiltApiClasses.ROOT + "intermediary/minecraft/world/ContainerHelperMembers"),
            // What the rebuilt classes call on the host.
            Map.entry(ITEM_STACK + ".CODEC", "field_24671"),
            Map.entry(ITEM_STACK + ".EMPTY", "field_8037"),
            Map.entry(ITEM_STACK + ".isEmpty()Z", "method_7960"),
            Map.entry("net/minecraft/nbt/NbtOps.INSTANCE", "field_11560"),
            Map.entry("net/minecraft/core/HolderLookup$Provider.createSerializationContext"
                    + "(Lcom/mojang/serialization/DynamicOps;)Lnet/minecraft/resources/RegistryOps;", "method_57093"),
            Map.entry("net/minecraft/nbt/CompoundTag.isEmpty()Z", "method_33133"),
            Map.entry("net/minecraft/nbt/CompoundTag.putByte(Ljava/lang/String;B)V", "method_10567"),
            Map.entry("net/minecraft/nbt/CompoundTag.getByteOr(Ljava/lang/String;B)B", "method_68562"),
            Map.entry("net/minecraft/nbt/CompoundTag.getListOrEmpty(Ljava/lang/String;)Lnet/minecraft/nbt/ListTag;",
                    "method_68569"),
            Map.entry("net/minecraft/nbt/CompoundTag.put(Ljava/lang/String;" + L_TAG + ")" + L_TAG, "method_10566"),
            // The old members a mod calls.
            Map.entry(ITEM_STACK + ".parseOptional" + PARSE_OPTIONAL, "method_57359"),
            Map.entry(ITEM_STACK + ".saveOptional" + SAVE_OPTIONAL, "method_57375"),
            Map.entry(ITEM_STACK + ".parse" + PARSE, "method_57360"),
            Map.entry(ITEM_STACK + ".save" + SAVE, "method_57358"),
            Map.entry(ITEM_STACK + ".save" + SAVE_INTO, "method_57376"),
            Map.entry(CONTAINER_HELPER + ".loadAllItems" + LOAD_ALL, "method_5429"),
            Map.entry(CONTAINER_HELPER + ".saveAllItems" + SAVE_ALL, "method_5426"),
            Map.entry(CONTAINER_HELPER + ".saveAllItems" + SAVE_ALL_FLAG, "method_5427")));

    private LegacyItemNbtApi() {}

    /** {@code ItemStack.parseOptional} and {@code saveOptional}, which 1.21.5 removed. */
    public static void register1215(RetromodTransformer t) {
        register1215(t, null);
    }

    /** The same members in the intermediary names a Fabric 1.21.5 to 1.21.11 host runs. */
    public static void register1215Intermediary(RetromodTransformer t) {
        register1215(t, INTERMEDIARY);
    }

    /** {@code ItemStack.parse} and {@code save}, and the {@code ContainerHelper} tag forms, which 1.21.6 removed. */
    public static void register1216(RetromodTransformer t) {
        register1216(t, null);
    }

    /** The same members in the intermediary names a Fabric 1.21.6 to 1.21.11 host runs. */
    public static void register1216Intermediary(RetromodTransformer t) {
        register1216(t, INTERMEDIARY);
    }

    private static void register1215(RetromodTransformer t, Remapper names) {
        RebuiltApiClasses.registerTree(t, STACK_MEMBERS, names);
        redirect(t, names, ITEM_STACK, "parseOptional", PARSE_OPTIONAL, STACK_MEMBERS, false);
        // ItemStack is final, so the instance call becomes a static one with the stack first.
        redirect(t, names, ITEM_STACK, "saveOptional", SAVE_OPTIONAL, STACK_MEMBERS, true);
        // The NbtUtils members have no intermediary table here, so only Mojang-named mods take them.
        if (names == null) registerNbtUtils(t);
    }

    /** {@code NbtUtils}' UUID and block position members, which 1.21.5 removed, in the formats old saves hold. */
    private static void registerNbtUtils(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, NBT_UTILS_MEMBERS);
        String pos = "Lnet/minecraft/core/BlockPos;";
        String[][] members = {
                {"createUUID", "(Ljava/util/UUID;)Lnet/minecraft/nbt/IntArrayTag;", "createUUID"},
                {"loadUUID", "(" + L_TAG + ")Ljava/util/UUID;", "loadUUID"},
                // Before 1.20.5 a position was a compound of X, Y and Z.
                {"readBlockPos", "(" + L_COMPOUND + ")" + pos, "readBlockPos"},
                {"writeBlockPos", "(" + pos + ")" + L_COMPOUND, "writeBlockPos"},
                // From 1.20.5 to 1.21.4 it was an array of three ints.
                {"readBlockPos", "(" + L_COMPOUND + "Ljava/lang/String;)Ljava/util/Optional;", "readBlockPos"},
                {"writeBlockPos", "(" + pos + ")" + L_TAG, "writeBlockPosArray"}};
        for (String[] member : members) {
            t.registerMethodRedirect(NBT_UTILS, member[0], member[1], NBT_UTILS_MEMBERS, member[2], member[1]);
        }
    }

    private static void register1216(RetromodTransformer t, Remapper names) {
        // Mods saved names in tags as component JSON, whose serializer 1.21.6 removed. No intermediary table here.
        if (names == null) LegacyComponentJsonBridge.register(t);
        // The container class names the stack class, so both are registered.
        RebuiltApiClasses.registerTree(t, CONTAINER_MEMBERS, names);
        redirect(t, names, ITEM_STACK, "parse", PARSE, STACK_MEMBERS, false);
        redirect(t, names, ITEM_STACK, "save", SAVE, STACK_MEMBERS, true);
        redirect(t, names, ITEM_STACK, "save", SAVE_INTO, STACK_MEMBERS, true);
        // 1.21.6 kept the method names for its value input and output forms, so only these descriptors move.
        redirect(t, names, CONTAINER_HELPER, "loadAllItems", LOAD_ALL, CONTAINER_MEMBERS, false);
        redirect(t, names, CONTAINER_HELPER, "saveAllItems", SAVE_ALL, CONTAINER_MEMBERS, false);
        redirect(t, names, CONTAINER_HELPER, "saveAllItems", SAVE_ALL_FLAG, CONTAINER_MEMBERS, false);
    }

    /** Points an old member at the rebuilt method of the same name, in Mojang names or in {@code names}. */
    private static void redirect(RetromodTransformer t, Remapper names, String owner, String name, String desc,
            String helper, boolean instance) {
        String helperDesc = instance ? "(L" + owner + ";" + desc.substring(1) : desc;
        if (names == null) {
            t.registerMethodRedirect(owner, name, desc, helper, name, helperDesc, instance);
            return;
        }
        t.registerMethodRedirect(names.mapType(owner), names.mapMethodName(owner, name, desc), names.mapMethodDesc(desc),
                names.mapType(helper), name, names.mapMethodDesc(helperDesc), instance);
    }
}
